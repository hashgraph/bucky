// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Preconditions}.
 */
class PreconditionsTest {

    // -----------------------------------------------------------------------
    // requireNotBlank
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("requireNotBlank throws IllegalArgumentException for null, empty, and whitespace-only strings")
    void requireNotBlankThrowsForBlankInputs() {
        assertThatThrownBy(() -> Preconditions.requireNotBlank(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Preconditions.requireNotBlank(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Preconditions.requireNotBlank("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Preconditions.requireNotBlank("\t"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("requireNotBlank returns the original string when it is not blank")
    void requireNotBlankReturnsInputForValidStrings() {
        assertThat(Preconditions.requireNotBlank("hello")).isEqualTo("hello");
        assertThat(Preconditions.requireNotBlank("  hello  ")).isEqualTo("  hello  ");
        assertThat(Preconditions.requireNotBlank("a")).isEqualTo("a");
    }

    // -----------------------------------------------------------------------
    // requireInRange
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("requireInRange accepts values at and within the boundaries (inclusive)")
    void requireInRangeAcceptsValuesInRange() {
        // boundaries
        Preconditions.requireInRange(1, 1, 10);
        Preconditions.requireInRange(10, 1, 10);
        // mid-range
        Preconditions.requireInRange(5, 1, 10);
    }

    @Test
    @DisplayName("requireInRange throws IllegalArgumentException for values outside the range")
    void requireInRangeThrowsForOutOfRangeValues() {
        assertThatThrownBy(() -> Preconditions.requireInRange(0, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0")
                .hasMessageContaining("1")
                .hasMessageContaining("10");
        assertThatThrownBy(() -> Preconditions.requireInRange(11, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("11");
    }

    @Test
    @DisplayName("requireInRange exception message includes the checked value and both boundaries")
    void requireInRangeExceptionMessageContainsAllValues() {
        assertThatThrownBy(() -> Preconditions.requireInRange(42, 1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("42")
                .hasMessageContaining("1")
                .hasMessageContaining("10");
    }
}
