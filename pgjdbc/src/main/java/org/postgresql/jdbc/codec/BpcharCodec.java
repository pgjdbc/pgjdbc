/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL bpchar (CHARACTER) type.
 *
 * <p>bpchar is a blank-padded fixed-length character type.
 * Delegates to {@link TextCodecImpl} for all operations.</p>
 */
public final class BpcharCodec implements BinaryCodec, TextCodec {

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
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeBinary(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeBinary(data, offset, length, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.encodeBinary(value, type, ctx);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeText(data, type, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.encodeText(value, type, ctx);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsString(data, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsInt(data, type, ctx);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsInt(data, type, ctx);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsLong(data, type, ctx);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsLong(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsDouble(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsDouble(data, type, ctx);
  }

  @Override
  public float decodeAsFloat(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsFloat(data, type, ctx);
  }

  @Override
  public float decodeAsFloat(String data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsFloat(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsBigDecimal(data, type, ctx);
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsBoolean(data, type, ctx);
  }

  @Override
  public boolean decodeAsBoolean(String data, PgType type, CodecContext ctx) throws SQLException {
    return TextCodecImpl.INSTANCE.decodeAsBoolean(data, type, ctx);
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeBinaryAs(data, type, targetClass, ctx);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    return TextCodecImpl.INSTANCE.decodeTextAs(data, type, targetClass, ctx);
  }
}
