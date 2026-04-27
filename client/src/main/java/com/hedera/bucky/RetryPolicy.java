// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky;

/**
 * Configures the retry behaviour of {@link S3Client}.
 *
 * <p>Use {@link #DEFAULT} for production workloads (3 retries, exponential back-off with full
 * jitter, 60 s total budget) or {@link #DISABLED} for tests and one-shot tooling where a single
 * attempt is sufficient.
 *
 * <p>Construct a custom policy via {@link #builder()}:
 * <pre>{@code
 * RetryPolicy policy = RetryPolicy.builder()
 *         .maxAttempts(5)
 *         .baseDelayMs(500)
 *         .totalTimeoutMs(120_000)
 *         .requestTimeoutMs(10_000)
 *         .build();
 * }</pre>
 */
public final class RetryPolicy {

    /** Single attempt, no retries. */
    public static final RetryPolicy DISABLED = new RetryPolicy(1, 200, 20_000, Long.MAX_VALUE / 1_000_000L, 0);

    /** 3 retries (4 total attempts), exponential back-off with full jitter, 60 s total budget. */
    public static final RetryPolicy DEFAULT = new RetryPolicy(4, 200, 20_000, 60_000, 0);

    private final int maxAttempts;
    private final long baseDelayMs;
    private final long maxDelayMs;
    private final long totalTimeoutMs;
    private final long requestTimeoutMs;

    private RetryPolicy(
            final int maxAttempts,
            final long baseDelayMs,
            final long maxDelayMs,
            final long totalTimeoutMs,
            final long requestTimeoutMs) {
        this.maxAttempts = maxAttempts;
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.totalTimeoutMs = totalTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /** Total number of attempts including the first (so {@code maxAttempts - 1} retries). */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** Initial back-off seed in milliseconds; doubles on each successive retry before jitter. */
    public long baseDelayMs() {
        return baseDelayMs;
    }

    /** Upper cap on computed back-off in milliseconds. */
    public long maxDelayMs() {
        return maxDelayMs;
    }

    /** Wall-clock budget in milliseconds for the entire operation including all retries. */
    public long totalTimeoutMs() {
        return totalTimeoutMs;
    }

    /**
     * Per-HTTP-request read timeout in milliseconds.
     * {@code 0} means no per-request timeout (the default).
     */
    public long requestTimeoutMs() {
        return requestTimeoutMs;
    }

    /** Returns a new {@link Builder} initialised with the same defaults as {@link #DEFAULT}. */
    public static Builder builder() {
        return new Builder();
    }

    /** Fluent builder for {@link RetryPolicy}. */
    public static final class Builder {

        private int maxAttempts = 4;
        private long baseDelayMs = 200;
        private long maxDelayMs = 20_000;
        private long totalTimeoutMs = 60_000;
        private long requestTimeoutMs = 0;

        private Builder() {}

        /**
         * Sets the total number of attempts (first attempt + retries).
         *
         * @param v must be &ge; 1
         */
        public Builder maxAttempts(final int v) {
            if (v < 1) {
                throw new IllegalArgumentException("maxAttempts must be >= 1, got: " + v);
            }
            this.maxAttempts = v;
            return this;
        }

        /**
         * Sets the initial back-off seed in milliseconds.
         *
         * @param v must be &ge; 0
         */
        public Builder baseDelayMs(final long v) {
            if (v < 0) {
                throw new IllegalArgumentException("baseDelayMs must be >= 0, got: " + v);
            }
            this.baseDelayMs = v;
            return this;
        }

        /**
         * Sets the upper cap on computed back-off in milliseconds.
         *
         * @param v must be &ge; 0
         */
        public Builder maxDelayMs(final long v) {
            if (v < 0) {
                throw new IllegalArgumentException("maxDelayMs must be >= 0, got: " + v);
            }
            this.maxDelayMs = v;
            return this;
        }

        /**
         * Sets the wall-clock budget for the entire operation in milliseconds.
         *
         * @param v must be &gt; 0
         */
        public Builder totalTimeoutMs(final long v) {
            if (v <= 0 || v > Long.MAX_VALUE / 1_000_000L) {
                throw new IllegalArgumentException(
                        "totalTimeoutMs must be > 0 and <= " + Long.MAX_VALUE / 1_000_000L + ", got: " + v);
            }
            this.totalTimeoutMs = v;
            return this;
        }

        /**
         * Sets the per-request read timeout in milliseconds.
         * Pass {@code 0} (the default) to disable the per-request timeout.
         *
         * @param v must be &ge; 0
         */
        public Builder requestTimeoutMs(final long v) {
            if (v < 0) {
                throw new IllegalArgumentException("requestTimeoutMs must be >= 0, got: " + v);
            }
            this.requestTimeoutMs = v;
            return this;
        }

        /** Builds and returns the configured {@link RetryPolicy}. */
        public RetryPolicy build() {
            return new RetryPolicy(maxAttempts, baseDelayMs, maxDelayMs, totalTimeoutMs, requestTimeoutMs);
        }
    }
}
