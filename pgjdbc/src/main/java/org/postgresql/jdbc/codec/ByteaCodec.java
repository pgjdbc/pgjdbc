/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.PGbytea;

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
  public String getPrimaryTypeName() {
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
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return decodeAsBytes(data, offset, length, type, ctx);
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
  public byte @Nullable [] decodeAsBytes(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Return a copy to prevent modification; the value owns its bytes.
    return Arrays.copyOfRange(data, offset, offset + length);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // PGbytea.toPGString hex-encodes a whole array; copy only for a genuine sub-slice.
    byte[] bytes = offset == 0 && length == data.length ? data : Arrays.copyOfRange(data, offset, offset + length);
    return PGbytea.toPGString(bytes);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == byte[].class || targetClass == Object.class) {
      return (T) decodeAsBytes(data, offset, length, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, offset, length, type, ctx);
    }
    if (targetClass == InputStream.class) {
      // Return InputStream wrapping the raw bytes of this value's slice.
      return (T) new ByteArrayInputStream(data, offset, length);
    }
    throw Exceptions.cannotDecode("bytea", targetClass.getName());
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
    throw Exceptions.cannotDecode("bytea", targetClass.getName());
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
    throw Exceptions.cannotEncode(value, "bytea");
  }
}
