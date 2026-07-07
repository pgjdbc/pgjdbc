/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.core.Oid;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL jsonb type.
 *
 * <p>Returns {@link PGobject} for getObject() (consistent with the legacy
 * driver and with master fix #3926); applications can extract the JSON text
 * via {@link PGobject#getValue()} or request String/byte[] explicitly through
 * {@code getObject(i, String.class)} / {@code getString(i)}.</p>
 *
 * <p>Binary format includes a version byte prefix (currently always 1).</p>
 */
public final class JsonbCodec implements BinaryCodec, TextCodec, ArrayElementCodec {

  public static final JsonbCodec INSTANCE = new JsonbCodec();

  /** JSONB binary format version (currently 1) */
  private static final byte JSONB_VERSION = 1;

  private static final JsonArrayLeafCodec ARRAY_LEAF = new JsonArrayLeafCodec(Oid.JSONB, INSTANCE);

  private JsonbCodec() {
  }

  @Override
  public String getTypeName() {
    return "jsonb";
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
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    // Skip version byte
    if (data.length < 1) {
      return wrap("");
    }
    return wrap(new String(data, 1, data.length - 1, StandardCharsets.UTF_8));
  }

  private static PGobject wrap(String value) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType("jsonb");
    obj.setValue(value);
    return obj;
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String str = value.toString();
    byte[] jsonBytes = str.getBytes(StandardCharsets.UTF_8);
    // Add version byte prefix
    byte[] result = new byte[jsonBytes.length + 1];
    result[0] = JSONB_VERSION;
    System.arraycopy(jsonBytes, 0, result, 1, jsonBytes.length);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return wrap(data);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    // Skip version byte
    if (data.length < 1) {
      return "";
    }
    return new String(data, 1, data.length - 1, StandardCharsets.UTF_8);
  }

  @Override
  public String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    String value = decodeAsString(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (targetClass == String.class) {
      return (T) value;
    }
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) wrap(value);
    }
    throw Codec.cannotDecode("jsonb", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
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
    throw Codec.cannotDecode("jsonb", targetClass.getName());
  }
}
