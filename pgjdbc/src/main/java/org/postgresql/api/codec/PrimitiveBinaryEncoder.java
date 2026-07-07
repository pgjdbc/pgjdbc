/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Optional capability a {@link StreamingBinaryCodec} implements to encode a Java
 * primitive straight into a {@link BackpatchingBinarySink} without boxing it
 * first.
 *
 * <p>The {@code SQLOutput} composite writer calls {@code writeInt}, {@code
 * writeLong}, {@code writeDouble}, and friends with primitives. Routed through
 * the {@code Object}-typed {@link BinaryCodec#encodeBinary(Object,
 * TypeDescriptor, CodecContext)} the primitive would be boxed only to be
 * unboxed again inside the codec. A codec that opts into this interface exposes
 * a primitive-typed entry point per JDBC primitive writer, so the aligned case
 * — {@code writeInt} into an {@code int4} field, {@code writeLong} into {@code
 * int8}, and so on — avoids the box entirely.</p>
 *
 * <p>Every method has a default that boxes and forwards to the streaming
 * {@code Object} form, so a codec overrides only the primitive(s) it can encode
 * natively; the rest keep the boxing fallback. A codec that does not implement
 * this interface at all is handled by the caller's own boxing fallback, so this
 * interface only ever removes a box, never adds behaviour.</p>
 *
 * @since 42.8.0
 */
@Experimental("Streaming codec API is experimental and may change in future releases")
public interface PrimitiveBinaryEncoder extends StreamingBinaryCodec {

  /**
   * Encodes an {@code int} as binary, writing directly into {@code out}. The
   * three narrowing JDBC writers ({@code writeByte}, {@code writeShort},
   * {@code writeInt}) all widen to {@code int} and reach this method.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the binary representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeInt(int value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    encodeBinary(value, type, ctx, out);
  }

  /**
   * Encodes a {@code long} as binary, writing directly into {@code out}.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the binary representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeLong(long value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    encodeBinary(value, type, ctx, out);
  }

  /**
   * Encodes a {@code float} as binary, writing directly into {@code out}.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the binary representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeFloat(float value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    encodeBinary(value, type, ctx, out);
  }

  /**
   * Encodes a {@code double} as binary, writing directly into {@code out}.
   *
   * @param value the value to encode
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @param out the sink to receive the binary representation
   * @throws SQLException if {@code value} is out of range for the target type
   * @throws IOException if {@code out} throws
   */
  default void encodeDouble(double value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    encodeBinary(value, type, ctx, out);
  }
}
