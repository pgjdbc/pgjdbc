/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL float4 (REAL) type.
 */
public final class Float4Codec implements BinaryCodec, TextCodec {

  public static final Float4Codec INSTANCE = new Float4Codec();

  private Float4Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "float4";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Float.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid float4 binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.float4(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    float v = toFloat(value);
    byte[] result = new byte[4];
    ByteConverter.float4(result, 0, v);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return String.valueOf(toFloat(value));
  }

  @Override
  public float decodeAsFloat(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 4) {
      throw new PSQLException(
          GT.tr("Invalid float4 binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.float4(data, 0);
  }

  @Override
  public float decodeAsFloat(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Float.parseFloat(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to float: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    // Check bounds for float to long conversion
    // Float can't exactly represent Long.MAX_VALUE, so we use a conservative bound
    if (value < Long.MIN_VALUE || value >= 9.223372036854776E18) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for long", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (long) value;
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(encodeBinary(decodeAsFloat(data, type, ctx), type, ctx), type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    if (Float.isNaN(value) || Float.isInfinite(value)) {
      throw new PSQLException(
          GT.tr("Cannot convert {0} to BigDecimal", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return BigDecimal.valueOf(value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    if (targetClass == Float.class || targetClass == Object.class) {
      return (T) Float.valueOf(value);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(value);
    }
    if (targetClass == Integer.class) {
      if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for int", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Integer.valueOf((int) value);
    }
    if (targetClass == Long.class) {
      if (value < Long.MIN_VALUE || value >= 9.223372036854776E18) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for long", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Long.valueOf((long) value);
    }
    if (targetClass == Short.class) {
      if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for short", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Short.valueOf((short) value);
    }
    if (targetClass == Byte.class) {
      if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for byte", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Byte.valueOf((byte) value);
    }
    if (targetClass == BigDecimal.class) {
      if (Float.isNaN(value) || Float.isInfinite(value)) {
        throw new PSQLException(
            GT.tr("Cannot convert {0} to BigDecimal", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) BigDecimal.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(value != 0);
    }
    throw new PSQLException(
        GT.tr("Cannot convert float4 to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    byte[] bytes = new byte[4];
    ByteConverter.float4(bytes, 0, value);
    return decodeBinaryAs(bytes, type, targetClass, ctx);
  }

  private float toFloat(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).floatValue();
    }
    if (value instanceof String) {
      try {
        return Float.parseFloat(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to float: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1.0f : 0.0f;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to float4", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
