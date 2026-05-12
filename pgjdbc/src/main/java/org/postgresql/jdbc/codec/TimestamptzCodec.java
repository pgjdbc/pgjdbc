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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * Codec for PostgreSQL timestamptz (timestamp with time zone) type.
 */
public final class TimestamptzCodec implements BinaryCodec, TextCodec {

  public static final TimestamptzCodec INSTANCE = new TimestamptzCodec();

  private TimestamptzCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "timestamptz";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Timestamp.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    // Check connection property for default type
    if (ctx.prefersJavaTimeForTimestamptz()) {
      return ts.toOffsetDateTimeBin(data);
    }
    return ts.toTimestampBin(null, data, true);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    String text = encodeText(value, type, ctx);
    return text.getBytes(ctx.getCharset());
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    // Check connection property for default type
    if (ctx.prefersJavaTimeForTimestamptz()) {
      return normalizeToUtc(ts.toOffsetDateTime(data));
    }
    return ts.toTimestamp(null, data);
  }

  /**
   * Normalizes OffsetDateTime to UTC, matching the binary format behavior.
   * Binary format always returns UTC (via toOffsetDateTimeBin), so text format
   * should be consistent.
   */
  private static @Nullable OffsetDateTime normalizeToUtc(@Nullable OffsetDateTime odt) {
    if (odt == null || odt.equals(OffsetDateTime.MAX) || odt.equals(OffsetDateTime.MIN)) {
      return odt;
    }
    return odt.withOffsetSameInstant(ZoneOffset.UTC);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (value instanceof Timestamp) {
      return ts.toString(null, (Timestamp) value);
    }
    if (value instanceof OffsetDateTime) {
      return ts.toString((OffsetDateTime) value);
    }
    if (value instanceof ZonedDateTime) {
      return ts.toString(((ZonedDateTime) value).toOffsetDateTime());
    }
    if (value instanceof Instant) {
      return ts.toString(((Instant) value).atOffset(ZoneOffset.UTC));
    }
    if (value instanceof LocalDateTime) {
      return ts.toString((LocalDateTime) value);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return ts.toString(null, new Timestamp(time));
    }
    if (value instanceof String) {
      return ts.toString(null, ts.toTimestamp(null, (String) value));
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to timestamptz", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Timestamp t = (Timestamp) decodeBinary(data, type, ctx);
    return t != null ? t.getTime() : 0;
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    Timestamp t = (Timestamp) decodeText(data, type, ctx);
    return t != null ? t.getTime() : 0;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (targetClass == Timestamp.class || targetClass == Object.class) {
      return (T) ts.toTimestampBin(null, data, true);
    }
    if (targetClass == OffsetDateTime.class) {
      return (T) ts.toOffsetDateTimeBin(data);
    }
    if (targetClass == ZonedDateTime.class) {
      // timestamptz is stored as UTC
      OffsetDateTime odt = ts.toOffsetDateTimeBin(data);
      return (T) odt.toZonedDateTime();
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = ts.toOffsetDateTimeBin(data);
      return (T) odt.toInstant();
    }
    // LocalDate / LocalTime / LocalDateTime are intentionally rejected — they
    // drop the time zone information that this column carries; the JDBC
    // contract surfaces that as DATA_TYPE_MISMATCH.
    if (targetClass == java.sql.Date.class) {
      return (T) ts.toDateBin(null, data);
    }
    if (targetClass == java.util.Date.class) {
      return (T) ts.toTimestampBin(null, data, true);
    }
    if (targetClass == Long.class) {
      Timestamp t = ts.toTimestampBin(null, data, true);
      return t == null ? null : (T) Long.valueOf(t.getTime());
    }
    if (targetClass == String.class) {
      return (T) ts.toStringOffsetDateTime(data);
    }
    throw new PSQLException(
        GT.tr("Cannot convert timestamptz to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (targetClass == Timestamp.class || targetClass == Object.class) {
      return (T) ts.toTimestamp(null, data);
    }
    if (targetClass == OffsetDateTime.class) {
      return (T) normalizeToUtc(ts.toOffsetDateTime(data));
    }
    if (targetClass == ZonedDateTime.class) {
      OffsetDateTime odt = normalizeToUtc(ts.toOffsetDateTime(data));
      return odt == null ? null : (T) odt.toZonedDateTime();
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = ts.toOffsetDateTime(data);
      return odt == null ? null : (T) odt.toInstant();
    }
    // LocalDate / LocalTime / LocalDateTime are intentionally rejected — they
    // drop the time zone information that this column carries.
    if (targetClass == java.sql.Date.class) {
      return (T) ts.toDate(null, data);
    }
    if (targetClass == java.util.Date.class) {
      return (T) ts.toTimestamp(null, data);
    }
    if (targetClass == Long.class) {
      Timestamp t = ts.toTimestamp(null, data);
      return t == null ? null : (T) Long.valueOf(t.getTime());
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert timestamptz to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    return ts.toStringOffsetDateTime(data);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert timestamptz to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert timestamptz to int"),
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
