/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.NumberParser;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL int2 (SMALLINT) type.
 *
 * <p>Note: getObject() returns Integer for backward compatibility,
 * not Short as might be expected.</p>
 */
public final class Int2Codec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final Int2Codec INSTANCE = new Int2Codec();

  private Int2Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
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
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Return Integer for backward compatibility
    return decodeAsInt(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 2) {
      throw new PSQLException(
          GT.tr("Invalid int2 binary data length: {0}", length),
          PSQLState.DATA_ERROR);
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
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return String.valueOf(toShort(value));
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data.length != 2) {
      throw new PSQLException(
          GT.tr("Invalid int2 binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.int2(data, 0);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Short.parseShort(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to short: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
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
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    int value = decodeAsInt(data, type, ctx);
    if (targetClass == Integer.class || targetClass == Object.class) {
      return (T) Integer.valueOf(value);
    }
    if (targetClass == Short.class) {
      return (T) Short.valueOf((short) value);
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(value);
    }
    if (targetClass == Byte.class) {
      if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for byte", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Byte.valueOf((byte) value);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(value);
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf(value);
    }
    if (targetClass == BigDecimal.class) {
      return (T) BigDecimal.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw Codec.cannotDecode("int2", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    int value = decodeAsInt(data, type, ctx);
    byte[] bytes = new byte[2];
    ByteConverter.int2(bytes, 0, (short) value);
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  static short toShort(Object value) throws SQLException {
    if (value instanceof Number) {
      long longValue = ((Number) value).longValue();
      if (longValue < Short.MIN_VALUE || longValue > Short.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for int2", longValue),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (short) longValue;
    }
    if (value instanceof String) {
      try {
        return Short.parseShort(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to short: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return (short) ((Boolean) value ? 1 : 0);
    }
    throw Codec.cannotEncode(value, "int2");
  }
}
