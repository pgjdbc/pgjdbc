/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.sql.SQLException;

/**
 * Optional capability a {@link BinaryCodec} implements to decode its binary wire form to a Java
 * primitive without boxing it first.
 *
 * <p>The primitive accessors used to live as boxing {@code default} methods on {@link BinaryCodec}
 * itself, which meant every codec — a range, a composite, a geometry — advertised a
 * {@code decodeAsInt} it could not honour. They now live here, so only a codec that can actually
 * produce the primitive opts in. A caller with a base-typed reference goes through
 * {@link PrimitiveDecoders#asInt(BinaryCodec, byte[], TypeDescriptor, CodecContext)} and friends,
 * which fall back to boxing through {@link BinaryCodec#decodeBinary} when the codec does not
 * implement this interface.</p>
 *
 * <p>The methods take a {@code [offset, offset + length)} slice of a larger buffer — the same
 * in-place form as {@link BinaryCodec#decodeBinary(byte[], int, int, TypeDescriptor, CodecContext)}
 * — so a container codec can decode a fixed-width element or field straight off its backing buffer
 * with neither a slice copy nor a box. A caller holding a whole value passes
 * {@code (data, 0, data.length, ...)}.</p>
 *
 * <p>Every method has a boxing default (identical to the fallback), so a codec overrides only the
 * primitives it decodes natively. Overriding implementations MUST range-check and throw
 * {@link SQLException} on overflow.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface PrimitiveBinaryDecoder extends BinaryCodec {

  /**
   * Decodes the slice {@code data[offset, offset + length)} as an int.
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or the value overflows int range
   */
  default int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return PrimitiveDecoders.boxToInt(decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a long.
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or the value overflows long range
   */
  default long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return PrimitiveDecoders.boxToLong(decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a float.
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the float value
   * @throws SQLException if decoding fails
   */
  default float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return PrimitiveDecoders.boxToFloat(decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a double.
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the double value
   * @throws SQLException if decoding fails
   */
  default double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return PrimitiveDecoders.boxToDouble(decodeBinary(data, offset, length, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a boolean.
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the boolean value
   * @throws SQLException if decoding fails
   */
  default boolean decodeAsBoolean(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return BooleanCoercion.castAndCheck(
        decodeBinary(data, offset, length, type, ctx), () -> decodeAsString(data, type, ctx));
  }
}
