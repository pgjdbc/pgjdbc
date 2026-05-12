/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.ArrayDecoding;
import org.postgresql.jdbc.ArrayEncoding;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.CodecDepth;
import org.postgresql.jdbc.PgArray;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Array;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL array types.
 *
 * <p>This codec handles encoding and decoding of PostgreSQL arrays by delegating
 * to {@link ArrayEncoding} and {@link ArrayDecoding} utilities. For decoding,
 * it returns a {@link PgArray} that lazily decodes elements on access. For encoding,
 * it accepts both {@link Array} (PgArray) and raw Java array objects.</p>
 */
public final class ArrayCodec implements BinaryCodec, TextCodec {

  public static final ArrayCodec INSTANCE = new ArrayCodec();

  private ArrayCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "array";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Array.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    // Return a PgArray wrapping the binary data for lazy decoding
    BaseConnection conn = ctx.getConnection();
    return new PgArray(conn, type.getOid(), data);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof PgArray) {
      PgArray pgArray = (PgArray) value;
      if (pgArray.isBinary()) {
        byte[] bytes = pgArray.toBytes();
        if (bytes != null) {
          return bytes;
        }
      }
      // Text-mode PgArray: decode to Java array, then re-encode as binary
      Object javaArray = pgArray.getArray();
      if (javaArray != null) {
        return encodeBinaryJavaArray(javaArray, type, ctx);
      }
      return new byte[0];
    }
    if (value instanceof Array) {
      // Generic JDBC Array - get the underlying array and encode
      Object javaArray = ((Array) value).getArray();
      if (javaArray != null) {
        return encodeBinaryJavaArray(javaArray, type, ctx);
      }
      return new byte[0];
    }
    if (value.getClass().isArray()) {
      return encodeBinaryJavaArray(value, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @SuppressWarnings("unchecked")
  private byte[] encodeBinaryJavaArray(Object javaArray, PgType type, CodecContext ctx) throws SQLException {
    ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(javaArray);
    BaseConnection conn = ctx.getConnection();
    return encoder.toBinaryRepresentation(conn, javaArray, type.getOid());
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    // Return a PgArray wrapping the text data for lazy decoding
    BaseConnection conn = ctx.getConnection();
    return new PgArray(conn, type.getOid(), data);
  }

  @Override
  @SuppressWarnings("unchecked")
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof Array) {
      String str = value.toString();
      return str != null ? str : "NULL";
    }
    if (value.getClass().isArray()) {
      ArrayEncoding.ArrayEncoder<Object> encoder = ArrayEncoding.getArrayEncoder(value);
      return encoder.toArrayString(type.getDelimiter(), value);
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to array", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass.isArray()) {
      // Decode binary array to Java array, then the caller gets the typed array
      CodecDepth.enter();
      try {
        BaseConnection conn = ctx.getConnection();
        return (T) ArrayDecoding.readBinaryArray(1, 0, data, conn);
      } finally {
        CodecDepth.exit();
      }
    }
    throw new PSQLException(
        GT.tr("Cannot convert array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Array.class || targetClass == PgArray.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass.isArray()) {
      // Decode text array to Java array
      CodecDepth.enter();
      try {
        BaseConnection conn = ctx.getConnection();
        ArrayDecoding.PgArrayList arrayList = ArrayDecoding.buildArrayList(data, type.getDelimiter());
        return (T) ArrayDecoding.readStringArray(1, arrayList.size(), type.getTypelem(), arrayList, conn);
      } finally {
        CodecDepth.exit();
      }
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert array to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    // Preserve the PostgreSQL text representation (e.g. {{1,0},{0,1}});
    // PgArray.toString() would re-emit elements with quotes.
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("int");
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("int");
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("long");
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("long");
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("double");
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw cannotConvert("double");
  }

  private static PSQLException cannotConvert(String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert array to {0}", targetType),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
