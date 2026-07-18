/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Boolean coercion shared by the {@code decodeAsBoolean} default methods of {@link BinaryCodec} and
 * {@link TextCodec}. It mirrors the driver's internal {@code BooleanTypeUtil} contract — accepting
 * the PostgreSQL boolean literals plus {@link Number} {@code 0}/{@code 1} — but lives in
 * {@code api.codec} so the public codec interfaces stay free of {@code org.postgresql.jdbc}.
 */
final class BooleanCoercion {

  private BooleanCoercion() {
  }

  /** Supplies the codec's native string form for the error message, evaluated only on failure. */
  @FunctionalInterface
  interface StringSupplier {
    @Nullable String get() throws SQLException;
  }

  /**
   * Coerces {@code value} to a boolean: a {@link Boolean} as-is, a {@link Number} {@code 0}/{@code
   * 1}, a {@link String}/{@link Character}/{@link PGobject} holding a PostgreSQL boolean literal. On
   * failure, formats the message from {@code stringSupplier} so the error carries the codec's own
   * text form.
   *
   * @param value the decoded codec value
   * @param stringSupplier supplier for the codec's native string form
   * @return the boolean value
   * @throws SQLException if {@code value} is not a recognised boolean
   */
  static boolean castAndCheck(@Nullable Object value, StringSupplier stringSupplier)
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
      Boolean parsed = fromString((String) value);
      if (parsed != null) {
        return parsed;
      }
    }
    if (value instanceof PGobject) {
      String pgString = ((PGobject) value).getValue();
      if (pgString != null) {
        Boolean parsed = fromString(pgString);
        if (parsed != null) {
          return parsed;
        }
      }
    }
    if (value instanceof Character) {
      Boolean parsed = fromCharacter((Character) value);
      if (parsed != null) {
        return parsed;
      }
    }
    String text = stringSupplier.get();
    if (text == null) {
      text = value == null ? "null" : value.toString();
    }
    throw new PSQLException(
        GT.tr("Cannot cast to boolean: \"{0}\"", text), PSQLState.CANNOT_COERCE);
  }

  /**
   * Parses a PostgreSQL boolean literal (case-insensitive, surrounding whitespace ignored): {@code
   * 1}/{@code true}/{@code t}/{@code yes}/{@code y}/{@code on} or {@code 0}/{@code false}/{@code
   * f}/{@code no}/{@code n}/{@code off}. Returns null when the text is not a recognised literal.
   */
  private static @Nullable Boolean fromString(String strval) {
    String val = strval.trim();
    if ("1".equals(val) || "true".equalsIgnoreCase(val)
        || "t".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val)
        || "y".equalsIgnoreCase(val) || "on".equalsIgnoreCase(val)) {
      return Boolean.TRUE;
    }
    if ("0".equals(val) || "false".equalsIgnoreCase(val)
        || "f".equalsIgnoreCase(val) || "no".equalsIgnoreCase(val)
        || "n".equalsIgnoreCase(val) || "off".equalsIgnoreCase(val)) {
      return Boolean.FALSE;
    }
    return null;
  }

  /**
   * Parses a single-character boolean ({@code 1}/{@code t}/{@code T}/{@code y}/{@code Y} or {@code
   * 0}/{@code f}/{@code F}/{@code n}/{@code N}). Returns null when the character is not recognised.
   */
  private static @Nullable Boolean fromCharacter(Character charval) {
    if ('1' == charval || 't' == charval || 'T' == charval
        || 'y' == charval || 'Y' == charval) {
      return Boolean.TRUE;
    }
    if ('0' == charval || 'f' == charval || 'F' == charval
        || 'n' == charval || 'N' == charval) {
      return Boolean.FALSE;
    }
    return null;
  }
}
