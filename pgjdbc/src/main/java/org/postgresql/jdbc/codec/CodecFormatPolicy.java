/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.TypeDescriptor;

import java.sql.SQLException;

/**
 * Chooses the wire {@link Format} for a parameter bind, the driver-side negotiation policy layered
 * over the {@link CodecFormatSupport} facts.
 *
 * <p>Unlike {@link org.postgresql.api.codec.Codecs#encode}, which enforces a format the caller has
 * already fixed, this negotiates: binary when the backend accepts it and the codec can produce it
 * for the value, otherwise text. The fallback belongs to the live extended-query protocol, which
 * picks a format per parameter; the offline and {@code COPY} paths never fall back, so this stays on
 * the driver side rather than in the public codec API.</p>
 *
 * <p>Both {@code PgPreparedStatement} bind sites route through here so the binary/text decision
 * cannot drift between them: the array path and the scalar path apply the same rule, and neither
 * feeds a value into {@code encodeBinary} that the codec cannot binary-encode.</p>
 */
public final class CodecFormatPolicy {

  private CodecFormatPolicy() {
  }

  /**
   * Chooses the format to bind {@code value} as. Prefers {@link Format#BINARY} when
   * {@code backendCanBinary} and the codec can binary-encode this value, otherwise
   * {@link Format#TEXT}.
   *
   * @param codec the codec resolved for the parameter type
   * @param value the value to bind
   * @param type the target type metadata
   * @param ctx the codec context
   * @param backendCanBinary whether the caller's protocol state and the server both allow a binary
   *     send for this type (for example {@code binaryTransferSend(oid)} plus
   *     {@code backendCanReceiveBinary(type)})
   * @return the chosen wire format
   * @throws SQLException if the codec can write neither format, or capability resolution fails
   */
  public static Format chooseBindFormat(Codec codec, Object value, TypeDescriptor type,
      CodecContext ctx, boolean backendCanBinary) throws SQLException {
    if (backendCanBinary && CodecFormatSupport.canWriteBinary(codec, value, type, ctx)) {
      return Format.BINARY;
    }
    if (CodecFormatSupport.canWriteText(codec)) {
      return Format.TEXT;
    }
    throw Exceptions.noWritableFormat(type);
  }
}
