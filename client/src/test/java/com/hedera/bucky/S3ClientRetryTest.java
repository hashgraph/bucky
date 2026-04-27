// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the retry mechanism using an embedded {@link HttpServer} that serves controlled responses.
 * No MinIO container is required — the server is spun up and torn down per test.
 */
class S3ClientRetryTest {

    private static final String REGION = "us-east-1";
    private static final String BUCKET = "test-bucket";
    private static final String ACCESS_KEY = "AKIAJ7R2W9V4X6N1P3L8";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";

    /** Minimal valid S3 XML list-objects response body. */
    private static final String LIST_200_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
            + "<Name>test-bucket</Name><Prefix></Prefix><KeyCount>0</KeyCount>"
            + "<MaxKeys>10</MaxKeys><IsTruncated>false</IsTruncated>"
            + "</ListBucketResult>";

    private static final String ERROR_XML_TEMPLATE =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + "<Error><Code>%s</Code><Message>test</Message></Error>";

    private HttpServer httpServer;
    private String serverUrl;

    @BeforeEach
    void startServer() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        httpServer.setExecutor(Executors.newCachedThreadPool());
        httpServer.start();
        serverUrl = "http://localhost:" + httpServer.getAddress().getPort() + "/";
    }

    @AfterEach
    void stopServer() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
    }

    private S3Client clientWith(final RetryPolicy policy) throws S3ClientInitializationException {
        return new S3Client(REGION, serverUrl, BUCKET, ACCESS_KEY, SECRET_KEY, policy);
    }

    /** Registers a handler that returns {@code errorStatus} for the first {@code errorCount}
     *  requests, then returns 200 with a valid list response. Returns the invocation counter. */
    private AtomicInteger registerCountingHandler(final int errorStatus, final int errorCount, final byte[] errorBody) {
        final AtomicInteger counter = new AtomicInteger(0);
        httpServer.createContext("/", exchange -> {
            final int call = counter.incrementAndGet();
            if (call <= errorCount) {
                sendResponse(exchange, errorStatus, errorBody);
            } else {
                sendResponse(exchange, 200, LIST_200_BODY.getBytes(StandardCharsets.UTF_8));
            }
        });
        return counter;
    }

    private AtomicInteger registerAlwaysErrorHandler(final int status, final byte[] body) {
        final AtomicInteger counter = new AtomicInteger(0);
        httpServer.createContext("/", exchange -> {
            counter.incrementAndGet();
            sendResponse(exchange, status, body);
        });
        return counter;
    }

    private static void sendResponse(final HttpExchange exchange, final int status, final byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (final OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private static byte[] xmlError(final String code) {
        return ERROR_XML_TEMPLATE.formatted(code).getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------

    @Test
    void noRetryOnSuccess() throws Exception {
        final AtomicInteger counter = new AtomicInteger(0);
        httpServer.createContext("/", exchange -> {
            counter.incrementAndGet();
            sendResponse(exchange, 200, LIST_200_BODY.getBytes(StandardCharsets.UTF_8));
        });

        try (final S3Client client = clientWith(RetryPolicy.DEFAULT)) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void retriesOn503ThenSucceeds() throws Exception {
        final AtomicInteger counter = registerCountingHandler(503, 2, xmlError("SlowDown"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(4)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void retriesOn429ThenSucceeds() throws Exception {
        final AtomicInteger counter = registerCountingHandler(429, 1, xmlError("TooManyRequests"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void retriesOn408ThenSucceeds() throws Exception {
        final AtomicInteger counter = registerCountingHandler(408, 1, xmlError("RequestTimeout"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void retriesOn409ThenSucceeds() throws Exception {
        final AtomicInteger counter = registerCountingHandler(409, 1, xmlError("OperationAborted"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void retriesOn500ThenSucceeds() throws Exception {
        final AtomicInteger counter = registerCountingHandler(500, 1, xmlError("InternalError"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void noRetryOn403() throws Exception {
        final AtomicInteger counter = registerAlwaysErrorHandler(403, xmlError("AccessDenied"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(4)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            assertThatThrownBy(() -> client.listObjects("", 10))
                    .isInstanceOf(S3ResponseException.class)
                    .satisfies(e -> assertThat(((S3ResponseException) e).getResponseStatusCode())
                            .isEqualTo(403));
        }

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void noRetryOn404ForDownloadTextFile() throws Exception {
        final AtomicInteger counter = registerAlwaysErrorHandler(404, new byte[0]);

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(4)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            final String result = client.downloadTextFile("non-existent-key");
            assertThat(result).isNull();
        }

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void retriesOn400WithRequestTimeTooSkewed() throws Exception {
        final AtomicInteger counter = registerCountingHandler(400, 1, xmlError("RequestTimeTooSkewed"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void retriesOn400WithExpiredToken() throws Exception {
        final AtomicInteger counter = registerCountingHandler(400, 1, xmlError("ExpiredToken"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void retriesOn400WithBadDigest() throws Exception {
        final AtomicInteger counter = registerCountingHandler(400, 1, xmlError("BadDigest"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void retriesOn400WithRequestTimeout() throws Exception {
        final AtomicInteger counter = registerCountingHandler(400, 1, xmlError("RequestTimeout"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void noRetryOn400WithUnknownErrorCode() throws Exception {
        final AtomicInteger counter = registerAlwaysErrorHandler(400, xmlError("InvalidBucketName"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(4)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            assertThatThrownBy(() -> client.listObjects("", 10))
                    .isInstanceOf(S3ResponseException.class)
                    .satisfies(e -> assertThat(((S3ResponseException) e).getResponseStatusCode())
                            .isEqualTo(400));
        }

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void noRetryOn400WithNonXmlBody() throws Exception {
        final AtomicInteger counter =
                registerAlwaysErrorHandler(400, "plain text error".getBytes(StandardCharsets.UTF_8));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(4)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            assertThatThrownBy(() -> client.listObjects("", 10))
                    .isInstanceOf(S3ResponseException.class)
                    .satisfies(e -> assertThat(((S3ResponseException) e).getResponseStatusCode())
                            .isEqualTo(400));
        }

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void exhaustsMaxAttemptsAndThrows() throws Exception {
        final AtomicInteger counter = registerAlwaysErrorHandler(503, xmlError("SlowDown"));

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            assertThatThrownBy(() -> client.listObjects("", 10))
                    .isInstanceOf(S3ResponseException.class)
                    .satisfies(e -> assertThat(((S3ResponseException) e).getResponseStatusCode())
                            .isEqualTo(503));
        }

        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void disabledPolicyMakesNoRetry() throws Exception {
        final AtomicInteger counter = registerCountingHandler(503, 1, xmlError("SlowDown"));

        try (final S3Client client = clientWith(RetryPolicy.DISABLED)) {
            assertThatThrownBy(() -> client.listObjects("", 10))
                    .isInstanceOf(S3ResponseException.class)
                    .satisfies(e -> assertThat(((S3ResponseException) e).getResponseStatusCode())
                            .isEqualTo(503));
        }

        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void totalTimeoutCausesEarlyAbort() throws Exception {
        // Server always responds with 503 after a 60 ms delay — longer than total timeout
        final AtomicInteger counter = new AtomicInteger(0);
        httpServer.createContext("/", (HttpHandler) exchange -> {
            counter.incrementAndGet();
            try {
                Thread.sleep(60);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sendResponse(exchange, 503, xmlError("SlowDown"));
        });

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(20)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(200) // only 200 ms budget
                .build())) {
            assertThatThrownBy(() -> client.listObjects("", 10)).isInstanceOf(S3ResponseException.class);
        }

        // Should have stopped well before 20 attempts due to the tight total timeout
        assertThat(counter.get()).isLessThan(10);
    }

    @Test
    void perRequestTimeoutCausesRetriableError() throws Exception {
        // Server hangs for 500 ms — longer than requestTimeoutMs
        final AtomicInteger counter = new AtomicInteger(0);
        httpServer.createContext("/", (HttpHandler) exchange -> {
            counter.incrementAndGet();
            try {
                Thread.sleep(500);
            } catch (final InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            sendResponse(exchange, 200, LIST_200_BODY.getBytes(StandardCharsets.UTF_8));
        });

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(2)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(5_000)
                .requestTimeoutMs(100) // 100 ms per request — will time out
                .build())) {
            // Both attempts will time out; expect the HttpTimeoutException to propagate as IOException
            assertThatThrownBy(() -> client.listObjects("", 10)).isInstanceOf(HttpTimeoutException.class);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void retryLogsAmzRequestId() throws Exception {
        // Server returns 503 with x-amz-request-id header, then 200
        final AtomicInteger counter = new AtomicInteger(0);
        httpServer.createContext("/", exchange -> {
            final int call = counter.incrementAndGet();
            if (call == 1) {
                exchange.getResponseHeaders().add("x-amz-request-id", "test-req-id-123");
                exchange.getResponseHeaders().add("x-amz-id-2", "test-ext-id-456");
                sendResponse(exchange, 503, xmlError("SlowDown"));
            } else {
                sendResponse(exchange, 200, LIST_200_BODY.getBytes(StandardCharsets.UTF_8));
            }
        });

        // We just verify it doesn't throw and that 2 attempts were made (i.e. header was captured,
        // retry happened). Full log-output capture would require a custom LoggerFinder — out of scope.
        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            client.listObjects("", 10);
        }

        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    void downloadObjectRangeRetriesOn400WithRetriableXmlCode() throws Exception {
        // Verifies that downloadObjectRange passes the response body to S3ResponseException
        // so that content-based 400 retry classification works (it uses ofByteArray(), not ofInputStream()).
        final AtomicInteger counter = new AtomicInteger(0);
        httpServer.createContext("/", exchange -> {
            final int call = counter.incrementAndGet();
            if (call == 1) {
                sendResponse(exchange, 400, xmlError("RequestTimeTooSkewed"));
            } else {
                final byte[] data = new byte[] {1, 2, 3, 4, 5};
                exchange.getResponseHeaders().add("Content-Range", "bytes 0-4/5");
                sendResponse(exchange, 206, data);
            }
        });

        try (final S3Client client = clientWith(RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelayMs(1)
                .maxDelayMs(5)
                .totalTimeoutMs(10_000)
                .build())) {
            final byte[] result = client.downloadObjectRange("some-key", 0, 4);
            assertThat(result).isEqualTo(new byte[] {1, 2, 3, 4, 5});
        }

        assertThat(counter.get()).isEqualTo(2);
    }
}
