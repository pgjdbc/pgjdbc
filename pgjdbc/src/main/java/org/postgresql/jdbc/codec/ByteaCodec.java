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
import org.postgresql.util.GT;
import org.postgresql.util.PGbytea;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Codec for PostgreSQL bytea type.
 */
public final class ByteaCodec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final ByteaCodec INSTANCE = new ByteaCodec();

  private ByteaCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "bytea";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return byte[].class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return ByteaArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsBytes(data, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toBytes(value);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PGbytea.toBytes(data.getBytes(ctx.getCharset()));
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    byte[] bytes = toBytes(value);
    return PGbytea.toPGString(bytes);
  }

  @Override
  public byte @Nullable [] decodeAsBytes(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Return a copy to prevent modification
    return Arrays.copyOf(data, data.length);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return PGbytea.toPGString(data);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == byte[].class || targetClass == Object.class) {
      return (T) decodeAsBytes(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, type, ctx);
    }
    if (targetClass == InputStream.class) {
      // Return InputStream wrapping the raw bytes
      return (T) new ByteArrayInputStream(data);
    }
    throw Codec.cannotDecode("bytea", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == byte[].class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    if (targetClass == InputStream.class) {
      // Decode text to bytes first, then wrap in InputStream
      byte[] bytes = PGbytea.toBytes(data.getBytes(ctx.getCharset()));
      return (T) new ByteArrayInputStream(bytes);
    }
    throw Codec.cannotDecode("bytea", targetClass.getName());
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert bytea to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert bytea to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert bytea to long"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert bytea to long"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert bytea to double"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert bytea to double"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  static byte[] toBytes(Object value) throws SQLException {
    if (value instanceof byte[]) {
      return (byte[]) value;
    }
    if (value instanceof String) {
      // PGbytea.toBytes accepts the ASCII-only hex / escape encoding produced by
      // PostgreSQL, so the platform default charset is irrelevant here.
      return PGbytea.toBytes(((String) value).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    throw Codec.cannotEncode(value, "bytea");
  }
}
