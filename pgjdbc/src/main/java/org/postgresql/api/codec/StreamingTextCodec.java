/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Extends {@link TextCodec} with an {@link Appendable}-targeted encode method
 * so callers can stream output directly into a shared buffer without
 * allocating an intermediate {@code String} per element.
 *
 * <p>Primary use case: composing nested encoders. For example, the array
 * codec for {@code composite[]} can wrap its output in an
 * {@link org.postgresql.jdbc.codec.EscapingAppendable} and let the composite
 * codec stream into it directly — eliminating the per-element
 * {@code String} that the non-streaming path materializes only to walk it
 * once for escape characters.</p>
 *
 * <p>The {@link #encodeText(Object, PgType, CodecContext)} String-returning
 * form is provided as a default adapter that buffers into a
 * {@link StringBuilder}, so existing callers continue to work and codecs may
 * opt in to streaming incrementally.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public interface StreamingTextCodec extends TextCodec {

  /**
   * Encodes {@code value} as text, writing directly into {@code out}.
   *
   * @param value the Java object to encode (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the textual representation
   * @throws SQLException if encoding fails
   * @throws IOException if {@code out} throws
   */
  void encodeText(Object value, PgType type, CodecContext ctx, Appendable out)
      throws SQLException, IOException;

  /**
   * Default {@code String}-returning form: buffers into a
   * {@link StringBuilder} and delegates to
   * {@link #encodeText(Object, PgType, CodecContext, Appendable)}.
   */
  @Override
  default String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    StringBuilder sb = new StringBuilder();
    try {
      encodeText(value, type, ctx, sb);
    } catch (IOException e) {
      // StringBuilder.append never throws IOException.
      throw new AssertionError(e);
    }
    return sb.toString();
  }
}
