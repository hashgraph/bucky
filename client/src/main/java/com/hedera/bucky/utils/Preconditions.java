// SPDX-License-Identifier: Apache-2.0
package com.hedera.bucky.utils;

/** A utility class used to assert various preconditions. */
public final class Preconditions {
    private static final String DEFAULT_NOT_BLANK_MESSAGE = "The input String is required to be non-blank.";
    private static final String DEFAULT_REQUIRE_IN_RANGE_MESSAGE =
            "The input number [%d] is required to be in the range [%d, %d] boundaries included.";

    /**
     * This method asserts a given {@link String} is not blank.
     * A blank {@link String} is one that is either {@code null} or contains
     * only whitespaces as defined by {@link String#isBlank()}. If the given
     * {@link String} is not blank, then we return it, else we throw an
     * {@link IllegalArgumentException}.
     *
     * @param toCheck a {@link String} to be checked if is blank as defined above
     * @return the {@link String} to be checked if it is not blank as defined above
     * @throws IllegalArgumentException if the input {@link String} to be
     * checked is blank
     */
    public static String requireNotBlank(final String toCheck) {
        if (StringUtilities.isBlank(toCheck)) {
            throw new IllegalArgumentException(DEFAULT_NOT_BLANK_MESSAGE);
        } else {
            return toCheck;
        }
    }

    /**
     * This method asserts a given int is within a range (boundaries included).
     * If the given int is within the range, then we return it, else, an
     * {@link IllegalArgumentException} is thrown.
     *
     * @param toCheck the int value to test
     * @param lowerBoundary the lower boundary
     * @param upperBoundary the upper boundary
     * @throws IllegalArgumentException if the input int does not pass the test
     */
    public static void requireInRange(final int toCheck, final int lowerBoundary, final int upperBoundary) {
        if (toCheck < lowerBoundary || toCheck > upperBoundary) {
            throw new IllegalArgumentException(
                    DEFAULT_REQUIRE_IN_RANGE_MESSAGE.formatted(toCheck, lowerBoundary, upperBoundary));
        }
    }

    private Preconditions() {}
}
