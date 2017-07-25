/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper class to handle boolean type of PostgreSQL.
 * <p>
 * Based on values accepted by the PostgreSQL server:
 * https://www.postgresql.org/docs/current/static/datatype-boolean.html
 */
class BooleanTypeUtil {

  private static final Logger LOGGER = Logger.getLogger(BooleanTypeUtil.class.getName());

  /**
   * Maps String values to Boolean constants. By using {@link String#CASE_INSENSITIVE_ORDER}
   * {@link Map#get(Object)} is case-insensitive.
   */
  private static final SortedMap<String, Boolean> STRING_TO_BOOL = new TreeMap<String, Boolean>(
      String.CASE_INSENSITIVE_ORDER);

  static {
    STRING_TO_BOOL.put("1", Boolean.TRUE);
    STRING_TO_BOOL.put("true", Boolean.TRUE);
    STRING_TO_BOOL.put("t", Boolean.TRUE);
    STRING_TO_BOOL.put("yes", Boolean.TRUE);
    STRING_TO_BOOL.put("y", Boolean.TRUE);
    STRING_TO_BOOL.put("on", Boolean.TRUE);
    STRING_TO_BOOL.put("0", Boolean.FALSE);
    STRING_TO_BOOL.put("false", Boolean.FALSE);
    STRING_TO_BOOL.put("f", Boolean.FALSE);
    STRING_TO_BOOL.put("no", Boolean.FALSE);
    STRING_TO_BOOL.put("n", Boolean.FALSE);
    STRING_TO_BOOL.put("off", Boolean.FALSE);
  }

  private BooleanTypeUtil() {
  }

  /**
   * Cast an Object value to the corresponding boolean value.
   *
   * @param in Object to cast into boolean
   * @return boolean value corresponding to the cast of the object
   * @throws PSQLException PSQLState.CANNOT_COERCE
   */
  static boolean castToBoolean(final Object in) throws PSQLException {
    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.log(Level.FINE, "Cast to boolean: \"{0}\"", String.valueOf(in));
    }
    if (in instanceof Boolean) {
      return (Boolean) in;
    }
    if (in instanceof String) {
      return fromString((String) in);
    }
    if (in instanceof Character) {
      return fromString(in.toString());
    }
    if (in instanceof Number) {
      return fromNumber((Number) in);
    }
    throw new PSQLException("Cannot cast to boolean", PSQLState.CANNOT_COERCE);
  }

  private static boolean fromString(final String strval) throws PSQLException {
    // Leading or trailing whitespace is ignored, and case does not matter.
    final String val = strval.trim();
    final Boolean result = STRING_TO_BOOL.get(val);
    if (result == null) {
      throw cannotCoerceException(strval);
    }
    return result.booleanValue();
  }

  private static boolean fromNumber(final Number numval) throws PSQLException {
    // Handles BigDecimal, Byte, Short, Integer, Long Float, Double
    // based on the widening primitive conversions.
    final double value = numval.doubleValue();
    if (value == 1.0d) {
      return true;
    }
    if (value == 0.0d) {
      return false;
    }
    throw cannotCoerceException(numval);
  }

  private static PSQLException cannotCoerceException(final Object value) {
    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.log(Level.FINE, "Cannot cast to boolean: \"{0}\"", String.valueOf(value));
    }
    return new PSQLException(GT.tr("Cannot cast to boolean: \"{0}\"", String.valueOf(value)),
        PSQLState.CANNOT_COERCE);
  }

}
