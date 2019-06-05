/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;

public class Numbers {
  private static final BigInteger BYTEMIN = new BigInteger(Byte.toString(Byte.MIN_VALUE));
  private static final BigInteger BYTEMAX = new BigInteger(Byte.toString(Byte.MAX_VALUE));

  private static final BigInteger SHORTMIN = new BigInteger(Short.toString(Short.MIN_VALUE));
  private static final BigInteger SHORTMAX = new BigInteger(Short.toString(Short.MAX_VALUE));

  private static final BigInteger INTMIN = new BigInteger(Integer.toString(Integer.MIN_VALUE));
  private static final BigInteger INTMAX = new BigInteger(Integer.toString(Integer.MAX_VALUE));

  private static final BigInteger LONGMIN = new BigInteger(Long.toString(Long.MIN_VALUE));
  private static final BigInteger LONGMAX = new BigInteger(Long.toString(Long.MAX_VALUE));

  public static byte toByte(String s) throws SQLException {
    if (s != null) {
      s = s.trim();
      if (s.isEmpty()) {
        return 0;
      }
      try {
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
        } catch (NumberFormatException ex) {
          throw new PSQLException(GT.tr("Bad value for type {0} : {1}", "byte", s),
              PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
        }
      }
    }
    return 0; // SQL NULL
  }

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

  public static BigDecimal toBigDecimal(String s, int scale) throws SQLException {
    if (s == null) {
      return null;
    }
    BigDecimal val = toBigDecimal(s);
    return scaleBigDecimal(val, scale);
  }

  public static BigDecimal scaleBigDecimal(BigDecimal val, int scale) throws PSQLException {
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
}
