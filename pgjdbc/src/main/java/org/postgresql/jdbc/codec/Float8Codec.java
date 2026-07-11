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
import org.postgresql.util.ByteConverter;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL float8 (DOUBLE PRECISION) type.
 */
public final class Float8Codec implements PrimitiveBinaryEncoder, PrimitiveBinaryDecoder,
    PrimitiveTextEncoder, PrimitiveTextDecoder, ArrayElementCodec {

  public static final Float8Codec INSTANCE = new Float8Codec();

  private Float8Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "float8";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is digits/sign/dot/e or NaN/Infinity — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Double.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return Float8ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 8) {
      throw Exceptions.invalidBinaryLength("float8", length);
    }
    return ByteConverter.float8(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    double v = toDouble(value);
    byte[] result = new byte[8];
    ByteConverter.float8(result, 0, v);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    out.writeDouble(toDouble(value));
  }

  @Override
  public void encodeDouble(double value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    out.writeDouble(value);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsDouble(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return String.valueOf(toDouble(value));
  }

  @Override
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    TextSink.appendDouble(out, toDouble(value));
  }

  @Override
  public void encodeDouble(double value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    TextSink.appendDouble(out, value);
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 8) {
      throw Exceptions.invalidBinaryLength("float8", length);
    }
    return ByteConverter.float8(data, offset);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Double.parseDouble(data.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("double", data, e);
    }
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return (float) decodeAsDouble(data, offset, length, type, ctx);
  }

  @Override
  public float decodeAsFloat(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return (float) decodeAsDouble(data, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return NumberDecoders.floatingToInt(decodeAsDouble(data, offset, length, type, ctx));
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return NumberDecoders.floatingToInt(decodeAsDouble(data, type, ctx));
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return NumberDecoders.floatingToLong(decodeAsDouble(data, offset, length, type, ctx));
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return NumberDecoders.floatingToLong(decodeAsDouble(data, type, ctx));
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    double value = decodeAsDouble(data, offset, length, type, ctx);
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      throw Exceptions.cannotConvertToBigDecimal(value);
    }
    return BigDecimal.valueOf(value);
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    double value = decodeAsDouble(data, offset, length, type, ctx);
    return decodeDoubleAs(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    double value = decodeAsDouble(data, type, ctx);
    return decodeDoubleAs(value, targetClass);
  }

  // float8's natural getObject type is Double (and the value is already a double); resolve it and
  // Object directly. String uses Double's text form, and Long is range-checked separately because
  // decodeFloatingAs has no Long branch. The rest share NumberDecoders.
  @SuppressWarnings("unchecked")
  private static <T> T decodeDoubleAs(double value, Class<T> targetClass) throws SQLException {
    if (targetClass == Double.class || targetClass == Object.class) {
      return (T) Double.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(NumberDecoders.floatingToLong(value));
    }
    return NumberDecoders.decodeFloatingAs(value, targetClass, "float8");
  }

  static double toDouble(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    if (value instanceof String) {
      try {
        return Double.parseDouble(((String) value).trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("double", value, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1.0 : 0.0;
    }
    throw Exceptions.cannotEncode(value, "float8");
  }
}
