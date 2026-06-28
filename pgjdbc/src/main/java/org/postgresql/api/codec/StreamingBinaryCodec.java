/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * Extends {@link BinaryCodec} with an {@link OutputStream}-targeted encode
 * method so callers can stream output directly into a shared buffer without
 * allocating an intermediate {@code byte[]} per element.
 *
 * <p>Primary use case: composing nested encoders. The array codec can write
 * each element's bytes straight into its own {@code ByteArrayOutputStream}
 * (with length-prefix back-patching via
 * {@link org.postgresql.jdbc.codec.BackpatchByteArrayOutputStream}) instead
 * of asking the element codec for a per-element {@code byte[]} that's
 * immediately copied into the array buffer.</p>
 *
 * <p>The {@link #encodeBinary(Object, TypeDescriptor, CodecContext)} byte-array form
 * is provided as a default adapter that buffers into a
 * {@link ByteArrayOutputStream}.</p>
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
  void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx, OutputStream out)
      throws SQLException, IOException;

  /**
   * Default {@code byte[]}-returning form: buffers into a
   * {@link ByteArrayOutputStream} and delegates to
   * {@link #encodeBinary(Object, TypeDescriptor, CodecContext, OutputStream)}.
   */
  @Override
  default byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      encodeBinary(value, type, ctx, baos);
    } catch (IOException e) {
      // ByteArrayOutputStream never throws IOException.
      throw new AssertionError(e);
    }
    return baos.toByteArray();
  }
}
