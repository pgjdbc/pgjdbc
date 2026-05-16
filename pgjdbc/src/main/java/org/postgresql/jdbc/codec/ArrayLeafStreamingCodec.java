/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.Array;
import java.sql.SQLException;

/**
 * Streaming array codec whose element-specific behavior is supplied by an
 * {@link ArrayLeafCodec}.
 */
public final class ArrayLeafStreamingCodec implements StreamingBinaryCodec, StreamingTextCodec {

  public static final ArrayLeafStreamingCodec INT4 =
      new ArrayLeafStreamingCodec("_int4", Integer[].class, Int4ArrayLeafCodec.INSTANCE);

  private final String typeName;
  private final Class<?> defaultJavaType;
  private final ArrayLeafCodec leaf;

  ArrayLeafStreamingCodec(String typeName, Class<?> defaultJavaType, ArrayLeafCodec leaf) {
    this.typeName = typeName;
    this.defaultJavaType = defaultJavaType;
    this.leaf = leaf;
  }

  @Override
  public String getTypeName() {
    return typeName;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return defaultJavaType;
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    Object javaArray = MultiDimArraySupport.unwrapArrayValue(value);
    if (javaArray == null) {
      return MultiDimArraySupport.zeroDimBinaryArray(leaf.getElementOid());
    }
    return MultiDimArrayBinary.encode(javaArray, ctx, leaf);
  }

  @Override
  public void encodeBinary(Object value, PgType type, CodecContext ctx, OutputStream out)
      throws SQLException, IOException {
    Object javaArray = MultiDimArraySupport.unwrapArrayValue(value);
    if (javaArray == null) {
      out.write(MultiDimArraySupport.zeroDimBinaryArray(leaf.getElementOid()));
      return;
    }
    writeBinaryArray(javaArray, out, ctx);
  }

  private void writeBinaryArray(Object javaArray, OutputStream out, CodecContext ctx)
      throws SQLException, IOException {
    if (out instanceof BackpatchingBinarySink) {
      MultiDimArrayBinary.encode(javaArray, (BackpatchingBinarySink) out, ctx, leaf);
      return;
    }
    out.write(MultiDimArrayBinary.encode(javaArray, ctx, leaf));
  }

  @Override
  public void encodeText(Object value, PgType type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    Object javaArray = MultiDimArraySupport.unwrapArrayValue(value);
    if (javaArray == null) {
      out.append("{}");
      return;
    }
    MultiDimArrayText.encode(javaArray, type.getDelimiter(), out, ctx, leaf);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx)
      throws SQLException {
    return new PgArray(ctx.getConnection(), type.getOid(), data);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx)
      throws SQLException {
    return new PgArray(ctx.getConnection(), type.getOid(), data);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass,
      CodecContext ctx) throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass.isArray()) {
      Class<?> leafComponentType = MultiDimArraySupport.leafComponentType(targetClass);
      if (leaf.supportsTargetComponent(leafComponentType)) {
        return (T) MultiDimArrayBinary.decode(data, leafComponentType, ctx, leaf);
      }
    }
    throw Codec.cannotConvert(type.getFullName(), targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass,
      CodecContext ctx) throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    PgArray pgArray = new PgArray(ctx.getConnection(), type.getOid(), data);
    Object javaArray = pgArray.getArray();
    if (javaArray == null) {
      return null;
    }
    if (targetClass.isInstance(javaArray)) {
      return targetClass.cast(javaArray);
    }
    throw Codec.cannotConvert(type.getFullName(), targetClass.getName());
  }
}
