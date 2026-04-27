// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RetryPolicyTest {

    @Test
    void disabledHasSingleAttempt() {
        assertThat(RetryPolicy.DISABLED.maxAttempts()).isEqualTo(1);
    }

    @Test
    void defaultHasFourAttempts() {
        assertThat(RetryPolicy.DEFAULT.maxAttempts()).isEqualTo(4);
    }

    @Test
    void defaultHasExpectedFieldValues() {
        final RetryPolicy p = RetryPolicy.DEFAULT;
        assertThat(p.baseDelayMs()).isEqualTo(200L);
        assertThat(p.maxDelayMs()).isEqualTo(20_000L);
        assertThat(p.totalTimeoutMs()).isEqualTo(60_000L);
        assertThat(p.requestTimeoutMs()).isEqualTo(0L);
    }

    @Test
    void disabledAndDefaultAreDistinctInstances() {
        assertThat(RetryPolicy.DISABLED).isNotSameAs(RetryPolicy.DEFAULT);
    }

    @Test
    void builderProducesCorrectValues() {
        final RetryPolicy p = RetryPolicy.builder()
                .maxAttempts(7)
                .baseDelayMs(100)
                .maxDelayMs(5_000)
                .totalTimeoutMs(30_000)
                .requestTimeoutMs(3_000)
                .build();

        assertThat(p.maxAttempts()).isEqualTo(7);
        assertThat(p.baseDelayMs()).isEqualTo(100L);
        assertThat(p.maxDelayMs()).isEqualTo(5_000L);
        assertThat(p.totalTimeoutMs()).isEqualTo(30_000L);
        assertThat(p.requestTimeoutMs()).isEqualTo(3_000L);
    }

    @Test
    void builderRejectMaxAttemptsLessThanOne() {
        assertThatThrownBy(() -> RetryPolicy.builder().maxAttempts(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxAttempts");
    }

    @Test
    void builderRejectsNegativeBaseDelay() {
        assertThatThrownBy(() -> RetryPolicy.builder().baseDelayMs(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baseDelayMs");
    }

    @Test
    void builderRejectsNegativeMaxDelay() {
        assertThatThrownBy(() -> RetryPolicy.builder().maxDelayMs(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxDelayMs");
    }

    @Test
    void builderRejectsTotalTimeoutZero() {
        assertThatThrownBy(() -> RetryPolicy.builder().totalTimeoutMs(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTimeoutMs");
    }

    @Test
    void builderRejectsTotalTimeoutNegative() {
        assertThatThrownBy(() -> RetryPolicy.builder().totalTimeoutMs(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTimeoutMs");
    }

    @Test
    void builderRejectsNegativeRequestTimeout() {
        assertThatThrownBy(() -> RetryPolicy.builder().requestTimeoutMs(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestTimeoutMs");
    }

    @Test
    void builderAllowsZeroRequestTimeout() {
        final RetryPolicy p = RetryPolicy.builder().requestTimeoutMs(0).build();
        assertThat(p.requestTimeoutMs()).isEqualTo(0L);
    }

    @Test
    void buildTwiceProducesIndependentObjects() {
        final RetryPolicy.Builder builder = RetryPolicy.builder().maxAttempts(3);
        final RetryPolicy p1 = builder.build();
        final RetryPolicy p2 = builder.maxAttempts(5).build();
        assertThat(p1.maxAttempts()).isEqualTo(3);
        assertThat(p2.maxAttempts()).isEqualTo(5);
    }
}
