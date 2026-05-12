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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Codec for PostgreSQL timestamp (without time zone) type.
 */
public final class TimestampCodec implements BinaryCodec, TextCodec {

  public static final TimestampCodec INSTANCE = new TimestampCodec();

  private TimestampCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "timestamp";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Timestamp.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    // Check connection property for default type
    if (ctx.prefersJavaTimeForTimestamp()) {
      return ts.toLocalDateTimeBin(data);
    }
    return ts.toTimestampBin(null, data, false);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    // Timestamp encoding uses text format as binary encoding is complex
    String text = encodeText(value, type, ctx);
    return text.getBytes(ctx.getCharset());
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    // Check connection property for default type
    if (ctx.prefersJavaTimeForTimestamp()) {
      return ts.toLocalDateTime(data);
    }
    return ts.toTimestamp(null, data);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (value instanceof Timestamp) {
      return ts.toString(null, (Timestamp) value);
    }
    if (value instanceof LocalDateTime) {
      return ts.toString((LocalDateTime) value);
    }
    if (value instanceof OffsetDateTime) {
      return ts.toString(((OffsetDateTime) value).toLocalDateTime());
    }
    if (value instanceof ZonedDateTime) {
      return ts.toString(((ZonedDateTime) value).toLocalDateTime());
    }
    if (value instanceof Instant) {
      return ts.toString(null, Timestamp.from((Instant) value));
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return ts.toString(null, new Timestamp(time));
    }
    if (value instanceof String) {
      // setObject(i, "2024-01-01 12:00:00", Types.TIMESTAMP) and friends.
      return ts.toString(null, ts.toTimestamp(null, (String) value));
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to timestamp", value.getClass().getName()),
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
      return (T) ts.toTimestampBin(null, data, false);
    }
    if (targetClass == LocalDateTime.class) {
      return (T) ts.toLocalDateTimeBin(data);
    }
    if (targetClass == LocalDate.class) {
      LocalDateTime ldt = ts.toLocalDateTimeBin(data);
      return (T) ldt.toLocalDate();
    }
    if (targetClass == OffsetDateTime.class) {
      // timestamp (no tz) interpreted in UTC
      return (T) ts.toOffsetDateTimeBin(data);
    }
    if (targetClass == ZonedDateTime.class) {
      // timestamp (no tz) interpreted in UTC
      OffsetDateTime odt = ts.toOffsetDateTimeBin(data);
      return (T) odt.toZonedDateTime();
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = ts.toOffsetDateTimeBin(data);
      return (T) odt.toInstant();
    }
    if (targetClass == java.sql.Date.class) {
      return (T) ts.toDateBin(null, data);
    }
    if (targetClass == java.util.Date.class) {
      return (T) ts.toTimestampBin(null, data, false);
    }
    if (targetClass == Long.class) {
      Timestamp t = ts.toTimestampBin(null, data, false);
      return t == null ? null : (T) Long.valueOf(t.getTime());
    }
    if (targetClass == String.class) {
      LocalDateTime ldt = ts.toLocalDateTimeBin(data);
      return ldt == null ? null : (T) ts.toString(ldt);
    }
    throw new PSQLException(
        GT.tr("Cannot convert timestamp to {0}", targetClass.getName()),
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
    if (targetClass == LocalDateTime.class) {
      return (T) ts.toLocalDateTime(data);
    }
    if (targetClass == LocalDate.class) {
      LocalDateTime ldt = ts.toLocalDateTime(data);
      return ldt == null ? null : (T) ldt.toLocalDate();
    }
    if (targetClass == OffsetDateTime.class) {
      // timestamp (no tz) - parse and interpret in UTC
      return (T) ts.toOffsetDateTime(data);
    }
    if (targetClass == ZonedDateTime.class) {
      OffsetDateTime odt = ts.toOffsetDateTime(data);
      return odt == null ? null : (T) odt.toZonedDateTime();
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = ts.toOffsetDateTime(data);
      return odt == null ? null : (T) odt.toInstant();
    }
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
        GT.tr("Cannot convert timestamp to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    LocalDateTime ldt = ts.toLocalDateTimeBin(data);
    return ldt == null ? null : ts.toString(ldt);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    // Preserve the original text (with microsecond precision).
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert timestamp to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert timestamp to int"),
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
