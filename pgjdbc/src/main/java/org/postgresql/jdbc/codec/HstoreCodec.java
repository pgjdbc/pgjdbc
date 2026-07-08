/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.util.GT;
import org.postgresql.util.HStoreConverter;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;

/**
 * Codec for PostgreSQL hstore type.
 *
 * <p>hstore is a key/value store within a single PostgreSQL value. It stores
 * sets of key/value pairs, where both keys and values are text strings.</p>
 *
 * <p>Text format: "key1"=>"value1", "key2"=>"value2", "nullkey"=>NULL</p>
 *
 * <p>Binary format: int32 count, followed by pairs of (int32 keyLen, key bytes,
 * int32 valLen, val bytes) where valLen=-1 indicates NULL value.</p>
 */
public final class HstoreCodec implements BinaryCodec, TextCodec {

  public static final HstoreCodec INSTANCE = new HstoreCodec();

  private HstoreCodec() {
    // Singleton
  }

  // Wire-encoding access (not child-resolve): hstore reaches the connection Encoding through the
  // implementation for HStoreConverter. Exposing the wire encoding on the CodecContext interface is
  // a separate slice-2 follow-up; slice 2c moved only child-type resolution onto the interface.
  private static PgCodecContext impl(CodecContext ctx) {
    return (PgCodecContext) ctx;
  }

  @Override
  public String getTypeName() {
    return "hstore";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Map.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] buf, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length == 0) {
      return null;
    }
    // HStoreConverter.fromBytes reads a whole array; copy only for a genuine sub-slice.
    byte[] data = offset == 0 && length == buf.length ? buf : Arrays.copyOfRange(buf, offset, offset + length);
    return HStoreConverter.fromBytes(data, impl(ctx).getEncoding());
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Map) {
      return HStoreConverter.toBytes((Map<?, ?>) value, impl(ctx).getEncoding());
    }
    throw new PSQLException(GT.tr("Cannot encode {0} as hstore", value.getClass().getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    return HStoreConverter.fromString(data);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Map) {
      return HStoreConverter.toString((Map<?, ?>) value);
    }
    throw new PSQLException(GT.tr("Cannot encode {0} as hstore", value.getClass().getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == Map.class || targetClass == Object.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot decode hstore to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Map.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot decode hstore to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert hstore to BigDecimal"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert hstore to BigDecimal"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    Object map = decodeBinary(data, offset, length, type, ctx);
    return map != null ? HStoreConverter.toString((Map<?, ?>) map) : null;
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }
}
