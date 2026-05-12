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
import org.postgresql.util.PGInterval;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Codec for PostgreSQL interval type.
 *
 * <p>Returns {@link PGInterval} for getObject().</p>
 *
 * <p>Binary format is: 8 bytes (microseconds), 4 bytes (days), 4 bytes (months).</p>
 */
public final class IntervalCodec implements BinaryCodec, TextCodec {

  public static final IntervalCodec INSTANCE = new IntervalCodec();

  private IntervalCodec() {
  }

  @Override
  public String getTypeName() {
    return "interval";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGInterval.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    if (data.length != 16) {
      throw new PSQLException(
          GT.tr("Invalid interval binary data length: {0}", data.length),
          PSQLState.DATA_ERROR);
    }

    // Binary format: 8 bytes microseconds, 4 bytes days, 4 bytes months
    long microseconds = ByteConverter.int8(data, 0);
    int days = ByteConverter.int4(data, 8);
    int months = ByteConverter.int4(data, 12);

    // Convert to PGInterval components
    int years = months / 12;
    months = months % 12;

    double seconds = microseconds / 1_000_000.0;
    int hours = (int) (seconds / 3600);
    seconds -= hours * 3600;
    int minutes = (int) (seconds / 60);
    seconds -= minutes * 60;

    return new PGInterval(years, months, days, hours, minutes, seconds);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    PGInterval interval;
    if (value instanceof PGInterval) {
      interval = (PGInterval) value;
    } else if (value instanceof String) {
      interval = new PGInterval((String) value);
    } else {
      throw new PSQLException(
          GT.tr("Cannot encode {0} as interval", value.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }

    // Convert to binary format
    int months = interval.getYears() * 12 + interval.getMonths();
    int days = interval.getDays();
    long microseconds = (long) (interval.getHours() * 3600_000_000L
        + interval.getMinutes() * 60_000_000L
        + interval.getSeconds() * 1_000_000);

    byte[] result = new byte[16];
    ByteConverter.int8(result, 0, microseconds);
    ByteConverter.int4(result, 8, days);
    ByteConverter.int4(result, 12, months);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    return new PGInterval(data);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof PGInterval) {
      String text = ((PGInterval) value).getValue();
      return text != null ? text : "";
    }
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object interval = decodeBinary(data, type, ctx);
    return interval != null ? interval.toString() : null;
  }

  @Override
  public String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to int"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to int"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to long"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to long"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to double"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to double"), PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (data == null || data.length == 0) {
      return null;
    }
    PGInterval interval = (PGInterval) decodeBinary(data, type, ctx);
    if (targetClass == PGInterval.class || targetClass == Object.class) {
      return (T) interval;
    }
    if (targetClass == String.class) {
      return (T) (interval != null ? interval.getValue() : null);
    }
    throw new PSQLException(
        GT.tr("Cannot convert interval to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
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
    PGInterval interval = new PGInterval(data);
    if (targetClass == PGInterval.class || targetClass == Object.class) {
      return (T) interval;
    }
    throw new PSQLException(
        GT.tr("Cannot convert interval to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
