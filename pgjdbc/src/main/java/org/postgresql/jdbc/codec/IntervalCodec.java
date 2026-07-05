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
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data.length == 0) {
      return null;
    }
    return decodeBinary(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 16) {
      throw new PSQLException(
          GT.tr("Invalid interval binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }

    // Binary format: 8 bytes microseconds, 4 bytes days, 4 bytes months
    long microseconds = ByteConverter.int8(data, offset);
    int days = ByteConverter.int4(data, offset + 8);
    int months = ByteConverter.int4(data, offset + 12);

    // Convert to PGInterval components. Split the whole microseconds in long arithmetic so the
    // hours * 3600 term cannot overflow int (it does past ~596_523 hours, corrupting the minutes), then
    // fold the sub-second remainder back into seconds as a double.
    int years = months / 12;
    months = months % 12;

    long totalSeconds = microseconds / 1_000_000L;
    long fractionMicros = microseconds % 1_000_000L;
    int hours = (int) (totalSeconds / 3600);
    long afterHours = totalSeconds - (long) hours * 3600;
    int minutes = (int) (afterHours / 60);
    double seconds = (afterHours - (long) minutes * 60) + fractionMicros / 1_000_000.0;

    return new PGInterval(years, months, days, hours, minutes, seconds);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
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

    // Convert to binary format. Keep the hours and minutes terms in long arithmetic (they are exact),
    // and round the fractional seconds to the nearest microsecond rather than truncating: the seconds
    // are a double, so (long) (seconds * 1e6) drops the last microsecond on most values (0.999999 s is
    // 999998.999... as a double and truncates to 999998).
    int months = interval.getYears() * 12 + interval.getMonths();
    int days = interval.getDays();
    long microseconds = interval.getHours() * 3600_000_000L
        + interval.getMinutes() * 60_000_000L
        + Math.round(interval.getSeconds() * 1_000_000.0);

    byte[] result = new byte[16];
    ByteConverter.int8(result, 0, microseconds);
    ByteConverter.int4(result, 8, days);
    ByteConverter.int4(result, 12, months);
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (data == null || data.isEmpty()) {
      return null;
    }
    return new PGInterval(data);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGInterval) {
      String text = ((PGInterval) value).getValue();
      return text != null ? text : "";
    }
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    Object interval = decodeBinary(data, type, ctx);
    return interval != null ? interval.toString() : null;
  }

  @Override
  public String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to int"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to long"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(GT.tr("Cannot convert interval to double"), PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
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
    throw Codec.cannotDecode("interval", targetClass.getName());
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
    PGInterval interval = new PGInterval(data);
    if (targetClass == PGInterval.class || targetClass == Object.class) {
      return (T) interval;
    }
    throw Codec.cannotDecode("interval", targetClass.getName());
  }
}
