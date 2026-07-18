/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Extends {@link BinaryCodec} with a {@link BackpatchingBinarySink}-targeted
 * encode method so callers can stream output directly into a shared buffer
 * without allocating an intermediate {@code byte[]} per element.
 *
 * <p>Primary use case: composing nested encoders. A container codec reserves a
 * length slot in the sink, lets the element codec stream its body straight into
 * the same buffer, and back-patches the slot once the length is known — instead
 * of asking the element codec for a per-element {@code byte[]} that is copied
 * into the container buffer and then discarded. See {@link BackpatchingBinarySink}
 * for the reserve/patch protocol.</p>
 *
 * <p>A codec opting into this interface implements <em>both</em> the streaming
 * form here and the {@code byte[]}-returning {@link #encodeBinary(Object,
 * TypeDescriptor, CodecContext)} inherited from {@link BinaryCodec}; both are
 * mandatory so the streaming form cannot be silently forgotten.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public interface StreamingBinaryCodec extends BinaryCodec {

  /**
   * Encodes {@code value} as binary, writing directly into {@code out}.
   *
   * @param value the Java object to encode (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the binary representation
   * @throws SQLException if encoding fails
   * @throws IOException if {@code out} throws
   */
  void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException;
}
