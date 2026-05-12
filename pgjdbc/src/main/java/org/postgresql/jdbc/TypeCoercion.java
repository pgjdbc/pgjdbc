/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.sql.Clob;
import java.sql.SQLException;

/**
 * Shared type coercion logic used by both PgPreparedStatement (setObject with target SQL type)
 * and PgResultSet (array element conversion in getObject(i, T[].class)).
 *
 * <p>Handles conversion from arbitrary Java objects to specific numeric/string target types,
 * as required by JDBC's setObject(int, Object, int sqlType) contract.</p>
 */
final class TypeCoercion {

  private TypeCoercion() {
  }

  /**
   * Coerces an object to int.
   *
   * @param in the input value (String, Number, Boolean, Date, Clob, Character)
   * @return the int value
   * @throws SQLException if the conversion is not possible
   */
  static int toInt(final Object in) throws SQLException {
    try {
      if (in instanceof Number) {
        return ((Number) in).intValue();
      }
      if (in instanceof String) {
        return Integer.parseInt((String) in);
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1 : 0;
      }
      if (in instanceof java.util.Date) {
        @SuppressWarnings("JavaUtilDate")
        long time = ((java.util.Date) in).getTime();
        return (int) time;
      }
      if (in instanceof Clob) {
        return Integer.parseInt(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Integer.parseInt(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCast(in, "int", e);
    }
    throw cannotCast(in, "int");
  }

  /**
   * Coerces an object to short.
   *
   * @param in the input value (String, Number, Boolean, Date, Clob, Character)
   * @return the short value
   * @throws SQLException if the conversion is not possible
   */
  static short toShort(final Object in) throws SQLException {
    try {
      if (in instanceof Number) {
        return ((Number) in).shortValue();
      }
      if (in instanceof String) {
        return Short.parseShort((String) in);
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? (short) 1 : (short) 0;
      }
      if (in instanceof java.util.Date) {
        @SuppressWarnings("JavaUtilDate")
        long time = ((java.util.Date) in).getTime();
        return (short) time;
      }
      if (in instanceof Clob) {
        return Short.parseShort(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Short.parseShort(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCast(in, "short", e);
    }
    throw cannotCast(in, "short");
  }

  /**
   * Coerces an object to long.
   *
   * @param in the input value (String, Number, Boolean, Date, Clob, Character)
   * @return the long value
   * @throws SQLException if the conversion is not possible
   */
  static long toLong(final Object in) throws SQLException {
    try {
      if (in instanceof Number) {
        return ((Number) in).longValue();
      }
      if (in instanceof String) {
        return Long.parseLong((String) in);
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1L : 0L;
      }
      if (in instanceof java.util.Date) {
        @SuppressWarnings("JavaUtilDate")
        long time = ((java.util.Date) in).getTime();
        return time;
      }
      if (in instanceof Clob) {
        return Long.parseLong(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Long.parseLong(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCast(in, "long", e);
    }
    throw cannotCast(in, "long");
  }

  /**
   * Coerces an object to float.
   *
   * @param in the input value (String, Number, Boolean, Date, Clob, Character)
   * @return the float value
   * @throws SQLException if the conversion is not possible
   */
  static float toFloat(final Object in) throws SQLException {
    try {
      if (in instanceof Number) {
        return ((Number) in).floatValue();
      }
      if (in instanceof String) {
        return Float.parseFloat((String) in);
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1f : 0f;
      }
      if (in instanceof java.util.Date) {
        @SuppressWarnings("JavaUtilDate")
        long time = ((java.util.Date) in).getTime();
        return time;
      }
      if (in instanceof Clob) {
        return Float.parseFloat(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Float.parseFloat(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCast(in, "float", e);
    }
    throw cannotCast(in, "float");
  }

  /**
   * Coerces an object to double.
   *
   * @param in the input value (String, Number, Boolean, Date, Clob, Character)
   * @return the double value
   * @throws SQLException if the conversion is not possible
   */
  static double toDouble(final Object in) throws SQLException {
    try {
      if (in instanceof Number) {
        return ((Number) in).doubleValue();
      }
      if (in instanceof String) {
        return Double.parseDouble((String) in);
      }
      if (in instanceof Boolean) {
        return (Boolean) in ? 1d : 0d;
      }
      if (in instanceof java.util.Date) {
        @SuppressWarnings("JavaUtilDate")
        long time = ((java.util.Date) in).getTime();
        return time;
      }
      if (in instanceof Clob) {
        return Double.parseDouble(asString((Clob) in));
      }
      if (in instanceof Character) {
        return Double.parseDouble(in.toString());
      }
    } catch (final Exception e) {
      throw cannotCast(in, "double", e);
    }
    throw cannotCast(in, "double");
  }

  /**
   * Coerces an object to BigDecimal.
   *
   * @param in the input value (String, BigDecimal, BigInteger, Number, Boolean, Date, Clob, Character)
   * @param scale the scale to apply, or -1 for no scaling
   * @return the BigDecimal value
   * @throws SQLException if the conversion is not possible
   */
  static BigDecimal toBigDecimal(final Object in, final int scale) throws SQLException {
    try {
      BigDecimal rc = null;
      if (in instanceof BigDecimal) {
        rc = (BigDecimal) in;
      } else if (in instanceof String) {
        rc = new BigDecimal((String) in);
      } else if (in instanceof BigInteger) {
        rc = new BigDecimal((BigInteger) in);
      } else if (in instanceof Long || in instanceof Integer || in instanceof Short
          || in instanceof Byte) {
        rc = BigDecimal.valueOf(((Number) in).longValue());
      } else if (in instanceof Double || in instanceof Float) {
        rc = BigDecimal.valueOf(((Number) in).doubleValue());
      } else if (in instanceof Boolean) {
        rc = (Boolean) in ? BigDecimal.ONE : BigDecimal.ZERO;
      } else if (in instanceof java.util.Date) {
        @SuppressWarnings("JavaUtilDate")
        long time = ((java.util.Date) in).getTime();
        rc = BigDecimal.valueOf(time);
      } else if (in instanceof Clob) {
        rc = new BigDecimal(asString((Clob) in));
      } else if (in instanceof Character) {
        rc = new BigDecimal(new char[]{(Character) in});
      }
      if (rc != null) {
        if (scale >= 0) {
          rc = rc.setScale(scale, RoundingMode.HALF_UP);
        }
        return rc;
      }
    } catch (final Exception e) {
      throw cannotCast(in, "BigDecimal", e);
    }
    throw cannotCast(in, "BigDecimal");
  }

  /**
   * Coerces an object to String.
   *
   * @param in the input value
   * @return the String value
   * @throws SQLException if the conversion is not possible
   */
  static String toString(final Object in) throws SQLException {
    try {
      if (in instanceof String) {
        return (String) in;
      }
      if (in instanceof Clob) {
        return asString((Clob) in);
      }
      return in.toString();
    } catch (final Exception e) {
      throw cannotCast(in, "String", e);
    }
  }

  /**
   * Coerces an object to boolean.
   * Delegates to {@link BooleanTypeUtil#castToBoolean(Object)} for consistent
   * PostgreSQL boolean conversion semantics.
   *
   * @param in the input value
   * @return the boolean value
   * @throws SQLException if the conversion is not possible
   */
  static boolean toBoolean(final Object in) throws SQLException {
    return BooleanTypeUtil.castToBoolean(in);
  }

  private static String asString(final Clob in) throws SQLException {
    return in.getSubString(1, (int) in.length());
  }

  private static PSQLException cannotCast(final Object in, final String toType) {
    return cannotCast(in, toType, null);
  }

  private static PSQLException cannotCast(final Object in, final String toType,
      final @Nullable Exception cause) {
    return new PSQLException(
        GT.tr("Cannot convert an instance of {0} to type {1}",
            in.getClass().getName(), toType),
        PSQLState.INVALID_PARAMETER_TYPE, cause);
  }
}
