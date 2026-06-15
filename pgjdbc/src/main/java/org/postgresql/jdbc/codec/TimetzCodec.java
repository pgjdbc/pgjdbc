/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TemporalCodecs;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;

/**
 * Codec for PostgreSQL timetz (time with time zone) type.
 */
public final class TimetzCodec implements BinaryCodec, TextCodec {

  public static final TimetzCodec INSTANCE = new TimetzCodec();

  private TimetzCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "timetz";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Time.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeBinary(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, PgType type,
      CodecContext ctx) throws SQLException {
    if (ctx.prefersJavaTimeForTimetz()) {
      return TemporalCodecs.decodeOffsetTimeBin(data, offset, length, ctx);
    }
    // timetz binary format is 12 bytes: 8 bytes for time + 4 bytes for timezone
    return TemporalCodecs.decodeTimeBin(data, offset, length, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    return TemporalCodecs.encodeTimetzBin(value, ctx);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    if (ctx.prefersJavaTimeForTimetz()) {
      return TemporalCodecs.decodeOffsetTimeText(data, ctx);
    }
    return TemporalCodecs.decodeTimeText(data, ctx);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof Time) {
      return TemporalCodecs.formatTime((Time) value, ctx);
    }
    if (value instanceof OffsetTime) {
      return TemporalCodecs.formatOffsetTime((OffsetTime) value, ctx);
    }
    if (value instanceof LocalTime) {
      return TemporalCodecs.formatLocalTime((LocalTime) value, ctx);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return TemporalCodecs.formatTime(new Time(time), ctx);
    }
    if (value instanceof String) {
      return TemporalCodecs.formatTime(TemporalCodecs.decodeTimeText((String) value, ctx), ctx);
    }
    throw Codec.cannotEncode(value, "timetz");
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Time t = (Time) decodeBinary(data, type, ctx);
    return t != null ? t.getTime() : 0;
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    Time t = (Time) decodeText(data, type, ctx);
    return t != null ? t.getTime() : 0;
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Time.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == OffsetTime.class) {
      return targetClass.cast(TemporalCodecs.decodeOffsetTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == OffsetDateTime.class) {
      // JDBC spec: timetz can be retrieved as OffsetDateTime with epoch date
      return targetClass.cast(
          TemporalCodecs.decodeOffsetTimeBin(data, 0, data.length, ctx).atDate(LocalDate.ofEpochDay(0)));
    }
    if (targetClass == Timestamp.class) {
      // JDBC: getTimestamp on a TIMETZ column anchors the time to 1970-01-01;
      // sub-second nanos come from the time-without-tz portion (first 8 bytes).
      Time t = TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx);
      if (t == null) {
        return null;
      }
      Timestamp r = new Timestamp(t.getTime());
      r.setNanos(TemporalCodecs.decodeTimestampBin(data, 0, 8, false, ctx).getNanos());
      return targetClass.cast(r);
    }
    // LocalTime / LocalDateTime / LocalDate are explicitly rejected per the
    // JDBC contract — they discard the time zone information that this column
    // carries. Fall through to the throw below.
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == Long.class) {
      Time t = TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      return targetClass.cast(TemporalCodecs.formatOffsetTimeBin(data, ctx));
    }
    throw Codec.cannotDecode("timetz", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Time.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeText(data, ctx));
    }
    if (targetClass == OffsetTime.class) {
      return targetClass.cast(TemporalCodecs.decodeOffsetTimeText(data, ctx));
    }
    if (targetClass == OffsetDateTime.class) {
      // JDBC spec: timetz can be retrieved as OffsetDateTime with epoch date
      OffsetTime ot = TemporalCodecs.decodeOffsetTimeText(data, ctx);
      return ot == null ? null : targetClass.cast(ot.atDate(LocalDate.ofEpochDay(0)));
    }
    if (targetClass == Timestamp.class) {
      Time t = TemporalCodecs.decodeTimeText(data, ctx);
      if (t == null) {
        return null;
      }
      Timestamp r = new Timestamp(t.getTime());
      r.setNanos(TemporalCodecs.decodeTimestampText(data, ctx).getNanos());
      return targetClass.cast(r);
    }
    // LocalTime / LocalDateTime / LocalDate are rejected — they drop the
    // time zone information that this column carries.
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeText(data, ctx));
    }
    if (targetClass == Long.class) {
      Time t = TemporalCodecs.decodeTimeText(data, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      return targetClass.cast(data);
    }
    throw Codec.cannotDecode("timetz", targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return TemporalCodecs.formatOffsetTimeBin(data, ctx);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert timetz to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert timetz to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsLong(data, type, ctx));
  }
}
