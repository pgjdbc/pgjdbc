/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.UUID;

/**
 * Codec for PostgreSQL uuid type.
 */
public final class UuidCodec implements BinaryCodec, TextCodec {

  public static final UuidCodec INSTANCE = new UuidCodec();

  private UuidCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "uuid";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return UUID.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data.length != 16) {
      throw new PSQLException(
          GT.tr("Invalid uuid binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }
    long msb = ByteConverter.int8(data, 0);
    long lsb = ByteConverter.int8(data, 8);
    return new UUID(msb, lsb);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    if (length != 16) {
      throw new PSQLException(
          GT.tr("Invalid uuid binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    long msb = ByteConverter.int8(data, offset);
    long lsb = ByteConverter.int8(data, offset + 8);
    return new UUID(msb, lsb);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    UUID uuid = toUuid(value);
    byte[] result = new byte[16];
    ByteConverter.int8(result, 0, uuid.getMostSignificantBits());
    ByteConverter.int8(result, 8, uuid.getLeastSignificantBits());
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return UUID.fromString(data.trim());
    } catch (IllegalArgumentException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to UUID: {0}", data),
          PSQLState.DATA_ERROR, e);
    }
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    UUID uuid = toUuid(value);
    return uuid.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    UUID uuid = (UUID) decodeBinary(data, type, ctx);
    return uuid != null ? uuid.toString() : null;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == UUID.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, type, ctx);
    }
    throw new PSQLException(
        GT.tr("Cannot convert uuid to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == UUID.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert uuid to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert uuid to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert uuid to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert uuid to long"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert uuid to long"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert uuid to double"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert uuid to double"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  private UUID toUuid(Object value) throws SQLException {
    if (value instanceof UUID) {
      return (UUID) value;
    }
    if (value instanceof String) {
      try {
        return UUID.fromString(((String) value).trim());
      } catch (IllegalArgumentException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to UUID: {0}", value),
            PSQLState.DATA_ERROR, e);
      }
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to uuid", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
