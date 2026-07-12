/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.postgresql.api.Experimental;

import java.sql.SQLException;

/**
 * Optional capability a {@link TextCodec} implements to decode its text wire form to a Java primitive
 * without boxing it first. The text counterpart of {@link PrimitiveBinaryDecoder}.
 *
 * <p>The primitive accessors used to live as boxing {@code default} methods on {@link TextCodec}
 * itself; they now live here, so only a codec that can produce the primitive opts in. A caller with a
 * base-typed reference goes through
 * {@link PrimitiveDecoders#asInt(TextCodec, String, TypeDescriptor, CodecContext)} and friends, which
 * fall back to boxing through {@link TextCodec#decodeText}.</p>
 *
 * <p>Each primitive comes in two forms, mirroring {@link TextCodec#decodeText}: a {@code String}
 * for a caller that already holds one, and a {@code char[]} {@code [offset, offset + length)} slice
 * for a container codec decoding an already-unquoted element in place, with no per-element
 * {@code String}. {@link #decodeTextBytesAsInt}/{@link #decodeTextBytesAsLong} are the ASCII-bytes
 * fast paths a numeric codec overrides to parse digits straight from the wire bytes.</p>
 *
 * <p>Every method has a boxing default (identical to the fallback), so a codec overrides only the
 * primitives it decodes natively. Overriding implementations MUST range-check and throw
 * {@link SQLException} on overflow.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface PrimitiveTextDecoder extends TextCodec {

  /**
   * Decodes {@code data} as an int.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or the value overflows int range
   */
  default int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToInt(decodeText(data, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as an int.
   *
   * @param data the backing buffer
   * @param offset start of this value's chars within {@code data}
   * @param length number of chars for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or the value overflows int range
   */
  default int decodeAsInt(char[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    // Route through the String form, not boxToInt(decodeText(...)): a codec that overrides the String
    // accessor with a non-truncating conversion (numeric/money round to nearest, matching PostgreSQL's
    // numeric->int cast) must decode a char[] the same way, or getInt on "0.9" would round from a String
    // but truncate from an array element. A codec with a faster char[] path overrides this method.
    return decodeAsInt(new String(data, offset, length), type, ctx);
  }

  /**
   * Decodes {@code data} as a long.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or the value overflows long range
   */
  default long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToLong(decodeText(data, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a long.
   *
   * @param data the backing buffer
   * @param offset start of this value's chars within {@code data}
   * @param length number of chars for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or the value overflows long range
   */
  default long decodeAsLong(char[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    // See decodeAsInt(char[]): route through the String form so a rounding/custom String accessor is
    // honoured on a char[] element too.
    return decodeAsLong(new String(data, offset, length), type, ctx);
  }

  /**
   * Decodes {@code data} as a float.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the float value
   * @throws SQLException if decoding fails
   */
  default float decodeAsFloat(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToFloat(decodeText(data, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a float.
   *
   * @param data the backing buffer
   * @param offset start of this value's chars within {@code data}
   * @param length number of chars for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the float value
   * @throws SQLException if decoding fails
   */
  default float decodeAsFloat(char[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    // See decodeAsInt(char[]): route through the String form so an overridden String accessor is
    // honoured on a char[] element too.
    return decodeAsFloat(new String(data, offset, length), type, ctx);
  }

  /**
   * Decodes {@code data} as a double.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the double value
   * @throws SQLException if decoding fails
   */
  default double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToDouble(decodeText(data, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a double.
   *
   * @param data the backing buffer
   * @param offset start of this value's chars within {@code data}
   * @param length number of chars for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the double value
   * @throws SQLException if decoding fails
   */
  default double decodeAsDouble(char[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    // See decodeAsInt(char[]): route through the String form so an overridden String accessor is
    // honoured on a char[] element too.
    return decodeAsDouble(new String(data, offset, length), type, ctx);
  }

  /**
   * Decodes {@code data} as a boolean.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the boolean value
   * @throws SQLException if decoding fails
   */
  default boolean decodeAsBoolean(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return BooleanCoercion.castAndCheck(
        decodeText(data, type, ctx), () -> decodeAsString(data, type, ctx));
  }

  /**
   * Decodes the slice {@code data[offset, offset + length)} as a boolean.
   *
   * @param data the backing buffer
   * @param offset start of this value's chars within {@code data}
   * @param length number of chars for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the boolean value
   * @throws SQLException if decoding fails
   */
  default boolean decodeAsBoolean(char[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    // See decodeAsInt(char[]): route through the String form so an overridden String accessor is
    // honoured on a char[] element too.
    return decodeAsBoolean(new String(data, offset, length), type, ctx);
  }

  /**
   * Decodes ASCII text bytes as an int, without a {@code String} conversion when overridden.
   *
   * @param data the raw text bytes
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or the value overflows int range
   */
  default int decodeTextBytesAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(new String(data, UTF_8), type, ctx);
  }

  /**
   * Decodes ASCII text bytes as a long, without a {@code String} conversion when overridden.
   *
   * @param data the raw text bytes
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or the value overflows long range
   */
  default long decodeTextBytesAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(new String(data, UTF_8), type, ctx);
  }
}
