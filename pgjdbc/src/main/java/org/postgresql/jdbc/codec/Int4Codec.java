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
 * Codec for PostgreSQL int4 (INTEGER) type.
 */
public final class Int4Codec implements PrimitiveBinaryEncoder, PrimitiveBinaryDecoder,
    PrimitiveTextEncoder, PrimitiveTextDecoder, ArrayElementCodec {

  public static final Int4Codec INSTANCE = new Int4Codec();

  private Int4Codec() {
    // Singleton
  }

  @Override
  public String getPrimaryTypeName() {
    return "int4";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is digits with an optional leading sign — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Integer.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return Int4ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 4) {
      throw Exceptions.invalidBinaryLength("int4", length);
    }
    return ByteConverter.int4(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    int v = toInt(value);
    byte[] result = new byte[4];
    ByteConverter.int4(result, 0, v);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    out.writeInt32(toInt(value));
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    TextSink.appendInt(out, toInt(value));
  }

  @Override
  public void encodeInt(int value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    out.writeInt32(value);
  }

  @Override
  public void encodeLong(long value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw Exceptions.outOfRange(value, "int4");
    }
    out.writeInt32((int) value);
  }

  @Override
  public void encodeInt(int value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    TextSink.appendInt(out, value);
  }

  @Override
  public void encodeLong(long value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw Exceptions.outOfRange(value, "int4");
    }
    TextSink.appendInt(out, (int) value);
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
          data, offset, length, Integer.MIN_VALUE, Integer.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // Anything the fast path rejects (a leading '+', whitespace, out-of-range)
      // falls back to the String parser, which owns the error message.
      return decodeText(new String(data, offset, length), type, ctx);
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return String.valueOf(toInt(value));
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 4) {
      throw Exceptions.invalidBinaryLength("int4", length);
    }
    return ByteConverter.int4(data, offset);
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return (int) NumberParser.getFastLong(
          data, 0, data.length(), Integer.MIN_VALUE, Integer.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // The fast path rejects a leading '+', whitespace, or an out-of-range value; fall back to the
      // String parser, which owns the parse and the error message. A String reaching here is copied
      // out once; a borrowed CharArraySequence slice parses in place on the fast path.
      // It also rejects a non-ASCII digit, which Integer.parseInt would otherwise accept, so screen for
      // that here rather than on the fast path, where a well-formed value would pay for the scan.
      String text = data.toString();
      try {
        NumberDecoders.requireAsciiLiteral(text);
        return Integer.parseInt(text.trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("int", text, e);
      }
    }
  }

  @Override
  public int decodeTextBytesAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (Encoding.hasAsciiNumbers(ctx.getCharset())) {
      try {
        return (int) NumberParser.getFastLong(data, Integer.MIN_VALUE, Integer.MAX_VALUE);
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
    return decodeIntAs(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    int value = decodeAsInt(data, type, ctx);
    return decodeIntAs(value, targetClass);
  }

  // int4's natural getObject type is Integer; resolve it directly so the common path does not widen,
  // and share the rarer coercions through NumberDecoders.
  @SuppressWarnings("unchecked")
  private static <T> T decodeIntAs(int value, Class<T> targetClass) throws SQLException {
    if (targetClass == Integer.class || targetClass == Object.class) {
      return (T) Integer.valueOf(value);
    }
    return NumberDecoders.decodeIntegralAs(value, targetClass, "int4");
  }

  static int toInt(Object value) throws SQLException {
    if (value instanceof Integer) {
      return (Integer) value;
    }
    if (value instanceof Number) {
      long asLong = ((Number) value).longValue();
      if (asLong < Integer.MIN_VALUE || asLong > Integer.MAX_VALUE) {
        throw Exceptions.outOfRange(value, "int4");
      }
      return (int) asLong;
    }
    if (value instanceof String) {
      try {
        NumberDecoders.requireAsciiLiteral((String) value);
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("int", value, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1 : 0;
    }
    throw Exceptions.cannotEncode(value, "int4");
  }
}
