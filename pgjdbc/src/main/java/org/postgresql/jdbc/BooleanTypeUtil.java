/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
   * Maps String values to Boolean constants. This starts with all lowers, but
   * alternate cases will be added as encountered.
   */
  private static final Map<String, Boolean> STRING_TO_BOOL_ALL_LOWERS = new ConcurrentHashMap<String, Boolean>(24);

  static {
    STRING_TO_BOOL_ALL_LOWERS.put("1", Boolean.TRUE);
    STRING_TO_BOOL_ALL_LOWERS.put("true", Boolean.TRUE);
    STRING_TO_BOOL_ALL_LOWERS.put("t", Boolean.TRUE);
    STRING_TO_BOOL_ALL_LOWERS.put("yes", Boolean.TRUE);
    STRING_TO_BOOL_ALL_LOWERS.put("y", Boolean.TRUE);
    STRING_TO_BOOL_ALL_LOWERS.put("on", Boolean.TRUE);
    STRING_TO_BOOL_ALL_LOWERS.put("0", Boolean.FALSE);
    STRING_TO_BOOL_ALL_LOWERS.put("false", Boolean.FALSE);
    STRING_TO_BOOL_ALL_LOWERS.put("f", Boolean.FALSE);
    STRING_TO_BOOL_ALL_LOWERS.put("no", Boolean.FALSE);
    STRING_TO_BOOL_ALL_LOWERS.put("n", Boolean.FALSE);
    STRING_TO_BOOL_ALL_LOWERS.put("off", Boolean.FALSE);
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
      return fromCharacter((Character) in);
    }
    if (in instanceof Number) {
      return fromNumber((Number) in);
    }
    throw new PSQLException("Cannot cast to boolean", PSQLState.CANNOT_COERCE);
  }

  private static boolean fromString(final String strval) throws PSQLException {
    // Leading or trailing whitespace is ignored, and case does not matter.
    final String val = strval.trim();
    Boolean result = STRING_TO_BOOL_ALL_LOWERS.get(val);
    if (result == null) {
      result = STRING_TO_BOOL_ALL_LOWERS.get(val.toLowerCase(Locale.ENGLISH));
      if (result != null) {
        STRING_TO_BOOL_ALL_LOWERS.put(val, result);
      } else {
        throw cannotCoerceException(strval);
      }
    }
    return result.booleanValue();
  }

  private static boolean fromCharacter(final Character charval) throws PSQLException {
    switch (charval.charValue()) {
      case '1':
      case 't':
      case 'y':
      case 'T':
      case 'Y':
        return true;
      case '0':
      case 'f':
      case 'n':
      case 'F':
      case 'N':
        return false;
      default:
        throw cannotCoerceException(charval);
    }
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
