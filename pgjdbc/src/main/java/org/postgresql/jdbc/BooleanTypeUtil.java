/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Helper class to handle boolean type of PostgreSQL.
 *
 * <p>Based on values accepted by the PostgreSQL server:
 * https://www.postgresql.org/docs/current/static/datatype-boolean.html</p>
 */
public class BooleanTypeUtil {

  private static final Logger LOGGER = Logger.getLogger(BooleanTypeUtil.class.getName());

  private BooleanTypeUtil() {
  }

  /**
   * Cast an Object value to the corresponding boolean value.
   *
   * @param in Object to cast into boolean
   * @return boolean value corresponding to the cast of the object
   * @throws PSQLException PSQLState.CANNOT_COERCE
   */
  public static boolean castToBoolean(final Object in) throws PSQLException {
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

  /**
   * Parses a boolean value from a character sequence.
   * Accepts PostgreSQL boolean literals: true, false, t, f, yes, no, y, n, on, off, 1, 0.
   *
   * <p>Takes a {@link CharSequence} so a container decoding a {@code bool[]}/{@code bit[]} element can
   * pass a borrowed {@code CharArraySequence} slice, and a {@link String} caller passes itself: the
   * happy path reads the literal in place and copies nothing out.</p>
   *
   * @param strval the sequence to parse
   * @return the boolean value
   * @throws PSQLException if the sequence is not a recognized boolean literal
   */
  public static boolean fromString(final CharSequence strval) throws PSQLException {
    // Leading or trailing whitespace is ignored, and case does not matter. Trim by moving bounds
    // rather than copying out a trimmed String, so a borrowed slice stays allocation-free.
    int start = 0;
    int end = strval.length();
    while (start < end && strval.charAt(start) <= ' ') {
      start++;
    }
    while (end > start && strval.charAt(end - 1) <= ' ') {
      end--;
    }
    // Every single-character literal ('t'/'f'/'y'/'n'/'1'/'0', either case) is exactly what
    // fromCharacter recognizes, so delegate rather than repeat the checks; only the multi-character
    // words are matched here.
    if (end - start == 1) {
      return fromCharacter(strval.charAt(start));
    }
    if (matchesIgnoreCase(strval, start, end, "true") || matchesIgnoreCase(strval, start, end, "yes")
        || matchesIgnoreCase(strval, start, end, "on")) {
      return true;
    }
    if (matchesIgnoreCase(strval, start, end, "false") || matchesIgnoreCase(strval, start, end, "no")
        || matchesIgnoreCase(strval, start, end, "off")) {
      return false;
    }
    throw cannotCoerceException(strval.toString());
  }

  /**
   * Compares {@code strval[start, end)} to an all-lower-case ASCII letter {@code literal}. {@code |
   * 0x20} folds an upper-case ASCII letter to lower case; any non-letter folds to a non-letter and so
   * fails to match. Needs no {@code String} or {@code Locale}.
   */
  private static boolean matchesIgnoreCase(final CharSequence strval, final int start, final int end,
      final String literal) {
    if (end - start != literal.length()) {
      return false;
    }
    for (int i = 0; i < literal.length(); i++) {
      if ((strval.charAt(start + i) | 0x20) != literal.charAt(i)) {
        return false;
      }
    }
    return true;
  }

  static boolean fromCharacter(final char charval) throws PSQLException {
    // | 0x20 folds an ASCII letter to lower case, so 't'/'T' and 'y'/'Y' each need one comparison.
    // '0'/'1' already carry that bit, so compare them raw rather than also accepting the 0x10/0x11
    // control characters that would fold onto them.
    final int lower = charval | 0x20;
    if (charval == '1' || lower == 't' || lower == 'y') {
      return true;
    }
    if (charval == '0' || lower == 'f' || lower == 'n') {
      return false;
    }
    throw cannotCoerceException(charval);
  }

  static boolean fromNumber(final Number numval) throws PSQLException {
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

  /**
   * Functional adapter the codec layer uses to lazily retrieve the codec's
   * native string representation when constructing an error message.
   */
  @FunctionalInterface
  public interface StringSupplier {
    @Nullable String get() throws SQLException;
  }

  /**
   * Coerces {@code value} to boolean using the legacy contract (Boolean,
   * Number with values 0/1, String/Character literals). On failure, formats
   * the error message using {@code stringSupplier} so callers (codecs) can
   * provide a richer text representation than {@code value.toString()}.
   *
   * @param value decoded codec value
   * @param stringSupplier supplier for the codec's native string form
   * @return the boolean value
   * @throws SQLException if conversion fails
   */
  public static boolean castAndCheck(@Nullable Object value, StringSupplier stringSupplier)
      throws SQLException {
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    if (value instanceof Number) {
      double d = ((Number) value).doubleValue();
      if (d == 1.0d) {
        return true;
      }
      if (d == 0.0d) {
        return false;
      }
    }
    if (value instanceof String) {
      try {
        return fromString((String) value);
      } catch (PSQLException ignore) {
        // fall through to the formatted error below
      }
    }
    if (value instanceof org.postgresql.util.PGobject) {
      String pgString = ((org.postgresql.util.PGobject) value).getValue();
      if (pgString != null) {
        try {
          return fromString(pgString);
        } catch (PSQLException ignore) {
          // fall through to the formatted error below
        }
      }
    }
    if (value instanceof Character) {
      try {
        return fromCharacter((Character) value);
      } catch (PSQLException ignore) {
        // fall through to the formatted error below
      }
    }
    String text = stringSupplier.get();
    if (text == null) {
      text = value == null ? "null" : value.toString();
    }
    throw new PSQLException(
        GT.tr("Cannot cast to boolean: \"{0}\"", text), PSQLState.CANNOT_COERCE);
  }

  private static PSQLException cannotCoerceException(final Object value) {
    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.log(Level.FINE, "Cannot cast to boolean: \"{0}\"", String.valueOf(value));
    }
    return new PSQLException(GT.tr("Cannot cast to boolean: \"{0}\"", String.valueOf(value)),
        PSQLState.CANNOT_COERCE);
  }

}
