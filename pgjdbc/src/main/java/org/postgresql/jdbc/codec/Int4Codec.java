/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Encoding;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.NumberParser;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL int4 (INTEGER) type.
 */
public final class Int4Codec implements StreamingBinaryCodec, StreamingTextCodec, ArrayElementCodec {

  public static final Int4Codec INSTANCE = new Int4Codec();

  private Int4Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
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
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid int4 binary data length: {0}", length),
          PSQLState.DATA_ERROR);
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
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx, OutputStream out)
      throws SQLException, IOException {
    if (out instanceof BackpatchingBinarySink) {
      ((BackpatchingBinarySink) out).writeInt32(toInt(value));
      return;
    }
    out.write(encodeBinary(value, type, ctx));
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    out.append(Integer.toString(toInt(value)));
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
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data.length != 4) {
      throw new PSQLException(
          GT.tr("Invalid int4 binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.int4(data, 0);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Integer.parseInt(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to int: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
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
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsInt(data, type, ctx));
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    int value = decodeAsInt(data, type, ctx);
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
        throw new PSQLException(
            GT.tr("Value {0} is out of int4 range", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (int) asLong;
    }
    if (value instanceof String) {
      try {
        return Integer.parseInt(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to int: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1 : 0;
    }
    throw Codec.cannotEncode(value, "int4");
  }
}
