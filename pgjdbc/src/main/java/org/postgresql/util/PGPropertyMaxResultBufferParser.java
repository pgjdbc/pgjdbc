/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.management.ManagementFactory;
import java.util.logging.Level;
import java.util.logging.Logger;

public class PGPropertyMaxResultBufferParser {

  private static final Logger LOGGER = Logger.getLogger(PGPropertyMaxResultBufferParser.class.getName());

  private static final String[] PERCENT_PHRASES = new String[]{
    "p",
    "pct",
    "percent"
  };

  /**
   * Method to parse value of max result buffer size.
   *
   * @param value string containing size of bytes with optional multiplier (T, G, M or K) or percent
   *              value to declare max percent of heap memory to use.
   * @return value of max result buffer size.
   * @throws PSQLException Exception when given value can't be parsed.
   */
  public static long parseProperty(@Nullable String value) throws PSQLException {
    long result = -1;
    //noinspection StatementWithEmptyBody
    if (value == null) {
      // default branch
    } else if (checkIfValueContainsPercent(value)) {
      result = parseBytePercentValue(value);
    } else if (!value.isEmpty()) {
      result = parseByteValue(value);
    }
    result = adjustResultSize(result);
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
  private static long parseBytePercentValue(String value) throws PSQLException {
    long result = -1;
    int length;

    if (!value.isEmpty()) {
      length = getPercentPhraseLengthIfContains(value);

      if (length == -1) {
        throwExceptionAboutParsingError(
            "Received MaxResultBuffer parameter can't be parsed. Value received to parse: {0}",
            value);
      }

      result = calculatePercentOfMemory(value, length);
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
   * @param value               String which contains percent + percent phrase which gonna be used
   *                            during calculations.
   * @param percentPhraseLength Length of percent phrase inside given value.
   * @return Size of byte buffer based on percent of max heap memory.
   */
  private static long calculatePercentOfMemory(String value, int percentPhraseLength) {
    String realValue = value.substring(0, value.length() - percentPhraseLength);
    double percent = Double.parseDouble(realValue) / 100;
    long result = (long) (percent * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());
    return result;
  }

  /**
   * Method to get size based on given string value. String can contains just a number or number +
   * multiplier sign (like T, G, M or K).
   *
   * @param value Given string to be parsed.
   * @return Size based on given string.
   * @throws PSQLException Exception when given value can't be parsed.
   */
  private static long parseByteValue(String value) throws PSQLException {
    long result = -1;
    long multiplier = 1;
    long mul = 1000;
    String realValue;
    char sign = value.charAt(value.length() - 1);

    switch (sign) {

      case 'T':
      case 't':
        multiplier *= mul;

      case 'G':
      case 'g':
        multiplier *= mul;

      case 'M':
      case 'm':
        multiplier *= mul;

      case 'K':
      case 'k':
        multiplier *= mul;
        realValue = value.substring(0, value.length() - 1);
        result = Integer.parseInt(realValue) * multiplier;
        break;

      case '%':
        return result;

      default:
        if (sign >= '0' && sign <= '9') {
          result = Long.parseLong(value);
        } else {
          throwExceptionAboutParsingError(
              "Received MaxResultBuffer parameter can't be parsed. Value received to parse: {0}",
              value);
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
  private static long adjustResultSize(long value) {
    if (value > 0.9 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax()) {
      long newResult = (long) (0.9 * ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax());

      LOGGER.log(Level.WARNING, GT.tr(
          "WARNING! Required to allocate {0} bytes, which exceeded possible heap memory size. Assigned {1} bytes as limit.",
          String.valueOf(value), String.valueOf(newResult)));

      value = newResult;
    }
    return value;
  }

  /**
   * Method to throw message for parsing MaxResultBuffer.
   *
   * @param message Message to be added to exception.
   * @param values  Values to be put inside exception message.
   * @throws PSQLException Exception when given value can't be parsed.
   */
  private static void throwExceptionAboutParsingError(String message, Object... values) throws PSQLException {
    throw new PSQLException(GT.tr(
      message,
      values),
      PSQLState.SYNTAX_ERROR);
  }
}
