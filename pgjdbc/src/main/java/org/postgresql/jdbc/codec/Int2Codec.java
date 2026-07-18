/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveBinaryEncoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.PrimitiveTextEncoder;
import org.postgresql.api.codec.TextSink;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Encoding;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.NumberParser;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL int2 (SMALLINT) type.
 *
 * <p>Note: getObject() returns Integer for backward compatibility,
 * not Short as might be expected.</p>
 */
public final class Int2Codec implements PrimitiveBinaryEncoder, PrimitiveBinaryDecoder,
    PrimitiveTextEncoder, PrimitiveTextDecoder, ArrayElementCodec {

  public static final Int2Codec INSTANCE = new Int2Codec();

  private Int2Codec() {
    // Singleton
  }

  @Override
  public String getPrimaryTypeName() {
    return "int2";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is digits with an optional leading sign — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    // Returns Integer for backward compatibility
    return Integer.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return Int2ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 2) {
      throw Exceptions.invalidBinaryLength("int2", length);
    }
    return (int) ByteConverter.int2(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    short v = toShort(value);
    byte[] result = new byte[2];
    ByteConverter.int2(result, 0, v);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    out.writeInt16(toShort(value));
  }

  @Override
  public void encodeInt(int value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    out.writeInt16(toShort(value));
  }

  @Override
  public void encodeLong(long value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    out.writeInt16(toShort(value));
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    try {
      return (int) NumberParser.getFastLong(
          data, offset, length, Short.MIN_VALUE, Short.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // Anything the fast path rejects (a leading '+', whitespace, out-of-range)
      // falls back to the String parser, which owns the error message.
      return decodeText(new String(data, offset, length), type, ctx);
    }
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    TextSink.appendInt(out, toShort(value));
  }

  @Override
  public void encodeInt(int value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    // writeShort/writeByte always fit; writeInt into an int2 field is range-checked like toShort.
    TextSink.appendInt(out, toShort(value));
  }

  @Override
  public void encodeLong(long value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    // writeShort/writeByte always fit; writeInt into an int2 field is range-checked like toShort.
    TextSink.appendInt(out, toShort(value));
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return String.valueOf(toShort(value));
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 2) {
      throw Exceptions.invalidBinaryLength("int2", length);
    }
    return ByteConverter.int2(data, offset);
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return (int) NumberParser.getFastLong(
          data, 0, data.length(), Short.MIN_VALUE, Short.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // The fast path rejects a leading '+', whitespace, or an out-of-range value; fall back to the
      // String parser, which owns the parse and the error message. It also rejects a non-ASCII digit,
      // which Short.parseShort would otherwise accept, so screen for that here rather than on the fast
      // path, where a well-formed value would pay for the scan.
      String text = data.toString();
      try {
        NumberDecoders.requireAsciiLiteral(text);
        return Short.parseShort(text.trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("short", text, e);
      }
    }
  }

  @Override
  public int decodeTextBytesAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (Encoding.hasAsciiNumbers(ctx.getCharset())) {
      try {
        return (int) NumberParser.getFastLong(data, Short.MIN_VALUE, Short.MAX_VALUE);
      } catch (NumberFormatException ignored) {
        // Fall through to string parsing
      }
    }
    return decodeAsInt(new String(data, ctx.getCharset()), type, ctx);
  }

  @Override
  public long decodeTextBytesAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeTextBytesAsInt(data, type, ctx);
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decodeAsInt(data, offset, length, type, ctx);
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decodeAsInt(data, offset, length, type, ctx);
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsInt(data, offset, length, type, ctx));
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    int value = decodeAsInt(data, offset, length, type, ctx);
    return decodeShortAs(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    int value = decodeAsInt(data, type, ctx);
    return decodeShortAs(value, targetClass);
  }

  // int2's natural getObject type is Integer (matching legacy smallint); resolve it directly so the
  // common path does not widen, and share the rarer coercions through NumberDecoders.
  @SuppressWarnings("unchecked")
  private static <T> T decodeShortAs(int value, Class<T> targetClass) throws SQLException {
    if (targetClass == Integer.class || targetClass == Object.class) {
      return (T) Integer.valueOf(value);
    }
    return NumberDecoders.decodeIntegralAs(value, targetClass, "int2");
  }

  static short toShort(int value) throws SQLException {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw Exceptions.outOfRange(value, "int2");
    }
    return (short) value;
  }

  static short toShort(long value) throws SQLException {
    if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
      throw Exceptions.outOfRange(value, "int2");
    }
    return (short) value;
  }

  static short toShort(Object value) throws SQLException {
    if (value instanceof Number) {
      return toShort(((Number) value).longValue());
    }
    if (value instanceof String) {
      try {
        NumberDecoders.requireAsciiLiteral((String) value);
        return Short.parseShort(((String) value).trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("short", value, e);
      }
    }
    if (value instanceof Boolean) {
      return (short) ((Boolean) value ? 1 : 0);
    }
    throw Exceptions.cannotEncode(value, "int2");
  }
}
