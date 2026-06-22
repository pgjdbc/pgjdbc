/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL json type.
 *
 * <p>Returns {@link PGobject} for getObject() (consistent with the legacy
 * driver and with master fix #3926); applications can extract the JSON text
 * via {@link PGobject#getValue()} or request String/byte[] explicitly through
 * {@code getObject(i, String.class)} / {@code getString(i)}.</p>
 */
public final class JsonCodec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final JsonCodec INSTANCE = new JsonCodec();

  private static final JsonArrayLeafCodec ARRAY_LEAF = new JsonArrayLeafCodec(Oid.JSON, INSTANCE);

  private JsonCodec() {
  }

  @Override
  public String getTypeName() {
    return "json";
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return ARRAY_LEAF;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGobject.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    return wrap(new String(data, StandardCharsets.UTF_8));
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    String str = value.toString();
    return str.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return wrap(data);
  }

  private static PGobject wrap(String value) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType("json");
    obj.setValue(value);
    return obj;
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    return new String(data, StandardCharsets.UTF_8);
  }

  @Override
  public String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert json to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert json to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert json to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert json to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert json to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert json to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    String value = new String(data, StandardCharsets.UTF_8);
    if (targetClass == String.class) {
      return (T) value;
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) wrap(value);
    }
    throw Codec.cannotDecode("json", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) wrap(data);
    }
    throw Codec.cannotDecode("json", targetClass.getName());
  }
}
