/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.postgresql.util.internal.JvmHeapAccess;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses the size syntax of {@code maxResultBuffer}, which some other size-valued connection
 * properties also accept.
 */
public class PGPropertyMaxResultBufferParser {

  private static final Logger LOGGER = Logger.getLogger(PGPropertyMaxResultBufferParser.class.getName());

  /**
   * Used only as a human-readable identifier in {@link PSQLException} messages — keep as a
   * string constant so this class never has a verifier-visible reference to
   * {@code java.lang.management.ManagementFactory}.
   */
  private static final String MANAGEMENT_FACTORY_CLASS_NAME = "java.lang.management.ManagementFactory";

  private static final double MAX_RESULT_BUFFER_HEAP_FRACTION = 0.9;

  private static final String[] PERCENT_PHRASES = new String[]{
    "p",
    "pct",
    "percent"
  };

  /**
   * Parses a {@code maxResultBuffer} value, naming that property in the error.
   *
   * @param value a byte count with an optional multiplier (T, G, M or K), or a percent of the
   *              maximum heap
   * @return the size in bytes, lowered to 90% of the maximum heap when it is larger, or {@code -1}
   *     when {@code value} is unset
   * @throws PSQLException if the value cannot be parsed, or parses to zero or below
   * @see #parseProperty(String, String)
   */
  public static long parseProperty(@Nullable String value) throws PSQLException {
    return parseProperty("maxResultBuffer", value);
  }

  /**
   * Parses a size property in the {@code maxResultBuffer} syntax: a byte count with an optional
   * decimal multiplier ({@code 150M} is 150000000 bytes) or a share of the maximum heap
   * ({@code 10p}).
   *
   * <p>{@code null} and the empty string mean unset and parse to {@code -1}. A value that parses
   * to zero or below throws rather than meaning unset or unlimited, so a mistyped value means the
   * same thing in every property that shares this syntax.
   *
   * @param propertyName connection property the value belongs to, named in the error
   * @param value        the value to parse
   * @return the size in bytes, lowered to 90% of the maximum heap when it is larger, or {@code -1}
   *     when {@code value} is unset
   * @throws PSQLException if the value cannot be parsed, or parses to zero or below
   */
  public static long parseProperty(String propertyName, @Nullable String value)
      throws PSQLException {
    return parseProperty(propertyName, value,
        PGPropertyMaxResultBufferParser::defaultMaxHeapBytesOrNegativeOne);
  }

  static long parseProperty(@Nullable String value, LongSupplier maxHeapBytesSupplier)
      throws PSQLException {
    return parseProperty("maxResultBuffer", value, maxHeapBytesSupplier);
  }

  static long parseProperty(String propertyName, @Nullable String value,
      LongSupplier maxHeapBytesSupplier) throws PSQLException {
    long result = -1;
    //noinspection StatementWithEmptyBody
    if (value == null) {
      // default branch
    } else {
      try {
        if (checkIfValueContainsPercent(value)) {
          result = parseBytePercentValue(propertyName, value, maxHeapBytesSupplier);
        } else if (!value.isEmpty()) {
          result = parseByteValue(propertyName, value);
        }
      } catch (NumberFormatException | ArithmeticException e) {
        throw invalidSize(propertyName, value, e);
      }
    }
    if (value != null && !value.isEmpty() && result <= 0) {
      throw new PSQLException(GT.tr(
          "The {0} connection property must be a positive size, but its value is {1}. Give a byte count such as 150M or a share of the heap such as 10p, or leave the property unset.",
          propertyName, value), PSQLState.INVALID_PARAMETER_VALUE);
    }
    result = adjustResultSize(result, maxHeapBytesSupplier);
    return result;
  }

  /**
   * Method to check if given value can contain percent declaration of size of max result buffer.
   *
   * @param value Value to check.
   * @return Result if value contains percent.
   */
  private static boolean checkIfValueContainsPercent(String value) {
    return getPercentPhraseLengthIfContains(value) != -1;
  }

  /**
   * Method to get percent value of max result buffer size dependable on actual free memory. This
   * method doesn't check other possibilities of value declaration.
   *
   * @param value string containing percent used to define max result buffer.
   * @return percent value of max result buffer size.
   * @throws PSQLException Exception when given value can't be parsed.
   */
  private static long parseBytePercentValue(String propertyName, String value,
      LongSupplier maxHeapBytesSupplier) throws PSQLException {
    long result = -1;
    int length;

    if (!value.isEmpty()) {
      length = getPercentPhraseLengthIfContains(value);

      if (length == -1) {
        throw invalidSize(propertyName, value, null);
      }

      result = calculatePercentOfMemory(propertyName, value, length, maxHeapBytesSupplier);
    }
    return result;
  }

  /**
   * Method to get length of percent phrase existing in given string, only if one of phrases exist
   * on the length of string.
   *
   * @param valueToCheck String which is gonna be checked if contains percent phrase.
   * @return Length of phrase inside string, returns -1 when no phrase found.
   */
  private static int getPercentPhraseLengthIfContains(String valueToCheck) {
    int result = -1;
    for (String phrase : PERCENT_PHRASES) {
      int indx = getPhraseLengthIfContains(valueToCheck, phrase);
      if (indx != -1) {
        result = indx;
      }
    }
    return result;
  }

  /**
   * Method to get length of given phrase in given string to check, method checks if phrase exist on
   * the end of given string.
   *
   * @param valueToCheck String which gonna be checked if contains phrase.
   * @param phrase       Phrase to be looked for on the end of given string.
   * @return Length of phrase inside string, returns -1 when phrase wasn't found.
   */
  private static int getPhraseLengthIfContains(String valueToCheck, String phrase) {
    int searchValueLength = phrase.length();

    if (valueToCheck.length() > searchValueLength) {
      String subValue = valueToCheck.substring(valueToCheck.length() - searchValueLength);
      if (subValue.equals(phrase)) {
        return searchValueLength;
      }
    }
    return -1;
  }

  /**
   * Method to calculate percent of given max heap memory.
   *
   * @param propertyName        connection property the value belongs to, named in the error
   * @param value               String which contains percent + percent phrase which gonna be used
   *                            during calculations.
   * @param percentPhraseLength Length of percent phrase inside given value.
   * @param maxHeapBytesSupplier the maximum heap in bytes, or a negative number when it is unknown
   * @return Size of byte buffer based on percent of max heap memory.
   * @throws PSQLException if the maximum heap is unknown
   */
  private static long calculatePercentOfMemory(String propertyName,
      String value, int percentPhraseLength, LongSupplier maxHeapBytesSupplier)
      throws PSQLException {
    String realValue = value.substring(0, value.length() - percentPhraseLength);
    double percent = Double.parseDouble(realValue) / 100;
    long maxHeapMemory = maxHeapBytesSupplier.getAsLong();
    if (maxHeapMemory < 0) {
      throw new PSQLException(GT.tr(
          "Could not parse {0} value {1}; percent values require {2}.",
          propertyName, value, MANAGEMENT_FACTORY_CLASS_NAME), PSQLState.INVALID_PARAMETER_VALUE);
    }
    return (long) (percent * maxHeapMemory);
  }

  /**
   * Method to get size based on given string value. String can contains just a number or number +
   * multiplier sign (like T, G, M or K).
   *
   * @param propertyName connection property the value belongs to, named in the error
   * @param value Given string to be parsed.
   * @return Size based on given string.
   * @throws PSQLException if the value ends in neither a digit nor a multiplier
   * @throws NumberFormatException if the digits before the multiplier do not form an {@code int},
   *     or a value without a multiplier does not form a {@code long}
   * @throws ArithmeticException if the size in bytes overflows {@code long}
   */
  private static long parseByteValue(String propertyName, String value) throws PSQLException {
    long result = -1;
    long multiplier = 1;
    long mul = 1000;
    String realValue;
    char sign = value.charAt(value.length() - 1);

    switch (sign) {

      case 'T':
      case 't':
        multiplier *= mul;
        // fall through

      case 'G':
      case 'g':
        multiplier *= mul;
        // fall through

      case 'M':
      case 'm':
        multiplier *= mul;
        // fall through

      case 'K':
      case 'k':
        multiplier *= mul;
        realValue = value.substring(0, value.length() - 1);
        result = Math.multiplyExact((long) Integer.parseInt(realValue), multiplier);
        break;

      default:
        if (sign >= '0' && sign <= '9') {
          result = Long.parseLong(value);
        } else {
          throw invalidSize(propertyName, value, null);
        }
        break;
    }
    return result;
  }

  /**
   * Method to adjust result memory limit size. If given memory is larger than 90% of max heap
   * memory then it gonna be reduced to 90% of max heap memory.
   *
   * @param value Size to be adjusted.
   * @return Adjusted size (original size or 90% of max heap memory)
   */
  private static long adjustResultSize(long value, LongSupplier maxHeapBytesSupplier) {
    if (value <= 0) {
      return value;
    }

    long maxHeapMemory = maxHeapBytesSupplier.getAsLong();
    if (maxHeapMemory < 0) {
      return value;
    }

    long maxResultBuffer = (long) (MAX_RESULT_BUFFER_HEAP_FRACTION * maxHeapMemory);
    if (value > maxResultBuffer) {
      long newResult = maxResultBuffer;

      LOGGER.log(Level.WARNING, GT.tr(
          "WARNING! Required to allocate {0} bytes, which exceeded possible heap memory size. Assigned {1} bytes as limit.",
          String.valueOf(value), String.valueOf(newResult)));

      value = newResult;
    }
    return value;
  }

  /**
   * Default heap-bytes source: routes through {@link JvmHeapAccess}, which lives in its own class
   * so the verifier does not need to resolve {@code java.lang.management.ManagementFactory}
   * unless this method is actually invoked. On runtimes without {@code java.lang.management}
   * (e.g., Android ART), loading {@link JvmHeapAccess} throws {@link NoClassDefFoundError} on
   * the INVOKESTATIC below; we map that to {@code -1} to signal "max heap unavailable".
   */
  private static long defaultMaxHeapBytesOrNegativeOne() {
    try {
      return JvmHeapAccess.maxHeapBytes();
    } catch (LinkageError e) {
      return -1;
    }
  }

  /**
   * Returns the exception for a value of {@code propertyName} that is not in the size syntax.
   *
   * @param cause the parse failure, or {@code null} when the syntax check found the problem
   */
  private static PSQLException invalidSize(String propertyName, String value,
      @Nullable Throwable cause) {
    return new PSQLException(GT.tr(
        "The {0} connection property has the value {1}, which is not a valid size. Give a byte count such as 150M or a share of the heap with the p suffix, such as 10p.",
        propertyName, value), PSQLState.INVALID_PARAMETER_VALUE, cause);
  }
}
