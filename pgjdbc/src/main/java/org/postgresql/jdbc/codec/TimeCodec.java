/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalTime;

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
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (ctx.prefersJavaTimeForTime()) {
      return ts.toLocalTimeBin(data);
    }
    return ts.toTimeBin(null, data);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    // Time encoding uses text format as there's no toBinTime in TimestampUtils
    // Binary format is rarely used for time without timezone
    String text = encodeText(value, type, ctx);
    return text.getBytes(ctx.getCharset());
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (ctx.prefersJavaTimeForTime()) {
      return ts.toLocalTime(data);
    }
    return ts.toTime(null, data);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (value instanceof Time) {
      return ts.toString(null, (Time) value);
    }
    if (value instanceof LocalTime) {
      return ts.toString((LocalTime) value);
    }
    if (value instanceof java.util.Date) {
      return ts.toString(null, new Time(((java.util.Date) value).getTime()));
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to time", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
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
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (targetClass == Time.class || targetClass == Object.class) {
      return (T) ts.toTimeBin(null, data);
    }
    if (targetClass == LocalTime.class) {
      return (T) ts.toLocalTimeBin(data);
    }
    if (targetClass == java.util.Date.class) {
      return (T) ts.toTimeBin(null, data);
    }
    if (targetClass == Long.class) {
      Time t = ts.toTimeBin(null, data);
      return t == null ? null : (T) Long.valueOf(t.getTime());
    }
    if (targetClass == String.class) {
      LocalTime lt = ts.toLocalTimeBin(data);
      return lt == null ? null : (T) ts.toString(lt);
    }
    throw new PSQLException(
        GT.tr("Cannot convert time to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (targetClass == Time.class || targetClass == Object.class) {
      return (T) ts.toTime(null, data);
    }
    if (targetClass == LocalTime.class) {
      return (T) ts.toLocalTime(data);
    }
    if (targetClass == java.util.Date.class) {
      return (T) ts.toTime(null, data);
    }
    if (targetClass == Long.class) {
      Time t = ts.toTime(null, data);
      return t == null ? null : (T) Long.valueOf(t.getTime());
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert time to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    LocalTime lt = ts.toLocalTimeBin(data);
    return lt == null ? null : ts.toString(lt);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert time to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert time to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
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
