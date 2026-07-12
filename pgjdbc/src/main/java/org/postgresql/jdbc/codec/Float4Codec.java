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
 * Codec for PostgreSQL float4 (REAL) type.
 */
public final class Float4Codec implements PrimitiveBinaryEncoder, PrimitiveBinaryDecoder,
    PrimitiveTextEncoder, PrimitiveTextDecoder, ArrayElementCodec {

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
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 4) {
      throw Exceptions.invalidBinaryLength("float4", length);
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
  public void encodeFloat(float value, TypeDescriptor type, CodecContext ctx, BackpatchingBinarySink out)
      throws SQLException, IOException {
    out.writeFloat(value);
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
  public void encodeText(Object value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    TextSink.appendFloat(out, toFloat(value));
  }

  @Override
  public void encodeFloat(float value, TypeDescriptor type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    TextSink.appendFloat(out, value);
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 4) {
      throw Exceptions.invalidBinaryLength("float4", length);
    }
    return ByteConverter.float4(data, offset);
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String text = data.toString();
    try {
      return Float.parseFloat(text.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("float", text, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decodeAsFloat(data, offset, length, type, ctx);
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsFloat(data, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    // Widen to double first: (float) Integer.MAX_VALUE rounds up to 2^31, so a float-space bound
    // check would accept the over-range 2^31 and clamp it. The double bounds are exact.
    return NumberDecoders.floatingToInt(decodeAsFloat(data, offset, length, type, ctx));
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return NumberDecoders.floatingToInt(decodeAsFloat(data, type, ctx));
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return NumberDecoders.floatingToLong(decodeAsFloat(data, offset, length, type, ctx));
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return NumberDecoders.floatingToLong(decodeAsFloat(data, type, ctx));
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, offset, length, type, ctx);
    if (Float.isNaN(value) || Float.isInfinite(value)) {
      throw Exceptions.cannotConvertToBigDecimal(value);
    }
    return BigDecimal.valueOf(value);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    float value = decodeAsFloat(data, offset, length, type, ctx);
    return decodeFloatAs(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    float value = decodeAsFloat(data, type, ctx);
    return decodeFloatAs(value, targetClass);
  }

  // float4's natural getObject type is Float; resolve it and Object directly so the common path does
  // not widen. String uses Float's narrower text form, and Long is range-checked separately because
  // decodeFloatingAs has no Long branch. The rest share NumberDecoders, widening to double.
  @SuppressWarnings("unchecked")
  private static <T> T decodeFloatAs(float value, Class<T> targetClass) throws SQLException {
    if (targetClass == Float.class || targetClass == Object.class) {
      return (T) Float.valueOf(value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(NumberDecoders.floatingToLong(value));
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
        throw Exceptions.cannotConvertValue("float", value, e);
      }
    }
    if (value instanceof Boolean) {
      return (Boolean) value ? 1.0f : 0.0f;
    }
    throw Exceptions.cannotEncode(value, "float4");
  }
}
