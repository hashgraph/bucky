// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.containers.GenericContainer;

/**
 * Unit tests for the {@link S3ClientTest} class.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class S3ClientTest {
    private static final String BUCKET_NAME = "test-bucket";
    private static final int MINIO_ROOT_PORT = 9000;
    private static final String MINIO_ROOT_USER = "minioadmin";
    private static final String MINIO_ROOT_PASSWORD = "minioadmin";
    private static final String REGION_NAME = "us-east-1";
    private GenericContainer<?> minioContainer;
    private MinioClient minioClient;
    private String endpoint;

    @SuppressWarnings({"resource", "HttpUrlsUsage"})
    @BeforeAll
    void setup() throws Exception {
        // Start MinIO container
        minioContainer = new GenericContainer<>("minio/minio:latest")
                .withCommand("server /data")
                .withExposedPorts(MINIO_ROOT_PORT)
                .withEnv("MINIO_ROOT_USER", MINIO_ROOT_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_ROOT_PASSWORD);
        minioContainer.start();
        // Initialize MinIO client
        endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(MINIO_ROOT_PORT);
        minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(MINIO_ROOT_USER, MINIO_ROOT_PASSWORD)
                .build();
        // Create a bucket
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(BUCKET_NAME).build());
    }

    @AfterAll
    void teardown() {
        if (minioContainer != null) {
            minioContainer.stop();
        }
    }

    /**
     * This test aims to verify that the
     * {@link S3Client#listObjects(String, int)} method will correctly return
     * existing objects in a bucket.
     */
    @Test
    @DisplayName("Test listObjects() correctly returns existing objects in a bucket")
    void testList() throws Exception {
        // Setup
        final String content = "Hello, MinIO!";
        final String keyPrefix = "block-";
        final List<String> expected = List.of(
                keyPrefix.concat("0.txt"),
                keyPrefix.concat("1.txt"),
                keyPrefix.concat("2.txt"),
                keyPrefix.concat("3.txt"),
                keyPrefix.concat("4.txt"));
        // verify that the bucket is empty before the test
        final boolean preCheck = minioClient
                .listObjects(ListObjectsArgs.builder()
                        .bucket(BUCKET_NAME)
                        .prefix(keyPrefix)
                        .maxKeys(100)
                        .build())
                .iterator()
                .hasNext();
        assertThat(preCheck).isFalse();
        // upload objects to the bucket
        for (final String object : expected) {
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(object).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            // Call
            final List<String> actual = s3Client.listObjects(keyPrefix, 100);
            // Assert
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
            // Call filter by max results
            final List<String> actualFilterMaxResults = s3Client.listObjects(keyPrefix, 2);
            // Assert
            assertThat(actualFilterMaxResults)
                    .containsExactlyInAnyOrderElementsOf(List.of(expected.get(0), expected.get(1)));
        }
    }

    /**
     * This test aims to verify that the
     * {@link S3Client#listObjects(String, int)} method will return an empty
     * list when no objects are found.
     */
    @Test
    @DisplayName("Test listObjects() returns empty  when no objects are found")
    void testListNonExistentObjects() throws Exception {
        try (final S3Client s3Client = client()) {
            // Call
            final List<String> actual = s3Client.listObjects("non-existent-prefix", 100);
            // Assert
            assertThat(actual).isNotNull().isEmpty();
        }
    }

    /**
     * This test aims to verify that the multipart upload functionality of the
     * S3Client works correctly. We manually build 3 parts of a file and then
     * proceed to upload them using the multipart upload API of the S3Client.
     * Then we verify that the data exists in the bucket by downloading it
     * via the MinIO client and checking that the content matches what we
     * uploaded.
     */
    @Test
    @DisplayName("Test multipart upload")
    void testMultipartUpload() throws Exception {
        // Setup
        final String key = "testMultipartUploadSuccess.txt";
        // check that the object does not exist before the test
        final boolean preCheck = minioClient
                .listObjects(ListObjectsArgs.builder()
                        .bucket(BUCKET_NAME)
                        .prefix(key)
                        .maxKeys(100)
                        .build())
                .iterator()
                .hasNext();
        assertThat(preCheck).isFalse();
        final Random random = new Random(23131535653443L);
        final byte[] part1 = new byte[5 * 1024 * 1024];
        final byte[] part2 = new byte[5 * 1024 * 1024];
        final byte[] part3 = new byte[1024];
        random.nextBytes(part1);
        random.nextBytes(part2);
        random.nextBytes(part3);
        try (final S3Client s3Client = client()) {
            // Call
            final String uploadId = s3Client.createMultipartUpload(key, "STANDARD", "plain/text");
            final List<String> eTags = new ArrayList<>();
            eTags.add(s3Client.multipartUploadPart(key, uploadId, 1, part1));
            eTags.add(s3Client.multipartUploadPart(key, uploadId, 2, part2));
            eTags.add(s3Client.multipartUploadPart(key, uploadId, 3, part3));
            s3Client.completeMultipartUpload(key, uploadId, eTags);
        }
        // Assert
        // download with a minio client
        byte[] actual = minioClient
                .getObject(
                        GetObjectArgs.builder().bucket(BUCKET_NAME).object(key).build())
                .readAllBytes();
        // Verify the content
        byte[] expected = new byte[part1.length + part2.length + part3.length];
        System.arraycopy(part1, 0, expected, 0, part1.length);
        System.arraycopy(part2, 0, expected, part1.length, part2.length);
        System.arraycopy(part3, 0, expected, part1.length + part2.length, part3.length);
        assertThat(actual).hasSameSizeAs(expected).isEqualTo(expected).containsExactly(expected);
    }

    /**
     * This test aims to verify that the
     * {@link S3Client#uploadFile(String, String, Iterator, String)} method
     * will correctly upload a large file in parts to the S3 bucket.
     */
    @Test
    @DisplayName("Test upload of a large file")
    void testUploadFile() throws Exception {
        // Setup
        final int testContentSize = 8 * 1024 * 1024 + 826;
        final String key = "uploadOfLargeFileSuccessful.txt";
        // check that the object does not exist before the test
        final boolean preCheck = minioClient
                .listObjects(ListObjectsArgs.builder()
                        .bucket(BUCKET_NAME)
                        .prefix(key)
                        .maxKeys(100)
                        .build())
                .iterator()
                .hasNext();
        assertThat(preCheck).isFalse();
        // create sample string data
        final StringBuilder contentBuilder = new StringBuilder();
        while (contentBuilder.length() < testContentSize) {
            contentBuilder.append("foo bar baz");
        }
        final String content = contentBuilder.toString();
        byte[] expected = content.getBytes(StandardCharsets.UTF_8);
        // split content in random size parts
        final Random random = new Random(23131535653443L);
        final List<byte[]> parts = new ArrayList<>();
        int offset = 0;
        while (offset < expected.length) {
            int partSize = random.nextInt(1, 1024 * 1024);
            if (offset + partSize > expected.length) {
                partSize = expected.length - offset;
            }
            final byte[] part = new byte[partSize];
            System.arraycopy(expected, offset, part, 0, partSize);
            parts.add(part);
            offset += partSize;
        }
        // upload parts
        try (final S3Client s3Client = client()) {
            s3Client.uploadFile(key, "STANDARD", parts.iterator(), "plain/text");
        }
        // download with a minio client
        final byte[] actual = minioClient
                .getObject(
                        GetObjectArgs.builder().bucket(BUCKET_NAME).object(key).build())
                .readAllBytes();
        // Verify the content
        assertThat(actual)
                .hasSameSizeAs(expected)
                .isEqualTo(expected)
                .containsExactly(expected)
                .asString()
                .isEqualTo(content);
    }

    /**
     * This test aims to verify that the {@link S3Client#uploadTextFile(String, String, String)}
     * method will correctly upload a simple text file to the S3 bucket and
     * that the file can be downloaded via {@link S3Client#downloadTextFile(String)}
     * successfully.
     */
    @Test
    @DisplayName("Test upload and download of a text file")
    void testTextFileUploadAndDownload() throws Exception {
        // Setup
        final String key = "uploadSimpleTextFile.txt";
        final String expected = "Hello, MinIO!";
        // verify that the file does not exist in the bucket before the test
        final boolean preCheck = minioClient
                .listObjects(ListObjectsArgs.builder()
                        .bucket(BUCKET_NAME)
                        .prefix(key)
                        .maxKeys(100)
                        .build())
                .iterator()
                .hasNext();
        assertThat(preCheck).isFalse();
        try (final S3Client s3Client = client()) {
            // upload text file via the client
            assertDoesNotThrow(() -> s3Client.uploadTextFile(key, "STANDARD", expected));
            // check download with minio client
            assertEquals(
                    expected,
                    new String(
                            minioClient
                                    .getObject(GetObjectArgs.builder()
                                            .bucket(BUCKET_NAME)
                                            .object(key)
                                            .build())
                                    .readAllBytes(),
                            StandardCharsets.UTF_8),
                    "Downloaded content does not match expected content");
            // check download with s3 client
            assertEquals(
                    expected, s3Client.downloadTextFile(key), "Downloaded content does not match expected content");
        }
    }

    /**
     * This test aims to verify that the {@link S3Client#listMultipartUploads()} method
     * will correctly return existing multipart uploads.
     */
    @Test
    @DisplayName("Test listMultipartUpload() will correctly return existing multipart uploads")
    void testListMultipartUploads() throws Exception {
        // Setup
        final String key = "testListMultipartUploads.txt";
        try (final S3Client s3Client = client()) {
            // verify that there are no multipart uploads before the test
            // we need to filter the map by key because MinIO client is reused
            final Map<String, List<String>> preChek = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(preChek).isEmpty();
            // create a multipart upload
            final String expected = s3Client.createMultipartUpload(key, "STANDARD", "plain/text");
            // Assert
            // we need to filter the map by key because MinIO client is reused
            final Map<String, List<String>> actual = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(actual)
                    .isNotEmpty()
                    .hasSize(1)
                    .containsKey(key)
                    .extractingByKey(key)
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .isNotEmpty()
                    .hasSize(1)
                    .containsExactly(expected);
        }
    }

    /**
     * This test aims to verify that the {@link S3Client#listMultipartUploads()} method
     * will correctly return existing multipart uploads for multiple keys and values.
     */
    @Test
    @DisplayName("Test listMultipartUpload() will correctly return existing multipart uploads")
    void testListMultipartUploadsMultiKeyValue() throws Exception {
        // Setup
        final String key1 = "testListMultipartUploads1.txt";
        final String key2 = "testListMultipartUploads2.txt";
        try (final S3Client s3Client = client()) {
            // verify that there are no multipart uploads before the test
            // we need to filter the map by key because MinIO client is reused
            final Map<String, List<String>> preCheckList = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key1) || e.getKey().equals(key2))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            final boolean preCheck1 = preCheckList.containsKey(key1);
            final boolean preCheck2 = preCheckList.containsKey(key2);
            assertThat(preCheck1).isFalse().isEqualTo(preCheck2);
            // create a multipart upload
            final String key1expected1 = s3Client.createMultipartUpload(key1, "STANDARD", "plain/text");
            final String key1expected2 = s3Client.createMultipartUpload(key1, "STANDARD", "plain/text");
            final String key2expected1 = s3Client.createMultipartUpload(key2, "STANDARD", "plain/text");
            final String key2expected2 = s3Client.createMultipartUpload(key2, "STANDARD", "plain/text");
            // Assert
            // we need to filter the map by key because MinIO client is reused
            final Map<String, List<String>> actual = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key1) || e.getKey().equals(key2))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(actual).isNotEmpty().hasSize(2).containsKeys(key1, key2);
            assertThat(actual.get(key1)).isNotEmpty().hasSize(2).containsExactly(key1expected1, key1expected2);
            assertThat(actual.get(key2)).isNotEmpty().hasSize(2).containsExactly(key2expected1, key2expected2);
        }
    }

    /**
     * This test aims to verify that the {@link S3Client#abortMultipartUpload(String, String)}
     * method will correctly abort an existing multipart upload.
     */
    @Test
    @DisplayName("Test abortMultipartUpload() will correctly abort an existing multipart upload")
    void testAbortMultipartUpload() throws Exception {
        // Setup
        final String key = "testAbortMultipartUpload.txt";
        try (final S3Client s3Client = client()) {
            // verify that there are no multipart uploads before the test
            // we need to filter the map by key because MinIO client is reused
            final boolean preCheck = s3Client.listMultipartUploads().entrySet().stream()
                    .anyMatch(e -> e.getKey().equals(key));
            assertThat(preCheck).isFalse();
            // create a multipart upload
            final String uploadId = s3Client.createMultipartUpload(key, "STANDARD", "plain/text");
            // Assert that the upload exists
            // we need to filter the map by key because MinIO client is reused
            final Map<String, List<String>> actualBeforeAbort = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(actualBeforeAbort)
                    .isNotEmpty()
                    .hasSize(1)
                    .containsKey(key)
                    .extractingByKey(key)
                    .asInstanceOf(InstanceOfAssertFactories.LIST)
                    .isNotEmpty()
                    .hasSize(1)
                    .containsExactly(uploadId);
            // Abort the multipart upload
            s3Client.abortMultipartUpload(key, uploadId);
            // Assert that the upload is removed,
            // we need to filter the map by key because the MinIO client is reused
            final Map<String, List<String>> actualAfterAbort = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(actualAfterAbort).isEmpty();
        }
    }

    /**
     * This test aims to verify that the {@link S3Client#abortMultipartUpload(String, String)}
     * method will correctly abort an existing multipart upload with multiple parts.
     */
    @Test
    @DisplayName("Test abortMultipartUpload() will correctly abort an existing multipart upload with multiple parts")
    void testAbortMultipartUploadMultiKeyValue() throws Exception {
        // Setup
        final String key1 = "testAbortMultipartUploads1.txt";
        final String key2 = "testAbortMultipartUploads2.txt";
        try (final S3Client s3Client = client()) {
            // verify that there are no multipart uploads before the test
            // we need to filter the map by key because MinIO client is reused
            final Map<String, List<String>> listPreCheck = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key1) || e.getKey().equals(key2))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            final boolean preCheck1 = listPreCheck.containsKey(key1);
            final boolean preCheck2 = listPreCheck.containsKey(key2);
            assertThat(preCheck1).isFalse().isEqualTo(preCheck2);
            // create a multipart upload
            final String key1expected1 = s3Client.createMultipartUpload(key1, "STANDARD", "plain/text");
            final String key1expected2 = s3Client.createMultipartUpload(key1, "STANDARD", "plain/text");
            final String key2expected1 = s3Client.createMultipartUpload(key2, "STANDARD", "plain/text");
            final String key2expected2 = s3Client.createMultipartUpload(key2, "STANDARD", "plain/text");
            // Assert
            // we need to filter the map by key because MinIO client is reused
            final Map<String, List<String>> actualBeforeAbort = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key1) || e.getKey().equals(key2))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(actualBeforeAbort).isNotEmpty().hasSize(2).containsKeys(key1, key2);
            assertThat(actualBeforeAbort.get(key1))
                    .isNotEmpty()
                    .hasSize(2)
                    .containsExactly(key1expected1, key1expected2);
            assertThat(actualBeforeAbort.get(key2))
                    .isNotEmpty()
                    .hasSize(2)
                    .containsExactly(key2expected1, key2expected2);

            // Abort one multipart upload
            s3Client.abortMultipartUpload(key1, key1expected1);
            // Assert that the upload is removed,
            // we need to filter the map by key because the MinIO client is reused
            final Map<String, List<String>> actual = s3Client.listMultipartUploads().entrySet().stream()
                    .filter(e -> e.getKey().equals(key1) || e.getKey().equals(key2))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            assertThat(actual).isNotEmpty().hasSize(2).containsKeys(key1, key2);
            assertThat(actual.get(key1)).isNotEmpty().hasSize(1).containsExactly(key1expected2);
            assertThat(actual.get(key2)).isNotEmpty().hasSize(2).containsExactly(key2expected1, key2expected2);
        }
    }

    /**
     * This test aims to verify that the {@link S3Client#downloadTextFile(String)}
     * method will return null when trying to download a non-existent object.
     */
    @Test
    @DisplayName("Test fetching a non-existent object")
    void testFetchNonExistentObject() throws Exception {
        try (final S3Client s3Client = client()) {
            assertNull(s3Client.downloadTextFile("non-existent-object.txt"));
        }
    }

    // -----------------------------------------------------------------------
    // Constructor — precondition validation (no network calls required)
    // -----------------------------------------------------------------------

    /**
     * Verifies that the {@link S3Client} constructor throws
     * {@link S3ClientInitializationException} when any required parameter is
     * blank or null. Each parameter is exercised in isolation.
     */
    @Test
    @DisplayName("Constructor throws S3ClientInitializationException for blank or null parameters")
    void testConstructorRejectsBlankOrNullParameters() {
        assertThatThrownBy(() -> new S3Client(null, endpoint, BUCKET_NAME, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client("", endpoint, BUCKET_NAME, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, null, BUCKET_NAME, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, "", BUCKET_NAME, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, endpoint, null, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, endpoint, "", MINIO_ROOT_USER, MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, endpoint, BUCKET_NAME, null, MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, endpoint, BUCKET_NAME, "", MINIO_ROOT_PASSWORD))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, endpoint, BUCKET_NAME, MINIO_ROOT_USER, null))
                .isInstanceOf(S3ClientInitializationException.class);
        assertThatThrownBy(() -> new S3Client(REGION_NAME, endpoint, BUCKET_NAME, MINIO_ROOT_USER, ""))
                .isInstanceOf(S3ClientInitializationException.class);
    }

    // -----------------------------------------------------------------------
    // listObjects — input validation (no network calls required)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#listObjects(String, int)} throws
     * {@link IllegalArgumentException} when {@code maxResults} is outside the
     * allowed range of [1, 1000].
     */
    @Test
    @DisplayName("listObjects() throws IllegalArgumentException for maxResults out of range [1, 1000]")
    void testListObjectsRejectsOutOfRangeMaxResults() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.listObjects("prefix", 0)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.listObjects("prefix", 1001)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // uploadTextFile / downloadTextFile / abortMultipartUpload — input validation
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#uploadTextFile(String, String, String)}
     * throws {@link IllegalArgumentException} for each blank parameter.
     */
    @Test
    @DisplayName("uploadTextFile() throws IllegalArgumentException for blank parameters")
    void testUploadTextFileRejectsBlankParameters() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.uploadTextFile("", "STANDARD", "content"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.uploadTextFile("  ", "STANDARD", "content"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.uploadTextFile("key", "", "content"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.uploadTextFile("key", "STANDARD", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Verifies that {@link S3Client#downloadTextFile(String)} throws
     * {@link IllegalArgumentException} for a blank key.
     */
    @Test
    @DisplayName("downloadTextFile() throws IllegalArgumentException for a blank key")
    void testDownloadTextFileRejectsBlankKey() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.downloadTextFile("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.downloadTextFile("   ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Verifies that {@link S3Client#abortMultipartUpload(String, String)}
     * throws {@link IllegalArgumentException} for a blank key or upload ID.
     */
    @Test
    @DisplayName("abortMultipartUpload() throws IllegalArgumentException for blank key or uploadId")
    void testAbortMultipartUploadRejectsBlankParameters() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.abortMultipartUpload("", "some-upload-id"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.abortMultipartUpload("some-key", ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // removeObject — input validation
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#deleteObject(String)} throws
     * {@link IllegalArgumentException} for a blank key.
     */
    @Test
    @DisplayName("removeObject() throws IllegalArgumentException for a blank key")
    void testDeleteObjectRejectsBlankKey() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.deleteObject("")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.deleteObject("   ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // removeObject — integration
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#deleteObject(String)} successfully removes
     * an existing object so that it is no longer present in the bucket.
     */
    @Test
    @DisplayName("removeObject() removes an existing object from the bucket")
    void testDeleteObjectDeletesExistingObject() throws Exception {
        final String key = "testRemoveObjectExisting.txt";
        final String content = "to be removed";
        minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                        new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                .build());
        try (final S3Client s3Client = client()) {
            assertDoesNotThrow(() -> s3Client.deleteObject(key));
        }
        final boolean stillExists = minioClient
                .listObjects(ListObjectsArgs.builder()
                        .bucket(BUCKET_NAME)
                        .prefix(key)
                        .maxKeys(1)
                        .build())
                .iterator()
                .hasNext();
        assertThat(stillExists).isFalse();
    }

    /**
     * Verifies that {@link S3Client#deleteObject(String)} does not throw when
     * called with a key that does not exist in the bucket (S3 DELETE is idempotent).
     */
    @Test
    @DisplayName("removeObject() does not throw for a non-existent key")
    void testDeleteObjectOnNonExistentKeyDoesNotThrow() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertDoesNotThrow(() -> s3Client.deleteObject("non-existent-object-to-remove.txt"));
        }
    }

    // -----------------------------------------------------------------------
    // listObjects — boundary and integration
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#listObjects(String, int)} respects
     * {@code maxResults=1} (the lower boundary of the allowed range) and
     * returns at most one result even when more objects exist.
     */
    @Test
    @DisplayName("listObjects() with maxResults=1 returns exactly one result")
    void testListObjectsWithMaxResultsOne() throws Exception {
        final String keyPrefix = "boundary-one-";
        final String content = "boundary test";
        // Upload two objects with the same prefix
        for (int i = 0; i < 2; i++) {
            final String key = keyPrefix + i + ".txt";
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            final List<String> actual = s3Client.listObjects(keyPrefix, 1);
            assertThat(actual).hasSize(1);
        }
    }

    /**
     * Verifies that {@link S3Client#listObjects(String, int)} with an empty
     * prefix returns all objects in the bucket.
     */
    @Test
    @DisplayName("listObjects() with empty prefix returns all objects in the bucket")
    void testListObjectsWithEmptyPrefixReturnsAllObjects() throws Exception {
        final String keyPrefix = "empty-prefix-test-";
        final String content = "empty prefix content";
        final List<String> uploaded = List.of(keyPrefix + "a.txt", keyPrefix + "b.txt");
        for (final String key : uploaded) {
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            final List<String> all = s3Client.listObjects("", 1000);
            assertThat(all).containsAll(uploaded);
        }
    }

    // -----------------------------------------------------------------------
    // Path-style keys (keys containing forward slashes)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#uploadTextFile(String, String, String)}
     * and {@link S3Client#downloadTextFile(String)} work correctly when the
     * object key contains forward slashes, representing a virtual directory
     * path.
     */
    @Test
    @DisplayName("uploadTextFile() and downloadTextFile() handle path-style keys with forward slashes")
    void testUploadAndDownloadTextFileWithPathStyleKey() throws Exception {
        final String key = "folder/subfolder/path-style-file.txt";
        final String expected = "content in a nested path";
        try (final S3Client s3Client = client()) {
            assertDoesNotThrow(() -> s3Client.uploadTextFile(key, "STANDARD", expected));
            final String actual = s3Client.downloadTextFile(key);
            assertThat(actual).isEqualTo(expected);
        }
    }

    // -----------------------------------------------------------------------
    // listParts — input validation
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#listParts(String, String)} throws
     * {@link IllegalArgumentException} for a blank key.
     */
    @Test
    @DisplayName("listParts() throws IllegalArgumentException for a blank key")
    void testListPartsRejectsBlankKey() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.listParts("", "some-upload-id"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.listParts("  ", "some-upload-id"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    /**
     * Verifies that {@link S3Client#listParts(String, String)} throws
     * {@link IllegalArgumentException} for a blank upload ID.
     */
    @Test
    @DisplayName("listParts() throws IllegalArgumentException for a blank uploadId")
    void testListPartsRejectsBlankUploadId() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.listParts("some-key", "")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.listParts("some-key", "  ")).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // listParts — integration
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#listParts(String, String)} returns an empty
     * list when no parts have been uploaded for the in-progress multipart upload.
     */
    @Test
    @DisplayName("listParts() returns an empty list when no parts have been uploaded")
    void testListPartsReturnsEmptyWhenNoPartsUploaded() throws Exception {
        final String key = "testListPartsEmpty.txt";
        try (final S3Client s3Client = client()) {
            final String uploadId = s3Client.createMultipartUpload(key, "STANDARD", "plain/text");
            try {
                final List<S3Client.PartInfo> parts = s3Client.listParts(key, uploadId);
                assertThat(parts).isNotNull().isEmpty();
            } finally {
                s3Client.abortMultipartUpload(key, uploadId);
            }
        }
    }

    /**
     * Verifies that {@link S3Client#listParts(String, String)} returns all
     * uploaded parts and that they are sorted by part number even when uploaded
     * out of order.
     */
    @Test
    @DisplayName("listParts() returns uploaded parts sorted by part number")
    void testListPartsReturnsUploadedPartsSortedByPartNumber() throws Exception {
        final String key = "testListPartsSorted.txt";
        // Part size is only validated at completion; use small data since we abort before completing.
        final byte[] part1Data = "part-one".getBytes(StandardCharsets.UTF_8);
        final byte[] part2Data = "part-two".getBytes(StandardCharsets.UTF_8);
        try (final S3Client s3Client = client()) {
            final String uploadId = s3Client.createMultipartUpload(key, "STANDARD", "plain/text");
            try {
                // Upload in reverse order to confirm the result is sorted by part number.
                s3Client.multipartUploadPart(key, uploadId, 2, part2Data);
                s3Client.multipartUploadPart(key, uploadId, 1, part1Data);
                final List<S3Client.PartInfo> parts = s3Client.listParts(key, uploadId);
                assertThat(parts).hasSize(2);
                assertThat(parts.get(0).partNumber()).isEqualTo(1);
                assertThat(parts.get(0).size()).isEqualTo(part1Data.length);
                assertThat(parts.get(0).etag()).isNotBlank();
                assertThat(parts.get(1).partNumber()).isEqualTo(2);
                assertThat(parts.get(1).size()).isEqualTo(part2Data.length);
                assertThat(parts.get(1).etag()).isNotBlank();
            } finally {
                s3Client.abortMultipartUpload(key, uploadId);
            }
        }
    }

    // -----------------------------------------------------------------------
    // uploadPartCopy — input validation
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#uploadPartCopy} throws
     * {@link IllegalArgumentException} for a blank source key, dest key, or upload ID.
     */
    @Test
    @DisplayName("uploadPartCopy() throws IllegalArgumentException for blank parameters")
    void testUploadPartCopyRejectsBlankParameters() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.uploadPartCopy("", 0, 9, "destKey", "upload-id", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.uploadPartCopy("srcKey", 0, 9, "", "upload-id", 1))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.uploadPartCopy("srcKey", 0, 9, "destKey", "", 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // uploadPartCopy — integration
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#uploadPartCopy} performs a server-side byte-range
     * copy from an existing object into a multipart upload part, and that the resulting
     * completed object contains exactly the copied bytes.
     */
    @Test
    @DisplayName("uploadPartCopy() copies a byte range from an existing object into a new object")
    void testUploadPartCopyCopiesRangeIntoNewObject() throws Exception {
        final String sourceKey = "testUploadPartCopySource.txt";
        final String destKey = "testUploadPartCopyDest.txt";
        final byte[] sourceContent = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(sourceKey).stream(
                        new ByteArrayInputStream(sourceContent), sourceContent.length, -1)
                .build());
        try (final S3Client s3Client = client()) {
            // Copy bytes 5–9 inclusive ("FGHIJ") as the only part of a new multipart upload.
            final String uploadId = s3Client.createMultipartUpload(destKey, "STANDARD", "plain/text");
            final String etag = s3Client.uploadPartCopy(sourceKey, 5, 9, destKey, uploadId, 1);
            assertThat(etag).isNotBlank();
            s3Client.completeMultipartUpload(destKey, uploadId, List.of(etag));
        }
        final byte[] actual = minioClient
                .getObject(GetObjectArgs.builder()
                        .bucket(BUCKET_NAME)
                        .object(destKey)
                        .build())
                .readAllBytes();
        assertThat(actual).isEqualTo("FGHIJ".getBytes(StandardCharsets.UTF_8));
    }

    // -----------------------------------------------------------------------
    // downloadObjectRange — input validation
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#downloadObjectRange(String, long, long)} throws
     * {@link IllegalArgumentException} for a blank key.
     */
    @Test
    @DisplayName("downloadObjectRange() throws IllegalArgumentException for a blank key")
    void testDownloadObjectRangeRejectsBlankKey() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.downloadObjectRange("", 0, 9))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> s3Client.downloadObjectRange("  ", 0, 9))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // -----------------------------------------------------------------------
    // downloadObjectRange — integration
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#downloadObjectRange(String, long, long)} returns
     * the correct byte slice of an existing object.
     */
    @Test
    @DisplayName("downloadObjectRange() returns the expected bytes for a given range")
    void testDownloadObjectRangeReturnsExpectedBytes() throws Exception {
        final String key = "testDownloadObjectRange.txt";
        final byte[] content = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".getBytes(StandardCharsets.UTF_8);
        minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                        new ByteArrayInputStream(content), content.length, -1)
                .build());
        try (final S3Client s3Client = client()) {
            // bytes 2–6 inclusive = 'C','D','E','F','G'
            final byte[] actual = s3Client.downloadObjectRange(key, 2, 6);
            assertThat(actual).isEqualTo("CDEFG".getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Verifies that {@link S3Client#downloadObjectRange(String, long, long)} throws
     * {@link S3ResponseException} when the requested object does not exist.
     */
    @Test
    @DisplayName("downloadObjectRange() throws S3ResponseException for a non-existent object")
    void testDownloadObjectRangeThrowsForNonExistentObject() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.downloadObjectRange("non-existent-range-object.txt", 0, 9))
                    .isInstanceOf(S3ResponseException.class);
        }
    }

    // -----------------------------------------------------------------------
    // listObjectsPage — input validation (no network calls required)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#listObjectsPage(String, String, String, int)} throws
     * {@link NullPointerException} when {@code prefix} is {@code null}.
     */
    @Test
    @DisplayName("listObjectsPage() throws NullPointerException for a null prefix")
    void testListObjectsPageRejectsNullPrefix() throws S3ClientInitializationException {
        try (final S3Client s3Client = client()) {
            assertThatThrownBy(() -> s3Client.listObjectsPage(null, null, null, 10))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -----------------------------------------------------------------------
    // listObjectsPage — integration
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#listObjectsPage(String, String, String, int)} returns
     * an empty key list and a {@code null} continuation token when no objects
     * match the given prefix.
     */
    @Test
    @DisplayName("listObjectsPage() returns an empty page when no objects match the prefix")
    void testListObjectsPageReturnsEmptyPageForNonExistentPrefix() throws Exception {
        try (final S3Client s3Client = client()) {
            final S3Client.ListPage page = s3Client.listObjectsPage("no-such-prefix-xyz-", null, null, 100);
            assertThat(page).isNotNull();
            assertThat(page.keys()).isNotNull().isEmpty();
            assertThat(page.continuationToken()).isNull();
        }
    }

    /**
     * Verifies that {@link S3Client#listObjectsPage(String, String, String, int)} returns
     * all matching keys and a {@code null} continuation token when the total
     * number of objects fits within a single page.
     */
    @Test
    @DisplayName("listObjectsPage() returns all keys and no continuation token when results fit on one page")
    void testListObjectsPageReturnsSinglePageWithNoContinuationToken() throws Exception {
        final String keyPrefix = "page-single-";
        final String content = "single page content";
        final List<String> expected = List.of(keyPrefix + "a.txt", keyPrefix + "b.txt", keyPrefix + "c.txt");
        for (final String key : expected) {
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            final S3Client.ListPage page = s3Client.listObjectsPage(keyPrefix, null, null, 100);
            assertThat(page.keys()).containsExactlyInAnyOrderElementsOf(expected);
            assertThat(page.continuationToken()).isNull();
        }
    }

    /**
     * Verifies that {@link S3Client#listObjectsPage(String, String, String, int)} returns
     * a non-null continuation token when {@code maxResults} is smaller than the
     * total number of matching objects, indicating more pages are available.
     */
    @Test
    @DisplayName("listObjectsPage() returns a continuation token when there are more results beyond the page")
    void testListObjectsPageReturnsContinuationTokenWhenMoreResultsExist() throws Exception {
        final String keyPrefix = "page-overflow-";
        final String content = "overflow content";
        final List<String> uploaded = List.of(keyPrefix + "1.txt", keyPrefix + "2.txt", keyPrefix + "3.txt");
        for (final String key : uploaded) {
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            // Request fewer results than the number of uploaded objects.
            final S3Client.ListPage page = s3Client.listObjectsPage(keyPrefix, null, null, 1);
            assertThat(page.keys()).hasSize(1);
            assertThat(page.continuationToken()).isNotNull().isNotBlank();
        }
    }

    /**
     * Verifies that passing a continuation token from a previous call to
     * {@link S3Client#listObjectsPage(String, String, String, int)} returns the next page
     * of results, and that all pages together contain every uploaded object exactly once.
     */
    @Test
    @DisplayName("listObjectsPage() paginates correctly using continuation tokens")
    void testListObjectsPagePaginationReturnsAllObjectsAcrossPages() throws Exception {
        final String keyPrefix = "page-paginate-";
        final String content = "paginate content";
        final List<String> uploaded = List.of(
                keyPrefix + "1.txt",
                keyPrefix + "2.txt",
                keyPrefix + "3.txt",
                keyPrefix + "4.txt",
                keyPrefix + "5.txt");
        for (final String key : uploaded) {
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            final List<String> accumulated = new ArrayList<>();
            String token = null;
            do {
                final S3Client.ListPage page = s3Client.listObjectsPage(keyPrefix, token, null, 2);
                assertThat(page.keys()).isNotEmpty();
                accumulated.addAll(page.keys());
                token = page.continuationToken();
            } while (token != null);
            assertThat(accumulated).containsExactlyInAnyOrderElementsOf(uploaded);
        }
    }

    // -----------------------------------------------------------------------
    // listObjectsPage — delimiter tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link S3Client#listObjectsPage(String, String, String, int)} with a
     * delimiter returns common prefixes (virtual directories) instead of individual keys.
     * Objects under {@code dir1/} and {@code dir2/} should be grouped as two common prefixes.
     */
    @Test
    @DisplayName("listObjectsPage() with delimiter returns common prefixes instead of flat keys")
    void testListObjectsPageWithDelimiterReturnsCommonPrefixes() throws Exception {
        final String keyPrefix = "delim-dirs-";
        final String content = "delimiter content";
        // Upload objects spread across two virtual directories.
        final List<String> uploaded =
                List.of(keyPrefix + "dir1/file-a.txt", keyPrefix + "dir1/file-b.txt", keyPrefix + "dir2/file-c.txt");
        for (final String key : uploaded) {
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            final S3Client.ListPage page = s3Client.listObjectsPage(keyPrefix, null, "/", 100);
            // Should return the two common prefixes, not the individual file keys.
            assertThat(page.keys()).containsExactlyInAnyOrder(keyPrefix + "dir1/", keyPrefix + "dir2/");
            assertThat(page.continuationToken()).isNull();
        }
    }

    /**
     * Verifies that {@link S3Client#listObjectsPage(String, String, String, int)} with a
     * delimiter returns an empty list when no objects match the given prefix.
     */
    @Test
    @DisplayName("listObjectsPage() with delimiter returns empty page for non-existent prefix")
    void testListObjectsPageWithDelimiterReturnsEmptyPageForNonExistentPrefix() throws Exception {
        try (final S3Client s3Client = client()) {
            final S3Client.ListPage page = s3Client.listObjectsPage("no-such-delim-prefix-xyz-", null, "/", 100);
            assertThat(page.keys()).isNotNull().isEmpty();
            assertThat(page.continuationToken()).isNull();
        }
    }

    /**
     * Verifies that {@link S3Client#listObjectsPage(String, String, String, int)} with a
     * delimiter correctly pages through common prefixes when there are more than
     * {@code maxResults} virtual directories.
     */
    @Test
    @DisplayName("listObjectsPage() with delimiter paginates common prefixes correctly")
    void testListObjectsPageWithDelimiterPaginatesCommonPrefixes() throws Exception {
        final String keyPrefix = "delim-page-";
        final String content = "delimiter page content";
        // Create three virtual directories, each with one object.
        final List<String> uploaded =
                List.of(keyPrefix + "alpha/file.txt", keyPrefix + "beta/file.txt", keyPrefix + "gamma/file.txt");
        for (final String key : uploaded) {
            minioClient.putObject(PutObjectArgs.builder().bucket(BUCKET_NAME).object(key).stream(
                            new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), content.length(), -1)
                    .build());
        }
        try (final S3Client s3Client = client()) {
            final List<String> accumulated = new ArrayList<>();
            String token = null;
            do {
                final S3Client.ListPage page = s3Client.listObjectsPage(keyPrefix, token, "/", 1);
                assertThat(page.keys()).hasSize(1);
                accumulated.addAll(page.keys());
                token = page.continuationToken();
            } while (token != null);
            assertThat(accumulated)
                    .containsExactlyInAnyOrder(keyPrefix + "alpha/", keyPrefix + "beta/", keyPrefix + "gamma/");
        }
    }

    /**
     * This method will create a new instance of the {@link S3Client} to test.
     */
    private S3Client client() throws S3ClientInitializationException {
        return new S3Client(REGION_NAME, endpoint, BUCKET_NAME, MINIO_ROOT_USER, MINIO_ROOT_PASSWORD);
    }
}
