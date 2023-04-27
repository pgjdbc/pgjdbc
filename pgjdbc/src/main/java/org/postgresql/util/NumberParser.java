/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

/**
 * Optimised byte[] to number parser.
 */
public class NumberParser {
  private static final NumberFormatException FAST_NUMBER_FAILED = new NumberFormatException() {
    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  };

  private static final long MAX_LONG_DIV_TEN = Long.MAX_VALUE / 10;

  /**
   * Optimised byte[] to number parser. This code does not handle null values, so the caller must do
   * checkResultSet and handle null values prior to calling this function. Fraction part is
   * discarded.
   *
   * @param bytes integer represented as a sequence of ASCII bytes
   * @return The parsed number.
   * @throws NumberFormatException If the number is invalid or the out of range for fast parsing.
   *                               The value must then be parsed by another (less optimised) method.
   */
  public static long getFastLong(byte[] bytes, long minVal, long maxVal) throws NumberFormatException {
    int len = bytes.length;
    if (len == 0) {
      throw FAST_NUMBER_FAILED;
    }

    boolean neg = bytes[0] == '-';

    long val = 0;
    int start = neg ? 1 : 0;
    while (start < len) {
      byte b = bytes[start++];
      if (b < '0' || b > '9') {
        if (b == '.') {
          if (neg && len == 2 || !neg && len == 1) {
            // we have to check that string is not "." or "-."
            throw FAST_NUMBER_FAILED;
          }
          // check that the rest of the buffer contains only digits
          while (start < len) {
            b = bytes[start++];
            if (b < '0' || b > '9') {
              throw FAST_NUMBER_FAILED;
            }
          }
          break;
        } else {
          throw FAST_NUMBER_FAILED;
        }
      }

      if (val <= MAX_LONG_DIV_TEN) {
        val *= 10;
        val += b - '0';
      } else {
        throw FAST_NUMBER_FAILED;
      }
    }

    if (val < 0) {
      // It is possible to get overflow in two situations:
      // 1. for MIN_VALUE, because abs(MIN_VALUE)=MAX_VALUE+1. In this situation thanks to
      //    complement arithmetic we got correct result and shouldn't do anything with it.
      // 2. for incorrect string, representing a number greater than MAX_VALUE, for example
      //    "9223372036854775809", it this case we have to throw exception
      if (!(neg && val == Long.MIN_VALUE)) {
        throw FAST_NUMBER_FAILED;
      }
    } else if (neg) {
      val = -val;
    }

    if (val < minVal || val > maxVal) {
      throw FAST_NUMBER_FAILED;
    }
    return val;
  }
}
