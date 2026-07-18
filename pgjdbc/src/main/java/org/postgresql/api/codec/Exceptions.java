/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.sql.SQLException;

/**
 * Factory for the errors thrown by this package's own dispatch logic (see {@link Codecs}).
 *
 * <p>{@code cannotDecode}/{@code cannotEncode} live on {@link Codecs} instead, since they are
 * public: codecs outside this package (e.g. {@code org.postgresql.jdbc.codec}, {@code
 * org.postgresql.jdbc}) need to report the same conversion errors and cannot see this
 * package-private class.</p>
 */
class Exceptions {
  private Exceptions() {
  }

  /**
   * Creates an error indicating that no codec is registered for a specific format and type.
   *
   * @param type the type descriptor for which no codec is registered
   * @param format the format name for which no codec is available
   * @return an {@link SQLException} indicating the absence of a codec for the given format and type
   */
  static SQLException noCodecForFormat(TypeDescriptor type, String format) {
    return new PSQLException(
        GT.tr("No {0} codec is registered for type {1}.", format, type.getFullName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  /**
   * Creates the error the default primitive-read paths raise when a decoded value cannot be represented
   * in the requested numeric target -- it overflows the target's range, or it is a non-finite
   * {@code NaN}/{@code Infinity} that {@code int}/{@code long}/{@code BigDecimal} cannot hold. Carries
   * {@link PSQLState#NUMERIC_VALUE_OUT_OF_RANGE}, matching the built-in codecs' own range checks.
   *
   * @param value the value that does not fit
   * @param targetType the target type name (for example {@code "int"})
   * @return the out-of-range {@link SQLException}
   */
  static SQLException valueOutOfRange(Object value, String targetType) {
    return new PSQLException(
        GT.tr("Value {0} is out of range for {1}", value, targetType),
        PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
  }
}
