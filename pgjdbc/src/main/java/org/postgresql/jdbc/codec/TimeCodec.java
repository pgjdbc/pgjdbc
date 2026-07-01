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
import org.postgresql.jdbc.TemporalCodecs;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.OffsetTime;

/**
 * Codec for PostgreSQL time (without time zone) type.
 */
public final class TimeCodec implements BinaryCodec, TextCodec {

  public static final TimeCodec INSTANCE = new TimeCodec();

  private TimeCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "time";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Time.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeBinary(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (ctx.prefersJavaTimeForTime()) {
      return TemporalCodecs.decodeLocalTimeBin(data, offset, length, ctx);
    }
    return TemporalCodecs.decodeTimeBin(data, offset, length, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TemporalCodecs.encodeTimeBin(value, ctx);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (ctx.prefersJavaTimeForTime()) {
      return TemporalCodecs.decodeLocalTimeText(data, ctx);
    }
    return TemporalCodecs.decodeTimeText(data, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Time) {
      return TemporalCodecs.formatTime((Time) value, ctx);
    }
    if (value instanceof LocalTime) {
      return TemporalCodecs.formatLocalTime((LocalTime) value, ctx);
    }
    if (value instanceof OffsetTime) {
      // Caller asked for TIME (no time zone) — strip the offset.
      return TemporalCodecs.formatLocalTime(((OffsetTime) value).toLocalTime(), ctx);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return TemporalCodecs.formatTime(new Time(time), ctx);
    }
    if (value instanceof String) {
      return TemporalCodecs.formatTime(TemporalCodecs.decodeTimeText((String) value, ctx), ctx);
    }
    throw Codec.cannotEncode(value, "time");
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Time.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == LocalTime.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == Timestamp.class) {
      // JDBC: getTimestamp on a TIME column anchors the time to 1970-01-01.
      Time t = TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx);
      if (t == null) {
        return null;
      }
      Timestamp r = new Timestamp(t.getTime());
      r.setNanos(TemporalCodecs.decodeTimestampBin(data, 0, data.length, false, ctx).getNanos());
      return targetClass.cast(r);
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == Long.class) {
      Time t = TemporalCodecs.decodeTimeBin(data, 0, data.length, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      LocalTime lt = TemporalCodecs.decodeLocalTimeBin(data, 0, data.length, ctx);
      return lt == null ? null : targetClass.cast(TemporalCodecs.formatLocalTime(lt, ctx));
    }
    throw Codec.cannotDecode("time", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Time.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeText(data, ctx));
    }
    if (targetClass == LocalTime.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalTimeText(data, ctx));
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
    throw Codec.cannotDecode("time", targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    LocalTime lt = TemporalCodecs.decodeLocalTimeBin(data, 0, data.length, ctx);
    return lt == null ? null : TemporalCodecs.formatLocalTime(lt, ctx);
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Preserve the original text (including microseconds) — java.sql.Time.toString()
    // would truncate the fractional part.
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert time to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert time to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

}
