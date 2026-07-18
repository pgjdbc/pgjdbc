/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

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
 * <p>A codec opting into this interface implements <em>both</em> the streaming
 * form here and the {@code String}-returning {@link #encodeText(Object,
 * TypeDescriptor, CodecContext)} inherited from {@link TextCodec}; both are
 * mandatory so the streaming form cannot be silently forgotten.</p>
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
  void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException;
}
