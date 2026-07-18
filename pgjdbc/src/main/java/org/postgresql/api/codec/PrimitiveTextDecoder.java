/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Optional capability a {@link TextCodec} implements to decode its text wire form to a Java primitive
 * without boxing it first. The text counterpart of {@link PrimitiveBinaryDecoder}.
 *
 * <p>The primitive accessors used to live as boxing {@code default} methods on {@link TextCodec}
 * itself; they now live here, so only a codec that can produce the primitive opts in. A caller with a
 * base-typed reference goes through
 * {@link PrimitiveDecoders#asInt(TextCodec, CharSequence, TypeDescriptor, CodecContext)} and friends,
 * which fall back to boxing through {@link TextCodec#decodeText}.</p>
 *
 * <p>Each primitive takes a single {@link CharSequence}, so there is exactly one place to override
 * per primitive and the {@code String} and in-place-slice forms can never drift apart. Unification is
 * needed here because both forms once had boxing defaults, so overriding one and leaving the other
 * let them diverge silently. The two-method {@link TextCodec#decodeText} needs no such unification:
 * its {@code String} form is mandatory and its {@code char[]} form delegates to it, so neither can
 * silently diverge. A caller that
 * already holds a {@code String} passes it straight through (a {@code String} is a
 * {@code CharSequence}); a container codec decoding an already-unquoted element off a larger buffer
 * wraps the slice in a reusable {@link CharArraySequence}, so it parses in place with no per-element
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
   * @param data the text data; a {@code String} or a borrowed {@link CharArraySequence} slice
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or the value overflows int range
   */
  default int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToInt(decodeText(data.toString(), type, ctx));
  }

  /**
   * Decodes {@code data} as a long.
   *
   * @param data the text data; a {@code String} or a borrowed {@link CharArraySequence} slice
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or the value overflows long range
   */
  default long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToLong(decodeText(data.toString(), type, ctx));
  }

  /**
   * Decodes {@code data} as a float.
   *
   * @param data the text data; a {@code String} or a borrowed {@link CharArraySequence} slice
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the float value
   * @throws SQLException if decoding fails
   */
  default float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToFloat(decodeText(data.toString(), type, ctx));
  }

  /**
   * Decodes {@code data} as a double.
   *
   * @param data the text data; a {@code String} or a borrowed {@link CharArraySequence} slice
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the double value
   * @throws SQLException if decoding fails
   */
  default double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PrimitiveDecoders.boxToDouble(decodeText(data.toString(), type, ctx));
  }

  /**
   * Decodes {@code data} as a boolean.
   *
   * @param data the text data; a {@code String} or a borrowed {@link CharArraySequence} slice
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the boolean value
   * @throws SQLException if decoding fails
   */
  default boolean decodeAsBoolean(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String text = data.toString();
    return BooleanCoercion.castAndCheck(
        decodeText(text, type, ctx), () -> decodeAsString(text, type, ctx));
  }

  /**
   * Decodes {@code data} as a {@link BigDecimal}.
   *
   * @param data the text data; a {@code String} or a borrowed {@link CharArraySequence} slice
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the BigDecimal value, or {@code null} if the value is SQL NULL
   * @throws SQLException if the value cannot be represented as a BigDecimal
   */
  default @Nullable BigDecimal decodeAsBigDecimal(CharSequence data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return PrimitiveDecoders.boxToBigDecimal(decodeText(data.toString(), type, ctx));
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
    return decodeAsInt(new String(data, ctx.getCharset()), type, ctx);
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
    return decodeAsLong(new String(data, ctx.getCharset()), type, ctx);
  }
}
