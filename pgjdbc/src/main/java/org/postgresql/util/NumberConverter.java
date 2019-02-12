/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;


public class NumberConverter {

  private NumberConverter() {}

  /**
   * A dummy exception thrown when fast byte[] to number parsing fails and no value can be returned.
   * The exact stack trace does not matter because the exception is always caught and is not visible
   * to users.
   */
  public static final NumberFormatException FAST_NUMBER_FAILED = new NumberFormatException() {

    // Override fillInStackTrace to prevent memory leak via Throwable.backtrace hidden field
    // The field is not observable via reflection, however when throwable contains stacktrace, it
    // does
    // hold strong references to user objects (e.g. classes -> classloaders), thus it might lead to
    // OutOfMemory conditions.
    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }
  };

  /**
   * Optimised byte[] to number parser. This code does not handle null values, so the caller must do
   * checkResultSet and handle null values prior to calling this function.
   *
   * @param bytes The bytes to parse.
   * @return The parsed number.
   * @throws NumberFormatException If the number is invalid or the out of range for fast parsing.
   *         The value must then be parsed by {@link #toLong(String)}.
   */
  public static long getFastLong(byte[] bytes) throws NumberFormatException {

    if (bytes.length == 0) {
      throw FAST_NUMBER_FAILED;
    }

    long val = 0;
    int start;
    boolean neg;
    if (bytes[0] == '-') {
      neg = true;
      start = 1;
      if (bytes.length == 1 || bytes.length > 19) {
        throw FAST_NUMBER_FAILED;
      }
    } else {
      start = 0;
      neg = false;
      if (bytes.length > 18) {
        throw FAST_NUMBER_FAILED;
      }
    }

    while (start < bytes.length) {
      byte b = bytes[start++];
      if (b < '0' || b > '9') {
        throw FAST_NUMBER_FAILED;
      }

      val *= 10;
      val += b - '0';
    }

    if (neg) {
      val = -val;
    }

    return val;
  }

  /**
   * Optimised byte[] to number parser. This code does not handle null values, so the caller must do
   * checkResultSet and handle null values prior to calling this function.
   *
   * @param bytes The bytes to parse.
   * @return The parsed number.
   * @throws NumberFormatException If the number is invalid or the out of range for fast parsing.
   *         The value must then be parsed by {@link #toInt(String)}.
   */
  public static int getFastInt(byte[] bytes) throws NumberFormatException {

    if (bytes.length == 0) {
      throw FAST_NUMBER_FAILED;
    }

    int val = 0;
    int start;
    boolean neg;
    if (bytes[0] == '-') {
      neg = true;
      start = 1;
      if (bytes.length == 1 || bytes.length > 10) {
        throw FAST_NUMBER_FAILED;
      }
    } else {
      start = 0;
      neg = false;
      if (bytes.length > 9) {
        throw FAST_NUMBER_FAILED;
      }
    }

    while (start < bytes.length) {
      byte b = bytes[start++];
      if (b < '0' || b > '9') {
        throw FAST_NUMBER_FAILED;
      }

      val *= 10;
      val += b - '0';
    }

    if (neg) {
      val = -val;
    }

    return val;
  }

  /**
   * Optimised byte[] to number parser. This code does not handle null values, so the caller must do
   * checkResultSet and handle null values prior to calling this function.
   *
   * @param bytes The bytes to parse.
   * @return The parsed number.
   * @throws NumberFormatException If the number is invalid or the out of range for fast parsing.
   *         The value must then be parsed by {@link #toBigDecimal(String, int)}.
   */
  public static BigDecimal getFastBigDecimal(byte[] bytes) throws NumberFormatException {

    if (bytes.length == 0) {
      throw FAST_NUMBER_FAILED;
    }

    int scale = 0;
    long val = 0;
    int start;
    boolean neg;
    if (bytes[0] == '-') {
      neg = true;
      start = 1;
      if (bytes.length == 1 || bytes.length > 19) {
        throw FAST_NUMBER_FAILED;
      }
    } else {
      start = 0;
      neg = false;
      if (bytes.length > 18) {
        throw FAST_NUMBER_FAILED;
      }
    }

    int periodsSeen = 0;
    while (start < bytes.length) {
      byte b = bytes[start++];
      if (b < '0' || b > '9') {
        if (b == '.') {
          scale = bytes.length - start;
          periodsSeen++;
          continue;
        } else {
          throw FAST_NUMBER_FAILED;
        }
      }
      val *= 10;
      val += b - '0';
    }

    int numNonSignChars = neg ? bytes.length - 1 : bytes.length;
    if (periodsSeen > 1 || periodsSeen == numNonSignChars) {
      throw FAST_NUMBER_FAILED;
    }

    if (neg) {
      val = -val;
    }

    return BigDecimal.valueOf(val, scale);
  }

  /**
   * <p>This is used to fix get*() methods on Money fields. It should only be used by those methods!</p>
   *
   * <p>It converts ($##.##) to -##.## and $##.## to ##.##</p>
   *
   * @param s the string value to trim
   * @return numeric-parsable representation of money string value
   */
  public static String trimMoney(String s) {
    if (s == null) {
      return null;
    }

    // if we don't have at least 2 characters it can't be money.
    if (s.length() < 2) {
      return s;
    }

    // Handle Money
    char ch = s.charAt(0);

    // optimise for non-money type: return immediately with one check
    // if the first char cannot be '(', '$' or '-'
    if (ch > '-') {
      return s;
    }

    if (ch == '(') {
      s = "-" + PGtokenizer.removePara(s).substring(1);
    } else if (ch == '$') {
      s = s.substring(1);
    } else if (ch == '-' && s.charAt(1) == '$') {
      s = "-" + s.substring(2);
    }

    return s;
  }

  public static BigDecimal scaleBigDecimal(BigDecimal val, int scale) throws SQLException {
    if (scale == -1) {
      return val;
    }
    try {
      return val.setScale(scale);
    } catch (ArithmeticException e) {
      throw new PSQLException(
          GT.tr("Bad value for type {0} : {1}", "BigDecimal", val),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
  }

  public static BigDecimal toBigDecimal(String s) throws SQLException {
    if (s == null) {
      return null;
    }
    try {
      s = s.trim();
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "BigDecimal", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
  }

  public static BigDecimal toBigDecimal(String s, int scale) throws SQLException {
    if (s == null) {
      return null;
    }
    BigDecimal val = toBigDecimal(s);
    return scaleBigDecimal(val, scale);
  }

  // ----------------- Formatting Methods -------------------

  private static final BigInteger BYTEMAX = BigInteger.valueOf(Byte.MAX_VALUE);
  private static final BigInteger BYTEMIN = BigInteger.valueOf(Byte.MIN_VALUE);

  public static byte toByte(String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        // TODO: Why does getByte("") return 0?  Is this documented?  Is this tested?  Is this done other places?
        if (s.isEmpty()) {
          return 0;
        }
        // try the optimal parse
        return Byte.parseByte(s);
      } catch (NumberFormatException e) {
        // didn't work, assume the column is not a byte
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();
          int gt = i.compareTo(BYTEMAX);
          int lt = i.compareTo(BYTEMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.byteValue();

        } catch (NumberFormatException ne) {
          // TODO: Are these secondary NumberFormatExceptions actually "Out of Range"? or better yet
          //       something completely unparseable/unconvertible?  This would be here and the many
          //       other parsers.
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  private static final BigInteger SHORTMAX = new BigInteger(Short.toString(Short.MAX_VALUE));
  private static final BigInteger SHORTMIN = new BigInteger(Short.toString(Short.MIN_VALUE));

  public static short toShort(String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Short.parseShort(s);
      } catch (NumberFormatException e) {
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();
          int gt = i.compareTo(SHORTMAX);
          int lt = i.compareTo(SHORTMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "short", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.shortValue();

        } catch (NumberFormatException ne) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "short", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  private static final BigInteger INTMAX = new BigInteger(Integer.toString(Integer.MAX_VALUE));
  private static final BigInteger INTMIN = new BigInteger(Integer.toString(Integer.MIN_VALUE));

  public static int toInt(String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Integer.parseInt(s);
      } catch (NumberFormatException e) {
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();

          int gt = i.compareTo(INTMAX);
          int lt = i.compareTo(INTMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "int", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.intValue();

        } catch (NumberFormatException ne) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "int", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  private static final BigInteger LONGMAX = new BigInteger(Long.toString(Long.MAX_VALUE));
  private static final BigInteger LONGMIN = new BigInteger(Long.toString(Long.MIN_VALUE));

  public static long toLong(String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Long.parseLong(s);
      } catch (NumberFormatException e) {
        try {
          BigDecimal n = new BigDecimal(s);
          BigInteger i = n.toBigInteger();
          int gt = i.compareTo(LONGMAX);
          int lt = i.compareTo(LONGMIN);

          if (gt > 0 || lt < 0) {
            throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "long", s),
                PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
          }
          return i.longValue();
        } catch (NumberFormatException ne) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "long", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

  public static float toFloat(String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Float.parseFloat(s);
      } catch (NumberFormatException e) {
        throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "float", s),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
    }
    return 0; // SQL NULL
  }

  public static double toDouble(String s) throws SQLException {
    if (s != null) {
      try {
        s = s.trim();
        return Double.parseDouble(s);
      } catch (NumberFormatException e) {
        throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "double", s),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
    }
    return 0; // SQL NULL
  }
}
