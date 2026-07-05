/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL float4 (REAL) type.
 */
public final class Float4Codec implements StreamingBinaryCodec, TextCodec, ArrayElementCodec {

  public static final Float4Codec INSTANCE = new Float4Codec();

  private Float4Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "float4";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is digits/sign/dot/e or NaN/Infinity — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Float.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return Float4ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid float4 binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.float4(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    float v = toFloat(value);
    byte[] result = new byte[4];
    ByteConverter.float4(result, 0, v);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    out.writeFloat(toFloat(value));
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return String.valueOf(toFloat(value));
  }

  @Override
  public float decodeAsFloat(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data.length != 4) {
      throw new PSQLException(
          GT.tr("Invalid float4 binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.float4(data, 0);
  }

  @Override
  public float decodeAsFloat(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Float.parseFloat(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to float: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
      throw new PSQLException(
          GT.tr("Value {0} is out of range for int", value),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
    }
    return (int) value;
  }

  @Override
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(encodeBinary(decodeAsFloat(data, type, ctx), type, ctx), type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    return decodeFloatAs(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    return decodeFloatAs(value, targetClass);
  }

  // float4's natural getObject type is Float; resolve it and Object directly so the common path does
  // not widen. String uses Float's narrower text form, and Long has a conservative bound because a
  // float cannot represent Long.MAX_VALUE exactly. The rest share NumberDecoders, widening to double.
  @SuppressWarnings("unchecked")
  private static <T> T decodeFloatAs(float value, Class<T> targetClass) throws SQLException {
    if (targetClass == Float.class || targetClass == Object.class) {
      return (T) Float.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Long.class) {
      if (value < Long.MIN_VALUE || value >= 9.223372036854776E18) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for long", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Long.valueOf((long) value);
    }
    return NumberDecoders.decodeFloatingAs(value, targetClass, "float4");
  }

  static float toFloat(Object value) throws SQLException {
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
    throw Codec.cannotEncode(value, "float4");
  }
}
