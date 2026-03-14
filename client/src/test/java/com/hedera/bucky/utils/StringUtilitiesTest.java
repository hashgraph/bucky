// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky.utils;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StringUtilities}.
 */
class StringUtilitiesTest {

    @Test
    @DisplayName("EMPTY constant equals an empty string")
    void emptyConstantIsEmptyString() {
        assertThat(StringUtilities.EMPTY).isEqualTo("").isEmpty();
    }

    @Test
    @DisplayName("isBlank returns true for null, empty, and whitespace-only strings")
    void isBlankReturnsTrueForBlankInputs() {
        assertThat(StringUtilities.isBlank(null)).isTrue();
        assertThat(StringUtilities.isBlank("")).isTrue();
        assertThat(StringUtilities.isBlank("   ")).isTrue();
        assertThat(StringUtilities.isBlank("\t")).isTrue();
        assertThat(StringUtilities.isBlank("\n")).isTrue();
        assertThat(StringUtilities.isBlank("  \t  \n  ")).isTrue();
    }

    @Test
    @DisplayName("isBlank returns false for strings containing non-whitespace content")
    void isBlankReturnsFalseForNonBlankStrings() {
        assertThat(StringUtilities.isBlank("a")).isFalse();
        assertThat(StringUtilities.isBlank("hello")).isFalse();
        assertThat(StringUtilities.isBlank("  hello  ")).isFalse();
        assertThat(StringUtilities.isBlank("hello world")).isFalse();
    }
}
