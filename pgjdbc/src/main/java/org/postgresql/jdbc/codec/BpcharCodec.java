/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL bpchar (CHARACTER) type.
 *
 * <p>bpchar is a blank-padded fixed-length character type.
 * Delegates to {@link TextCodecImpl} for all operations.</p>
 */
public final class BpcharCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder {

  public static final BpcharCodec INSTANCE = new BpcharCodec();

  private BpcharCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "bpchar";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return String.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeBinary(data, offset, length, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.encodeBinary(value, type, ctx);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeText(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.encodeText(value, type, ctx);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsString(data, offset, length, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsInt(data, offset, length, type, ctx);
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsInt(data, type, ctx);
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsLong(data, offset, length, type, ctx);
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsLong(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsDouble(data, offset, length, type, ctx);
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsDouble(data, type, ctx);
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsFloat(data, offset, length, type, ctx);
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsFloat(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsBigDecimal(data, offset, length, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsBigDecimal(data, type, ctx);
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsBoolean(data, offset, length, type, ctx);
  }

  @Override
  public boolean decodeAsBoolean(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsBoolean(data, type, ctx);
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeBinaryAs(data, offset, length, type, targetClass, ctx);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeTextAs(data, type, targetClass, ctx);
  }
}
