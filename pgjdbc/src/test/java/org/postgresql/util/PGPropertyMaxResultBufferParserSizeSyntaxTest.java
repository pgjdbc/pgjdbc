/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import org.postgresql.test.annotations.DisableLogger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.function.LongSupplier;

/**
 * A size property in the {@code maxResultBuffer} syntax parses to a positive byte count, to
 * {@code -1} when unset, or throws a {@link PSQLException} with SQLState 22023 that names the
 * property and the value.
 *
 * <p>A value that parses to zero or below gets the "must be a positive size" message; a value
 * outside the syntax, including one whose number overflows, gets the "not a valid size" message.
 * The maximum heap comes from a fixed supplier, so no result depends on the test JVM.</p>
 */
class PGPropertyMaxResultBufferParserSizeSyntaxTest {

  /** 10 TB (10000000000000 bytes); 90% of it, the largest result, is 9000000000000. */
  private static final LongSupplier TEN_TB_HEAP = () -> 10_000_000_000_000L;

  private static final LongSupplier HEAP_UNKNOWN = () -> -1L;

  private static final String PROPERTY = "maxServerTextMessageSize";

  @ParameterizedTest
  @CsvSource({
      "1, 1",
      "100, 100",
      "10k, 10000",
      "10K, 10000",
      "25m, 25000000",
      "25M, 25000000",
      "2g, 2000000000",
      "2G, 2000000000",
      "1t, 1000000000000",
      "1T, 1000000000000",
      "25p, 2500000000000",
      "50pct, 5000000000000",
      "75percent, 7500000000000",
      "12.5p, 1250000000000",
  })
  void aPositiveSizeParsesToBytes(String value, long expected) throws PSQLException {
    assertEquals(expected,
        PGPropertyMaxResultBufferParser.parseProperty(PROPERTY, value, TEN_TB_HEAP));
  }

  @ParameterizedTest
  @NullAndEmptySource
  void anUnsetValueParsesToMinusOne(String value) throws PSQLException {
    assertEquals(-1,
        PGPropertyMaxResultBufferParser.parseProperty(PROPERTY, value, TEN_TB_HEAP));
  }

  @Test
  @DisableLogger(PGPropertyMaxResultBufferParser.class)
  void aSizeAboveNinetyPercentOfTheHeapIsLoweredToIt() throws PSQLException {
    assertEquals(9_000_000_000_000L,
        PGPropertyMaxResultBufferParser.parseProperty(PROPERTY, "10T", TEN_TB_HEAP));
  }

  /**
   * 9223372T is the largest terabyte count whose byte value fits in a {@code long};
   * {@link #aValueOutsideTheSizeSyntaxIsRejected(String)} rejects 9999999T. With no known heap
   * the result is not lowered.
   */
  @Test
  void theLargestTerabyteCountThatFitsInALongParses() throws PSQLException {
    assertEquals(9_223_372_000_000_000_000L,
        PGPropertyMaxResultBufferParser.parseProperty(PROPERTY, "9223372T", HEAP_UNKNOWN));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "0K", "0p", "-1", "-1K", "-5p"})
  void aValueThatIsNotPositiveIsRejected(String value) {
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> PGPropertyMaxResultBufferParser.parseProperty(PROPERTY, value, TEN_TB_HEAP));

    assertAll(
        () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState()),
        () -> assertEquals(GT.tr(
            "The {0} connection property must be a positive size, but its value is {1}. Give a byte count such as 150M or a share of the heap such as 10p, or leave the property unset.",
            PROPERTY, value), e.getMessage()));
  }

  /**
   * {@code 10%} was once accepted as no limit; the percent suffixes are {@code p}, {@code pct}
   * and {@code percent}. The last three values overflow an {@code int} before the multiplier,
   * a {@code long} without one, and a {@code long} after multiplying.
   */
  @ParameterizedTest
  @ValueSource(strings = {"abc", "abcM", "abcp", "10x", "10%", "10.5M",
      "2147483648K", "9223372036854775808", "9999999T"})
  void aValueOutsideTheSizeSyntaxIsRejected(String value) {
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> PGPropertyMaxResultBufferParser.parseProperty(PROPERTY, value, TEN_TB_HEAP));

    assertAll(
        () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState()),
        () -> assertEquals(GT.tr(
            "The {0} connection property has the value {1}, which is not a valid size. Give a byte count such as 150M or a share of the heap with the p suffix, such as 10p.",
            PROPERTY, value), e.getMessage()));
  }

  /**
   * The heap size comes from {@code java.lang.management}, which some runtimes lack; the error then
   * names the property whose percent value could not be resolved.
   */
  @ParameterizedTest
  @ValueSource(strings = {"maxResultBuffer", "maxServerTextMessageSize"})
  void aPercentValueWithAnUnknownHeapIsRejectedNamingTheProperty(String propertyName) {
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> PGPropertyMaxResultBufferParser.parseProperty(propertyName, "10p", HEAP_UNKNOWN));

    assertAll(
        () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState()),
        () -> assertEquals(GT.tr(
            "Could not parse {0} value {1}; percent values require {2}.",
            propertyName, "10p", "java.lang.management.ManagementFactory"), e.getMessage()));
  }

  @Test
  void aNumberFormatFailureIsKeptAsTheCause() {
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> PGPropertyMaxResultBufferParser.parseProperty(PROPERTY, "abcM", TEN_TB_HEAP));

    assertInstanceOf(NumberFormatException.class, e.getCause());
  }

  @Test
  void thePublicOverloadNamesTheGivenProperty() {
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> PGPropertyMaxResultBufferParser.parseProperty("maxServerTextMessageSize", "-1"));

    assertEquals(GT.tr(
        "The {0} connection property must be a positive size, but its value is {1}. Give a byte count such as 150M or a share of the heap such as 10p, or leave the property unset.",
        "maxServerTextMessageSize", "-1"), e.getMessage());
  }

  @Test
  void theSingleArgumentOverloadNamesMaxResultBuffer() {
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> PGPropertyMaxResultBufferParser.parseProperty("10x"));

    assertEquals(GT.tr(
        "The {0} connection property has the value {1}, which is not a valid size. Give a byte count such as 150M or a share of the heap with the p suffix, such as 10p.",
        "maxResultBuffer", "10x"), e.getMessage());
  }
}
