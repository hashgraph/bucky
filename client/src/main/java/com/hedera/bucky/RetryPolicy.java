// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky;

/**
 * Configures the retry behaviour of {@link S3Client}.
 *
 * <p>Use {@link #DEFAULT} for production workloads (3 retries, exponential back-off with full
 * jitter, 60 s total budget) or {@link #DISABLED} for tests and one-shot tooling where a single
 * attempt is sufficient.
 *
 * @param maxAttempts      total number of attempts including the first (so {@code maxAttempts - 1} retries); must be &ge; 1
 * @param baseDelayMs      initial back-off seed in milliseconds; doubles on each successive retry before jitter; must be &ge; 0
 * @param maxDelayMs       upper cap on computed back-off in milliseconds; must be &ge; 0
 * @param totalTimeoutMs   wall-clock budget in milliseconds for the entire operation including all retries; must be &gt; 0
 * @param requestTimeoutMs per-HTTP-request read timeout in milliseconds; {@code 0} means no per-request timeout
 */
public record RetryPolicy(
        int maxAttempts, long baseDelayMs, long maxDelayMs, long totalTimeoutMs, long requestTimeoutMs) {

    /** Single attempt, no retries. */
    public static final RetryPolicy DISABLED = new RetryPolicy(1, 200, 20_000, Long.MAX_VALUE / 1_000_000L, 0);

    /** 3 retries (4 total attempts), exponential back-off with full jitter, 60 s total budget. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(4, 200, 20_000, 60_000, 0);

    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1, got: %d".formatted(maxAttempts));
        }
        if (baseDelayMs < 0) {
            throw new IllegalArgumentException("baseDelayMs must be >= 0, got: %d".formatted(baseDelayMs));
        }
        if (maxDelayMs < 0) {
            throw new IllegalArgumentException("maxDelayMs must be >= 0, got: %d".formatted(maxDelayMs));
        }
        if (totalTimeoutMs <= 0 || totalTimeoutMs > Long.MAX_VALUE / 1_000_000L) {
            throw new IllegalArgumentException("totalTimeoutMs must be > 0 and <= %d, got: %d"
                    .formatted(Long.MAX_VALUE / 1_000_000L, totalTimeoutMs));
        }
        if (requestTimeoutMs < 0) {
            throw new IllegalArgumentException("requestTimeoutMs must be >= 0, got: %d".formatted(requestTimeoutMs));
        }
    }
}
