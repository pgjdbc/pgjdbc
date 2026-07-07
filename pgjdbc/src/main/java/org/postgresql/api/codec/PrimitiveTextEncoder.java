/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Optional capability a {@link StreamingTextCodec} implements to encode a Java
 * primitive straight into an {@link Appendable} without boxing it first.
 *
 * <p>This is the text counterpart of {@link PrimitiveBinaryEncoder}: the {@code
 * SQLOutput} composite writer calls the primitive writers, and routing them
 * through the {@code Object}-typed {@link TextCodec#encodeText(Object,
 * TypeDescriptor, CodecContext)} boxes the primitive only to unbox it inside the
 * codec. A codec that opts in appends the digits (or {@code t}/{@code f})
 * directly; note the text form still allocates the digit {@code String}, so the
 * saving is the box, not the character buffer.</p>
 *
 * <p>Every method has a default that boxes and forwards to the streaming
 * {@code Object} form, so a codec overrides only the primitive(s) it can encode
 * natively; the rest keep the boxing fallback.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public interface PrimitiveTextEncoder extends StreamingTextCodec {

  /**
   * Encodes an {@code int} as text, writing directly into {@code out}. The three
   * narrowing JDBC writers ({@code writeByte}, {@code writeShort}, {@code
   * writeInt}) all widen to {@code int} and reach this method.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the textual representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeInt(int value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    encodeText(value, type, ctx, out);
  }

  /**
   * Encodes a {@code long} as text, writing directly into {@code out}.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the textual representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeLong(long value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    encodeText(value, type, ctx, out);
  }

  /**
   * Encodes a {@code float} as text, writing directly into {@code out}.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the textual representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeFloat(float value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    encodeText(value, type, ctx, out);
  }

  /**
   * Encodes a {@code double} as text, writing directly into {@code out}.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the textual representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeDouble(double value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    encodeText(value, type, ctx, out);
  }
}
