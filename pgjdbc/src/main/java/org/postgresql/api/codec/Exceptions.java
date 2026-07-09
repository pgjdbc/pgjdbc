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
}
