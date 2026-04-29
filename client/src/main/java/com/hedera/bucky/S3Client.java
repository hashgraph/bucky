// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky;

import static java.lang.System.Logger.Level.DEBUG;

import com.hedera.bucky.utils.Preconditions;
import com.hedera.bucky.utils.StringUtilities;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Simple standalone S3 client for uploading, downloading and listing objects from S3.
 */
@SuppressWarnings("JavadocLinkAsPlainText")
public final class S3Client implements AutoCloseable {
    /* Set the system property to allow restricted headers in HttpClient */
    static {
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "Host,Content-Length");
    }
    /** HMAC SHA256 algorithm for signing **/
    private static final String ALGORITHM_HMAC_SHA256 = "HmacSHA256";
    /** SHA256 hash of an empty request body **/
    private static final String EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    /** Unsigned payload header value **/
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    /** AWS4 signature scheme and algorithm **/
    private static final String SCHEME = "AWS4";
    /** AWS4 signature algorithm **/
    private static final String ALGORITHM = "HMAC-SHA256";
    /** AWS4 signature terminator **/
    private static final String TERMINATOR = "aws4_request";
    /** Minimum number of objects that can be requested in a single list-objects page. */
    public static final int LIST_OBJECTS_MIN = 1;
    /** Maximum number of objects that can be requested in a single list-objects page, as defined by the S3 API. */
    public static final int LIST_OBJECTS_MAX = 1000;
    /** Format strings for the date/time and date stamps required during signing **/
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC);
    /** Format string for the date stamp **/
    private static final DateTimeFormatter DATE_STAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(java.time.ZoneOffset.UTC);
    /** The query parameter separator **/
    private static final char QUERY_PARAMETER_SEPARATOR = '&';
    /** The query parameter value separator **/
    private static final char QUERY_PARAMETER_VALUE_SEPARATOR = '=';
    /** The size limit of response body to be read for an exceptional response */
    private static final int ERROR_BODY_MAX_LENGTH = 32_768;
    /** The GET HTTP Method canonical name **/
    private static final String GET = "GET";
    /** The POST HTTP Method canonical name **/
    private static final String POST = "POST";
    /** The PUT HTTP Method canonical name **/
    private static final String PUT = "PUT";
    /** The DELETE HTTP Method canonical name **/
    private static final String DELETE = "DELETE";

    /** Logger for retry warnings. */
    private static final System.Logger LOGGER = System.getLogger(S3Client.class.getName());

    /** HTTP status codes that indicate a transient failure worth retrying. */
    private static final Set<Integer> RETRIABLE_STATUS_CODES = Set.of(408, 409, 429, 500, 502, 503, 504);

    /** S3 error codes inside a 400 response body that indicate a retriable condition. */
    private static final Set<String> RETRIABLE_400_ERROR_CODES =
            Set.of("RequestTimeTooSkewed", "ExpiredToken", "BadDigest", "RequestTimeout");

    /** The S3 region name **/
    private final String regionName;
    /** The S3 endpoint URL **/
    private final String endpoint;
    /** The S3 bucket name **/
    private final String bucketName;
    /** The S3 access key **/
    private final String accessKey;
    /** The S3 secret key **/
    private final String secretKey;
    /** The HTTP client used for making requests **/
    private final HttpClient httpClient;
    /** The document builder factory used for response body parsing **/
    private final DocumentBuilderFactory documentBuilderFactory;
    /** The retry policy controlling back-off behaviour **/
    private final RetryPolicy retryPolicy;

    /**
     * Constructor for S3Client using the default retry policy ({@link RetryPolicy#DEFAULT}).
     *
     * @param regionName The S3 region name (e.g. "us-east-1").
     * @param endpoint The S3 endpoint URL (e.g. "https://s3.amazonaws.com/").
     * @param bucketName The name of the S3 bucket.
     * @param accessKey The S3 access key.
     * @param secretKey The S3 secret key.
     * @throws S3ClientInitializationException if an error occurs during
     * client initialization or preconditions are not met.
     */
    public S3Client(
            @NonNull final String regionName,
            @NonNull final String endpoint,
            @NonNull final String bucketName,
            @NonNull final String accessKey,
            @NonNull final String secretKey)
            throws S3ClientInitializationException {
        this(regionName, endpoint, bucketName, accessKey, secretKey, RetryPolicy.DEFAULT);
    }

    /**
     * Constructor for S3Client with a custom retry policy.
     *
     * @param regionName The S3 region name (e.g. "us-east-1").
     * @param endpoint The S3 endpoint URL (e.g. "https://s3.amazonaws.com/").
     * @param bucketName The name of the S3 bucket.
     * @param accessKey The S3 access key.
     * @param secretKey The S3 secret key.
     * @param retryPolicy The retry policy to use for transient failures. Cannot be null.
     * @throws S3ClientInitializationException if an error occurs during
     * client initialization or preconditions are not met.
     */
    public S3Client(
            @NonNull final String regionName,
            @NonNull final String endpoint,
            @NonNull final String bucketName,
            @NonNull final String accessKey,
            @NonNull final String secretKey,
            @NonNull final RetryPolicy retryPolicy)
            throws S3ClientInitializationException {
        try {
            this.regionName = Preconditions.requireNotBlank(regionName);
            this.endpoint = Preconditions.requireNotBlank(endpoint).endsWith("/") ? endpoint : endpoint + "/";
            this.bucketName = Preconditions.requireNotBlank(bucketName);
            this.accessKey = Preconditions.requireNotBlank(accessKey);
            this.secretKey = Preconditions.requireNotBlank(secretKey);
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            this.httpClient = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            this.documentBuilderFactory = DocumentBuilderFactory.newInstance();
            this.documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            this.documentBuilderFactory.setNamespaceAware(true);
        } catch (final Exception e) {
            throw new S3ClientInitializationException(e);
        }
    }

    /**
     * Closes the HTTP client.
     */
    @Override
    public void close() {
        this.httpClient.close();
    }

    /**
     * Lists objects in the S3 bucket with the specified prefix
     *
     * @param prefix The prefix to filter the objects.
     * Use {@link StringUtilities#EMPTY} for a wildcard prefix (list all).
     * @param maxResults The maximum number of results to return.
     * @return A list of object keys.
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException if an error occurs while reading the response body
     */
    public List<String> listObjects(@NonNull final String prefix, final int maxResults)
            throws S3ResponseException, IOException {
        Objects.requireNonNull(prefix);
        Preconditions.requireInRange(maxResults, LIST_OBJECTS_MIN, LIST_OBJECTS_MAX);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String canonicalQueryString = "list-type=2&prefix=" + prefix + "&max-keys=" + maxResults;
                final String url = endpoint + bucketName + "/?" + canonicalQueryString;
                final HttpResponse<InputStream> response =
                        request(url, GET, Collections.emptyMap(), null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final String formattedPrefix = StringUtilities.isBlank(prefix) ? "BLANK_PREFIX" : prefix;
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message = "Unsuccessful listing of objects: prefix=%s, maxResults=%s"
                                .formatted(formattedPrefix, maxResults);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                    return extractChildText(parseDocument(in).getElementsByTagName("Contents"), "Key");
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) {
                    throw networkEx.getCause();
                }
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) {
                    throw responseEx;
                }
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }

        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Lists one page of objects (or common prefixes) in the S3 bucket with the specified prefix.
     *
     * <p>Pass {@code null} as {@code continuationToken} to start from the beginning.
     * When the returned {@link ListPage#continuationToken()} is non-null, pass it back
     * to retrieve the next page.  Results on each page are returned in alphabetical order.
     *
     * <p>When {@code delimiter} is {@code null}, the page contains object keys parsed from
     * {@code <Contents>} elements.  When {@code delimiter} is non-null (e.g. {@code "/"}),
     * it is sent as the S3 {@code delimiter} query parameter and the page contains common
     * prefixes parsed from {@code <CommonPrefixes>} elements instead.
     *
     * @param prefix            the prefix to filter by; use an empty string to list all
     * @param continuationToken token from the previous page, or {@code null} for the first page
     * @param delimiter         S3 grouping delimiter, or {@code null} for a flat object listing
     * @param maxResults        maximum number of results to return (1–1000)
     * @return a page of keys (or common prefixes) and an optional token for the next page
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException         if an error occurs while reading the response body
     */
    public ListPage listObjectsPage(
            @NonNull final String prefix,
            @Nullable final String continuationToken,
            @Nullable final String delimiter,
            final int maxResults)
            throws S3ResponseException, IOException {
        Objects.requireNonNull(prefix);
        Preconditions.requireInRange(maxResults, LIST_OBJECTS_MIN, LIST_OBJECTS_MAX);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String url = endpoint + bucketName + "/?"
                        + buildListPageQueryString(prefix, maxResults, delimiter, continuationToken);
                final HttpResponse<InputStream> response =
                        request(url, GET, Collections.emptyMap(), null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final String formattedPrefix = StringUtilities.isBlank(prefix) ? "BLANK_PREFIX" : prefix;
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message = "Unsuccessful listing of objects: prefix=%s, maxResults=%s"
                                .formatted(formattedPrefix, maxResults);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                    final Document document = parseDocument(in);
                    final String outerTag = delimiter != null ? "CommonPrefixes" : "Contents";
                    final String innerTag = delimiter != null ? "Prefix" : "Key";
                    final List<String> results = extractChildText(document.getElementsByTagName(outerTag), innerTag);
                    final NodeList tokenNodes = document.getElementsByTagName("NextContinuationToken");
                    final String nextToken =
                            tokenNodes.getLength() > 0 ? tokenNodes.item(0).getTextContent() : null;
                    return new ListPage(results, nextToken);
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Uploads a file to S3 using multipart upload, assumes the file is small enough as uses single part upload.
     *
     * @param objectKey the key for the object in S3 (e.g., "my_folder/my_file.txt")
     * @param storageClass the storage class (e.g., "STANDARD", "REDUCED_REDUNDANCY")
     * @param content the content of the file as a string
     * @throws S3ResponseException if a non-200 response is received from S3 during file upload
     * @throws IOException if an error occurs while reading the response body in case of non 200 response
     */
    public void uploadTextFile(
            @NonNull final String objectKey, @NonNull final String storageClass, @NonNull final String content)
            throws S3ResponseException, IOException {
        Preconditions.requireNotBlank(objectKey);
        Preconditions.requireNotBlank(storageClass);
        Preconditions.requireNotBlank(content);
        final byte[] contentData = content.getBytes(StandardCharsets.UTF_8);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final Map<String, String> headers = new HashMap<>();
                headers.put("content-length", Integer.toString(contentData.length));
                headers.put("content-type", "text/plain");
                headers.put("x-amz-storage-class", storageClass);
                headers.put("x-amz-content-sha256", base64(sha256(contentData)));
                final String url = endpoint + bucketName + "/" + urlEncode(objectKey, true);
                final HttpResponse<InputStream> response =
                        request(url, PUT, headers, contentData, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message = "Failed to upload text file: key=%s".formatted(objectKey);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                }
                return;
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
    }

    /**
     * Downloads a text file from S3, assumes the file is small enough as uses single part download.
     *
     * @param key the key for the object in S3 (e.g., "my_folder/my_file.txt"), cannot be blank
     * @return the content of the file as a string, null if the file doesn't exist
     * @throws S3ResponseException if a non-200 response is received from S3 during file download
     * @throws IOException if an error occurs while reading the response body in case of non 200 response
     */
    public String downloadTextFile(@NonNull final String key) throws S3ResponseException, IOException {
        Preconditions.requireNotBlank(key);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String url = endpoint + bucketName + "/" + urlEncode(key, true);
                final HttpResponse<InputStream> response =
                        request(url, GET, Collections.emptyMap(), null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode == 404) {
                        return null;
                    } else if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message = "Failed to download text file: key=%s".formatted(key);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    } else {
                        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Uploads a file to S3 using multipart upload.
     *
     * @param objectKey the key for the object in S3 (e.g., "my_folder/my_file.txt"), cannot be blank
     * @param storageClass the storage class (e.g., "STANDARD", "REDUCED_REDUNDANCY"), cannot be blank
     * @param contentIterable an Iterable of byte arrays representing the file content. cannot be null
     * @param contentType the content type of the file (e.g., "text/plain"). cannot be blank
     * @throws S3ResponseException if a non-200 response is received from S3 during file upload
     * @throws IOException if an error occurs while reading the response body in case of non 200 response
     */
    public void uploadFile(
            @NonNull final String objectKey,
            @NonNull final String storageClass,
            @NonNull final Iterator<byte[]> contentIterable,
            @NonNull final String contentType)
            throws S3ResponseException, IOException {
        // start the multipart upload
        final String uploadId = createMultipartUpload(objectKey, storageClass, contentType);
        // create a list to store the ETags of the uploaded parts
        final List<String> eTags = new ArrayList<>();
        // Multipart upload works in chunks of 5MB
        final byte[] chunk = new byte[5 * 1024 * 1024];
        // track the offset in the chunk
        int offsetInChunk = 0;
        // We need to iterate of the contentIterable and build 5MB chunks that we can upload
        // to S3. We will use System.arrayCopy() to build the chunks.
        while (contentIterable.hasNext()) {
            final byte[] next = contentIterable.next();
            int remainingContent = next.length;
            while (remainingContent > 0) {
                // if the remaining content is larger than the chunk size, we need to split it
                if (remainingContent > chunk.length - offsetInChunk) {
                    int length = chunk.length - offsetInChunk;
                    System.arraycopy(next, next.length - remainingContent, chunk, offsetInChunk, length);
                    remainingContent -= length;
                    // we now have a full chunk so need to upload the chunk to S3
                    eTags.add(multipartUploadPart(objectKey, uploadId, eTags.size() + 1, chunk));
                    // reset the offset in the chunk
                    offsetInChunk = 0;
                } else {
                    // we have a partial chunk so need to copy the remaining content to the chunk
                    System.arraycopy(next, next.length - remainingContent, chunk, offsetInChunk, remainingContent);
                    offsetInChunk += remainingContent;
                    remainingContent = 0;
                }
            }
        }
        // now upload the last chunk if it is not empty
        if (offsetInChunk > 0) {
            // extract just the content of the chunk
            byte[] lastChunk = new byte[offsetInChunk];
            System.arraycopy(chunk, 0, lastChunk, 0, offsetInChunk);
            // we have a partial chunk so need to upload it to S3
            eTags.add(multipartUploadPart(objectKey, uploadId, eTags.size() + 1, lastChunk));
        }
        // Complete the multipart upload
        completeMultipartUpload(objectKey, uploadId, eTags);
    }

    /**
     * This method will list all multipart uploads currently active for the given
     * bucket. It will return a map of object keys and upload IDs, or an empty
     * map if none is found.
     *
     * @return a map of all multipart uploads in the bucket, where the key is
     * the object key and the value is a list of upload IDs, or an empty map if
     * none are found.
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException if an error occurs while reading the response body
     */
    @NonNull
    public Map<String, List<String>> listMultipartUploads() throws S3ResponseException, IOException {
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String url = endpoint + bucketName + "/" + "?" + "uploads=";
                final HttpResponse<InputStream> response =
                        request(url, GET, Collections.emptyMap(), null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        throw new S3ResponseException(
                                responseStatusCode, responseBody, responseHeaders, "Failed to list multipart uploads");
                    }
                    final Map<String, List<String>> uploadIds = new TreeMap<>();
                    final Document bodyAsDocument = parseDocument(in);
                    final NodeList uploads = bodyAsDocument.getElementsByTagName("Upload");
                    final int length = uploads.getLength();
                    for (int i = 0; i < length; i++) {
                        final Element uploadElement = (Element) uploads.item(i);
                        final String key = uploadElement
                                .getElementsByTagName("Key")
                                .item(0)
                                .getTextContent();
                        final String uploadId = uploadElement
                                .getElementsByTagName("UploadId")
                                .item(0)
                                .getTextContent();
                        uploadIds.computeIfAbsent(key, k -> new ArrayList<>()).add(uploadId);
                    }
                    return Collections.unmodifiableMap(uploadIds);
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * This method will abort a multipart upload for the specified object key.
     *
     * @param key the object key. cannot be blank
     * @param uploadId the upload ID for the multipart upload. cannot be blank
     * @throws S3ResponseException if a non-204 response is received from S3
     * @throws IOException if an error occurs while reading the response body in case of non-204 response
     */
    public void abortMultipartUpload(@NonNull final String key, @NonNull final String uploadId)
            throws S3ResponseException, IOException {
        Preconditions.requireNotBlank(key);
        Preconditions.requireNotBlank(uploadId);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String url = endpoint + bucketName + "/" + key + "?" + "uploadId=" + uploadId;
                final HttpResponse<InputStream> response =
                        request(url, DELETE, Collections.emptyMap(), null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 204) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message =
                                "Failed to abort multipart upload: key=%s, uploadId=%s".formatted(key, uploadId);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                }
                return;
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
    }

    /**
     * Deletes an object from the S3 bucket.
     *
     * @param key the object key to delete. Cannot be blank
     * @throws S3ResponseException if a non-204 response is received from S3
     * @throws IOException if an error occurs while reading the response body in case of non-204 response
     */
    public void deleteObject(@NonNull final String key) throws S3ResponseException, IOException {
        Preconditions.requireNotBlank(key);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String url = endpoint + bucketName + "/" + key;
                final HttpResponse<InputStream> response =
                        request(url, DELETE, Collections.emptyMap(), null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 204) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        throw new S3ResponseException(
                                responseStatusCode,
                                responseBody,
                                responseHeaders,
                                "Failed to remove object: key=%s".formatted(key));
                    }
                }
                return;
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
    }

    /**
     * Creates a multipart upload for the specified object key.
     *
     * @param key The object key. Cannot be null
     * @param storageClass The storage class (e.g. "STANDARD", "REDUCED_REDUNDANCY"), nullable
     * @param contentType The content type of the object. Cannot be null
     * @return The upload ID for the multipart upload
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException if an error occurs while reading the response body
     */
    public String createMultipartUpload(
            @NonNull final String key, @NonNull final String storageClass, @NonNull final String contentType)
            throws S3ResponseException, IOException {
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final Map<String, String> headers = new HashMap<>();
                headers.put("content-type", contentType);
                headers.put("x-amz-storage-class", storageClass);
                final String url = endpoint + bucketName + "/" + key + "?" + "uploads=";
                final HttpResponse<InputStream> response =
                        request(url, POST, headers, null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message = "Failed to create multipart upload: key=%s".formatted(key);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                    return parseDocument(in)
                            .getElementsByTagName("UploadId")
                            .item(0)
                            .getTextContent();
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Uploads a part of a multipart upload.
     *
     * @param key The object key. Cannot be blank
     * @param uploadId The upload ID for the multipart upload. Cannot be blank
     * @param partNumber The part number (1-based)
     * @param partData The data for the part. Cannot be null
     * @return The ETag of the uploaded part
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException if an error occurs while reading the response body
     */
    public String multipartUploadPart(
            @NonNull final String key,
            @NonNull final String uploadId,
            final int partNumber,
            @NonNull final byte[] partData)
            throws S3ResponseException, IOException {
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String canonicalQueryString = "uploadId=" + uploadId + "&partNumber=" + partNumber;
                final Map<String, String> headers = new HashMap<>();
                headers.put("content-length", Integer.toString(partData.length));
                headers.put("content-type", "application/octet-stream");
                headers.put("x-amz-content-sha256", base64(sha256(partData)));
                final String url = endpoint + bucketName + "/" + key + "?" + canonicalQueryString;
                final HttpResponse<InputStream> response =
                        request(url, PUT, headers, partData, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message = "Failed to upload multipart part: key=%s, uploadId=%s, partNumber=%d"
                                .formatted(key, uploadId, partNumber);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                    return response.headers().firstValue("ETag").orElse(null);
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Completes a multipart upload.
     *
     * @param key The object key. Cannot be blank
     * @param uploadId The upload ID for the multipart upload. Cannot be blank
     * @param eTags The list of ETags for the uploaded parts. Cannot be null
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException if an error occurs while reading the response body in case of non 200 response
     */
    public void completeMultipartUpload(
            @NonNull final String key, @NonNull final String uploadId, @NonNull final List<String> eTags)
            throws S3ResponseException, IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("<CompleteMultipartUpload>");
        for (int i = 0; i < eTags.size(); i++) {
            sb.append("<Part><PartNumber>")
                    .append(i + 1)
                    .append("</PartNumber><ETag>")
                    .append(eTags.get(i))
                    .append("</ETag></Part>");
        }
        sb.append("</CompleteMultipartUpload>");
        final byte[] requestBody = sb.toString().getBytes(StandardCharsets.UTF_8);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final Map<String, String> headers = new HashMap<>();
                headers.put("content-type", "application/xml");
                final String url = endpoint + bucketName + "/" + key + "?" + "uploadId=" + uploadId;
                final HttpResponse<InputStream> response =
                        request(url, POST, headers, requestBody, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message =
                                "Failed to complete multipart upload: key=%s, uploadId=%s".formatted(key, uploadId);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                }
                return;
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
    }

    /**
     * Lists the parts that have already been uploaded for an in-progress multipart upload.
     *
     * @param key the object key of the multipart upload. Cannot be blank
     * @param uploadId the upload ID returned by {@link #createMultipartUpload}. Cannot be blank
     * @return the parts uploaded so far, sorted by part number; empty if no parts have been uploaded
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException if an error occurs while reading the response body
     */
    @NonNull
    public List<PartInfo> listParts(@NonNull final String key, @NonNull final String uploadId)
            throws S3ResponseException, IOException {
        Preconditions.requireNotBlank(key);
        Preconditions.requireNotBlank(uploadId);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String canonicalQueryString = "max-parts=1000&uploadId=" + uploadId;
                final String url = endpoint + bucketName + "/" + urlEncode(key, true) + "?" + canonicalQueryString;
                final HttpResponse<InputStream> response =
                        request(url, GET, Collections.emptyMap(), null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message = "Failed to list parts: key=%s, uploadId=%s".formatted(key, uploadId);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                    final SortedMap<Integer, PartInfo> parts = new ConcurrentSkipListMap<>();
                    final NodeList partNodes = parseDocument(in).getElementsByTagName("Part");
                    for (int i = 0; i < partNodes.getLength(); i++) {
                        final Element part = (Element) partNodes.item(i);
                        final int partNumber = Integer.parseInt(
                                part.getElementsByTagName("PartNumber").item(0).getTextContent());
                        final long size = Long.parseLong(
                                part.getElementsByTagName("Size").item(0).getTextContent());
                        final String etag =
                                part.getElementsByTagName("ETag").item(0).getTextContent();
                        parts.put(partNumber, new PartInfo(partNumber, size, etag));
                    }
                    return parts.values().stream().toList();
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Copies a byte range from an existing S3 object into a part of a new multipart upload.
     * This is a server-side copy — no data is transferred through the client.
     *
     * @param sourceKey  the key of the object to copy from. Cannot be blank
     * @param startByte  the first byte of the range to copy (inclusive, 0-based)
     * @param endByte    the last byte of the range to copy (inclusive)
     * @param destKey    the key of the multipart upload target. Cannot be blank
     * @param uploadId   the upload ID of the target multipart upload. Cannot be blank
     * @param partNumber the 1-based part number within the target upload
     * @return the ETag of the newly created part
     * @throws S3ResponseException if a non-200 response is received from S3
     * @throws IOException if an error occurs while reading the response body
     */
    @NonNull
    public String uploadPartCopy(
            @NonNull final String sourceKey,
            final long startByte,
            final long endByte,
            @NonNull final String destKey,
            @NonNull final String uploadId,
            final int partNumber)
            throws S3ResponseException, IOException {
        Preconditions.requireNotBlank(sourceKey);
        Preconditions.requireNotBlank(destKey);
        Preconditions.requireNotBlank(uploadId);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final String canonicalQueryString = "partNumber=" + partNumber + "&uploadId=" + uploadId;
                final Map<String, String> headers = new HashMap<>();
                headers.put("x-amz-copy-source", "/" + bucketName + "/" + urlEncode(sourceKey, true));
                headers.put("x-amz-copy-source-range", "bytes=" + startByte + "-" + endByte);
                final String url = endpoint + bucketName + "/" + urlEncode(destKey, true) + "?" + canonicalQueryString;
                final HttpResponse<InputStream> response =
                        request(url, PUT, headers, null, BodyHandlers.ofInputStream());
                final int responseStatusCode = response.statusCode();
                try (final InputStream in = response.body()) {
                    if (responseStatusCode != 200) {
                        final byte[] responseBody = in.readNBytes(ERROR_BODY_MAX_LENGTH);
                        final HttpHeaders responseHeaders = response.headers();
                        final String message =
                                "Failed to copy part: sourceKey=%s, destKey=%s, uploadId=%s, partNumber=%d"
                                        .formatted(sourceKey, destKey, uploadId, partNumber);
                        throw new S3ResponseException(responseStatusCode, responseBody, responseHeaders, message);
                    }
                    return parseDocument(in)
                            .getElementsByTagName("ETag")
                            .item(0)
                            .getTextContent();
                }
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Downloads a byte range of an S3 object into memory.
     *
     * @param key       the object key. Cannot be blank
     * @param startByte the first byte of the range to download (inclusive, 0-based)
     * @param endByte   the last byte of the range to download (inclusive)
     * @return the requested bytes
     * @throws S3ResponseException if a non-206/200 response is received from S3
     * @throws IOException if an error occurs while reading the response body
     */
    @NonNull
    public byte[] downloadObjectRange(@NonNull final String key, final long startByte, final long endByte)
            throws S3ResponseException, IOException {
        Preconditions.requireNotBlank(key);
        final long deadlineNanos = System.nanoTime() + retryPolicy.totalTimeoutMs() * 1_000_000L;
        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            try {
                final Map<String, String> headers = new HashMap<>();
                headers.put("Range", "bytes=" + startByte + "-" + endByte);
                final String url = endpoint + bucketName + "/" + urlEncode(key, true);
                final HttpResponse<byte[]> response = request(url, GET, headers, null, BodyHandlers.ofByteArray());
                final int responseStatusCode = response.statusCode();
                if (responseStatusCode == 206 || responseStatusCode == 200) {
                    return response.body();
                }
                final String message =
                        "Failed to download object range: key=%s, range=%d-%d".formatted(key, startByte, endByte);
                throw new S3ResponseException(responseStatusCode, response.body(), response.headers(), message);
            } catch (final UncheckedIOException networkEx) {
                if (!canRetryNetworkError(networkEx, attempt, deadlineNanos)) throw networkEx.getCause();
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw networkEx.getCause();
                }
            } catch (final S3ResponseException responseEx) {
                if (!canRetryResponseError(responseEx, attempt, deadlineNanos)) throw responseEx;
                sleepBeforeRetry(attempt, deadlineNanos);
                if (System.nanoTime() >= deadlineNanos) {
                    throw responseEx;
                }
            }
        }
        // unreachable — RetryPolicy validates maxAttempts >= 1, so the last iteration always returns or throws
        return null;
    }

    /**
     * Builds the canonical query string for a list-objects-v2 request.
     *
     * @param prefix            prefix filter
     * @param maxResults        maximum keys to return
     * @param delimiter         grouping delimiter, or {@code null} for a flat listing
     * @param continuationToken pagination token from a previous page, or {@code null}
     * @return the assembled query string (not URL-encoded as a whole; individual values are encoded)
     */
    private static String buildListPageQueryString(
            final String prefix,
            final int maxResults,
            @Nullable final String delimiter,
            @Nullable final String continuationToken) {
        final StringBuilder queryString = new StringBuilder("list-type=2&max-keys=")
                .append(maxResults)
                .append("&prefix=")
                .append(prefix);
        if (delimiter != null) {
            queryString.append("&delimiter=").append(URLEncoder.encode(delimiter, StandardCharsets.UTF_8));
        }
        if (continuationToken != null) {
            queryString
                    .append("&continuation-token=")
                    .append(URLEncoder.encode(continuationToken, StandardCharsets.UTF_8));
        }
        return queryString.toString();
    }

    /**
     * Extracts the text content of the first {@code childTag} element found inside each element of
     * {@code parentNodes}, skipping any parent that does not contain the child tag.
     *
     * @param parentNodes the list of parent elements to iterate over
     * @param childTag    the tag name of the child element whose text content is wanted
     * @return a mutable list of text values, in document order
     */
    private List<String> extractChildText(final NodeList parentNodes, final String childTag) {
        final List<String> result = new ArrayList<>();
        for (int i = 0; i < parentNodes.getLength(); i++) {
            final NodeList children = ((Element) parentNodes.item(i)).getElementsByTagName(childTag);
            if (children.getLength() > 0) {
                result.add(children.item(0).getTextContent());
            }
        }
        return result;
    }

    /**
     * Carries one page of S3 list results plus an optional continuation token for the next page.
     *
     * @param keys                the object keys (or common prefixes) returned in this page
     * @param continuationToken   token to pass to the next call to retrieve the following page;
     *                            {@code null} when this is the last page
     */
    public record ListPage(List<String> keys, @Nullable String continuationToken) {}

    /**
     * Carries the metadata of one part in an in-progress multipart upload.
     *
     * @param partNumber the 1-based part number
     * @param size       the size of the part in bytes
     * @param etag       the ETag returned by S3 when the part was uploaded
     */
    public record PartInfo(int partNumber, long size, String etag) {}

    // -------------------------------------------------------------------------
    // Retry infrastructure
    // -------------------------------------------------------------------------

    private void sleepBeforeRetry(final int attempt, final long deadlineNanos) {
        final long remainingNanos = deadlineNanos - System.nanoTime();
        // Cap shift at 62 to keep the left-shift result positive: shifting a long by 63
        // flips the sign bit; shifting by 64+ wraps around (JVM masks shift amount to 6 bits).
        final int shift = Math.min(attempt - 1, 62);
        // Full-jitter exponential back-off: pick a random delay in [0, min(maxDelay, base * 2^attempt)].
        // The min guards against the exponential growing past the configured ceiling.
        final long capNanos = Math.min(retryPolicy.maxDelayMs(), retryPolicy.baseDelayMs() << shift) * 1_000_000L;
        final long maxNanos = capNanos <= 0 ? 0 : ThreadLocalRandom.current().nextLong(0, capNanos + 1);
        // Never sleep longer than the time remaining before the total-timeout deadline.
        final long sleepNanos = Math.clamp(remainingNanos, 0L, maxNanos);
        if (sleepNanos > 0) {
            LockSupport.parkNanos(sleepNanos);
        }
    }

    /**
     * Returns {@code true} if the network error is retriable and the loop should continue.
     */
    private boolean canRetryNetworkError(final UncheckedIOException ex, final int attempt, final long deadlineNanos) {
        final boolean canRetry = attempt + 1 < retryPolicy.maxAttempts()
                && System.nanoTime() < deadlineNanos
                && !Thread.currentThread().isInterrupted();
        if (canRetry) {
            LOGGER.log(
                    DEBUG,
                    "S3 network error on attempt {0}/{1}, will retry: {2}",
                    attempt + 1,
                    retryPolicy.maxAttempts(),
                    ex.getMessage());
        }
        return canRetry;
    }

    /**
     * Returns {@code true} if the response error is retriable and the loop should continue.
     */
    private boolean canRetryResponseError(final S3ResponseException ex, final int attempt, final long deadlineNanos) {
        final boolean canRetry = isRetriable(ex.getResponseStatusCode(), ex.getResponseBody())
                && attempt + 1 < retryPolicy.maxAttempts()
                && System.nanoTime() < deadlineNanos;
        if (canRetry) {
            final String reqId = firstHeader(ex.getResponseHeaders(), "x-amz-request-id");
            final String extId = firstHeader(ex.getResponseHeaders(), "x-amz-id-2");
            LOGGER.log(
                    DEBUG,
                    "S3 retriable HTTP {0} on attempt {1}/{2}, x-amz-request-id={3}, x-amz-id-2={4}",
                    ex.getResponseStatusCode(),
                    attempt + 1,
                    retryPolicy.maxAttempts(),
                    reqId,
                    extId);
        }
        return canRetry;
    }

    /**
     * Returns true when the given status code and body represent a condition worth retrying.
     */
    private boolean isRetriable(final int status, final byte[] body) {
        return RETRIABLE_STATUS_CODES.contains(status) || (status == 400 && isRetriable400(body));
    }

    /**
     * Parses the S3 XML error body to check whether the {@code <Code>} element names a retriable
     * error condition. Returns {@code false} for non-XML bodies or missing {@code <Code>}.
     */
    private boolean isRetriable400(final byte[] body) {
        boolean isRetriable = false;

        if (body != null && body.length > 0) {
            try {
                final Document doc = documentBuilderFactory.newDocumentBuilder().parse(new ByteArrayInputStream(body));
                final NodeList codes = doc.getElementsByTagName("Code");
                if (codes.getLength() > 0) {
                    isRetriable =
                            RETRIABLE_400_ERROR_CODES.contains(codes.item(0).getTextContent());
                }
            } catch (final SAXException | ParserConfigurationException | IOException | RuntimeException ignored) {
                // non-XML or missing <Code> — treat as non-retriable
            }
        }
        return isRetriable;
    }

    /** Extracts the first value of {@code headerName} from {@code headers}, or {@code "n/a"}. */
    private static String firstHeader(final HttpHeaders headers, final String headerName) {
        return headers != null ? headers.firstValue(headerName).orElse("n/a") : "n/a";
    }

    // -------------------------------------------------------------------------
    // HTTP layer
    // -------------------------------------------------------------------------

    /**
     * Performs an HTTP request to S3 to the specified URL with the given parameters.
     *
     * @param url The URL to send the request to
     * @param httpMethod The HTTP method to use (e.g., GET, POST, PUT)
     * @param headers The request headers to send
     * @param requestBody The request body to send, or null if no request body is needed
     * @param bodyHandler The body handler for parsing response
     * @return HTTP response and result parsed using the provided body handler
     */
    private <T> HttpResponse<T> request(
            final String url,
            final String httpMethod,
            final Map<String, String> headers,
            byte[] requestBody,
            final BodyHandler<T> bodyHandler) {
        try {
            // the region-specific endpoint to the target object expressed in path style
            final URI endpointUrl = new URI(url);
            final Map<String, String> localHeaders = new TreeMap<>(headers);
            final String contentHashString;
            if (requestBody == null || requestBody.length == 0) {
                contentHashString = EMPTY_BODY_SHA256;
                requestBody = new byte[0];
            } else {
                contentHashString = UNSIGNED_PAYLOAD;
                localHeaders.put("content-length", Integer.toString(requestBody.length));
            }
            localHeaders.put("x-amz-content-sha256", contentHashString);
            // extract query parameters from the URL
            final Map<String, String> q = extractQueryParameters(endpointUrl);
            // compute the authorization header
            final String authorization = computeSignatureForAuthorizationHeader(
                    endpointUrl, httpMethod, regionName, localHeaders, q, contentHashString, accessKey, secretKey);
            // place the computed signature into a formatted 'Authorization' header and call S3
            localHeaders.put("Authorization", authorization);
            // build the request
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(endpointUrl);
            requestBuilder = switch (httpMethod) {
                case POST -> requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(requestBody));
                case PUT -> requestBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(requestBody));
                case GET -> requestBuilder.GET();
                case DELETE -> requestBuilder.DELETE();
                default -> throw new IllegalArgumentException("Unsupported HTTP method: " + httpMethod);
            };
            requestBuilder = requestBuilder.headers(localHeaders.entrySet().stream()
                    .flatMap(entry -> Stream.of(entry.getKey(), entry.getValue()))
                    .toArray(String[]::new));
            if (retryPolicy.requestTimeoutMs() > 0) {
                requestBuilder = requestBuilder.timeout(Duration.ofMillis(retryPolicy.requestTimeoutMs()));
            }
            final HttpRequest request = requestBuilder.build();
            return httpClient.send(request, bodyHandler);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final InterruptedException | URISyntaxException e) {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Extract parameters from a query string, preserving encoding.
     *
     * @param endpointUrl The endpoint URL to extract parameters from.
     * @return The list of parameters, in the order they were found.
     */
    private static Map<String, String> extractQueryParameters(final URI endpointUrl) {
        final String rawQuery = endpointUrl.getQuery();
        if (rawQuery == null) {
            return Collections.emptyMap();
        } else {
            final Map<String, String> results = new HashMap<>();
            final int endIndex = rawQuery.length() - 1;
            int index = 0;
            while (index <= endIndex) {
                // Ideally we should first look for '&', then look for '=' before the '&', but that's not how AWS
                // understand query parsing. A string such as "?foo&bar=qux" will be understood as one parameter with
                // the
                // name "foo&bar" and value "qux".
                String name;
                String value;
                int nameValueSeparatorIndex = rawQuery.indexOf(QUERY_PARAMETER_VALUE_SEPARATOR, index);
                if (nameValueSeparatorIndex < 0) {
                    // No value
                    name = rawQuery.substring(index);
                    value = null;
                    index = endIndex + 1;
                } else {
                    int parameterSeparatorIndex = rawQuery.indexOf(QUERY_PARAMETER_SEPARATOR, nameValueSeparatorIndex);
                    if (parameterSeparatorIndex < 0) {
                        parameterSeparatorIndex = endIndex + 1;
                    }
                    name = rawQuery.substring(index, nameValueSeparatorIndex);
                    value = rawQuery.substring(nameValueSeparatorIndex + 1, parameterSeparatorIndex);
                    index = parameterSeparatorIndex + 1;
                }
                // note that value = null is valid as we can have a parameter without a value in
                // a query string (legal http)
                results.put(
                        URLDecoder.decode(name, StandardCharsets.UTF_8),
                        value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8));
            }
            return results;
        }
    }

    /**
     * Computes an AWS4 signature for a request, ready for inclusion as an 'Authorization' header.
     *
     * @param endpointUrl the url to which the request is being made
     * @param httpMethod the HTTP method (GET, POST, PUT, etc.)
     * @param regionName the AWS region name
     * @param headers The request headers, 'Host' and 'X-Amz-Date' will be added to this set
     * @param queryParameters Any query parameters that will be added to the endpoint. The parameters should be
     * specified in canonical format
     * @param bodyHash Precomputed SHA256 hash of the request body content; this value should also be set as the
     * header 'X-Amz-Content-SHA256' for non-streaming uploads
     * @param awsAccessKey The user's AWS Access Key
     * @param awsSecretKey The user's AWS Secret Key
     * @return The computed authorization string for the request. This value needs to be set as the header
     * 'Authorization' on the further HTTP request.
     */
    private static String computeSignatureForAuthorizationHeader(
            final URI endpointUrl,
            final String httpMethod,
            final String regionName,
            final @NonNull Map<String, String> headers,
            final @NonNull Map<String, String> queryParameters,
            final String bodyHash,
            final String awsAccessKey,
            final String awsSecretKey) {
        // first, get the date and time for the further request, and convert
        // to ISO 8601 format for use in signature generation
        final ZonedDateTime now = ZonedDateTime.now(java.time.ZoneOffset.UTC);
        final String dateTimeStamp = DATE_TIME_FORMATTER.format(now);
        final String dateStamp = DATE_STAMP_FORMATTER.format(now);
        // update the headers with required 'x-amz-date' and 'host' values
        headers.put("x-amz-date", dateTimeStamp);

        // determine host header
        String hostHeader = endpointUrl.getHost();
        final int port = endpointUrl.getPort();
        if (port >= 0) {
            hostHeader = hostHeader.concat(":" + port);
        }
        // update the host header
        headers.put("Host", hostHeader);

        // canonicalize the headers; we need the set of header names as well as the
        // names and values to go into the signature process
        final String canonicalizedHeaderNames = headers.keySet().stream()
                .map(header -> header.toLowerCase(Locale.ENGLISH))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.joining(";"));
        // The canonical header requires value entries in sorted order, and multiple white spaces in the values should
        // be compressed to a single space.
        final String canonicalizedHeaders = headers.entrySet().stream()
                        .sorted(Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                        .map(entry -> entry.getKey().toLowerCase(Locale.ENGLISH).replaceAll("\\s+", " ") + ":"
                                + entry.getValue().replaceAll("\\s+", " "))
                        .collect(Collectors.joining("\n"))
                + "\n";

        // if any query string parameters have been supplied, canonicalize them
        final String canonicalizedQueryParameters = queryParameters.entrySet().stream()
                .map(entry -> urlEncode(entry.getKey(), false) + "="
                        + (entry.getValue() == null ? "" : urlEncode(entry.getValue(), false)))
                .sorted()
                .collect(Collectors.joining("&"));

        // canonicalizedResourcePath
        final String path = endpointUrl.getPath();
        final String canonicalizedResourcePath = path.isEmpty() ? "/" : urlEncode(path, true);
        // canonicalize the various components of the request
        final String canonicalRequest = httpMethod + "\n"
                + canonicalizedResourcePath + "\n"
                + canonicalizedQueryParameters + "\n"
                + canonicalizedHeaders + "\n"
                + canonicalizedHeaderNames + "\n"
                + bodyHash;

        // construct the string to be signed
        final String scope = dateStamp + "/" + regionName + "/" + "s3" + "/" + TERMINATOR;
        final String stringToSign = SCHEME + "-" + ALGORITHM + "\n" + dateTimeStamp + "\n" + scope + "\n"
                + HexFormat.of().formatHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        // compute the signing key
        final byte[] kSecret = (SCHEME + awsSecretKey).getBytes(StandardCharsets.UTF_8);
        final byte[] kDate = sign(dateStamp, kSecret);
        final byte[] kRegion = sign(regionName, kDate);
        final byte[] kService = sign("s3", kRegion);
        final byte[] kSigning = sign(TERMINATOR, kService);
        final byte[] signature = sign(stringToSign, kSigning);

        // build and return the authorization header
        final String credentialsAuthorizationHeader = "Credential=" + awsAccessKey + "/" + scope;
        final String signedHeadersAuthorizationHeader = "SignedHeaders=" + canonicalizedHeaderNames;
        final String signatureAuthorizationHeader =
                "Signature=" + HexFormat.of().formatHex(signature);
        return SCHEME + "-" + ALGORITHM + " " + credentialsAuthorizationHeader + ", " + signedHeadersAuthorizationHeader
                + ", " + signatureAuthorizationHeader;
    }

    /**
     * Signs the given data using HMAC SHA256 with the specified key.
     *
     * @param stringData The data to sign
     * @param key The key to use for signing
     * @return The signed data as a byte array
     */
    private static byte[] sign(final String stringData, final byte[] key) {
        try {
            final Mac mac = Mac.getInstance(S3Client.ALGORITHM_HMAC_SHA256);
            mac.init(new SecretKeySpec(key, S3Client.ALGORITHM_HMAC_SHA256));
            return mac.doFinal(stringData.getBytes(StandardCharsets.UTF_8));
        } catch (final NoSuchAlgorithmException | InvalidKeyException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Encodes the given URL using UTF-8 encoding.
     *
     * @param url the URL to encode
     * @param keepPathSlash true, if slashes in the path should be preserved, false
     * @return the encoded URL
     */
    private static String urlEncode(final String url, final boolean keepPathSlash) {
        final String encoded = URLEncoder.encode(url, StandardCharsets.UTF_8).replace("+", "%20");
        if (keepPathSlash) {
            return encoded.replace("%2F", "/");
        } else {
            return encoded;
        }
    }

    /**
     * Computes the SHA-256 hash of the given data.
     *
     * @param data the data to hash
     * @return the SHA-256 hash as a byte array
     */
    private static byte[] sha256(final byte[] data) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(data);
            return md.digest();
        } catch (final NoSuchAlgorithmException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }

    /**
     * Encodes the byte array to a base64 string.
     *
     * @param data the byte array to encode
     * @return the base64 encoded string
     */
    private static String base64(final byte[] data) {
        return new String(Base64.getEncoder().encode(data));
    }

    /**
     * This method parses an XML document from an input stream. Uses the
     * configured {@link DocumentBuilderFactory}.
     *
     * @param is to parse
     * @return a {@link Document} parsed from the input stream
     */
    private Document parseDocument(final InputStream is) {
        try {
            return documentBuilderFactory.newDocumentBuilder().parse(is);
        } catch (final SAXException | ParserConfigurationException | IOException e) {
            throw new UncheckedIOException(new IOException(e));
        }
    }
}
