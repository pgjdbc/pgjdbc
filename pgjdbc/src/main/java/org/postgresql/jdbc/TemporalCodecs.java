/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.PGStatement;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * Codec-facing facade over the temporal math in {@link TimestampUtils}.
 *
 * <p>The codecs depend on this class instead of {@code TimestampUtils} so the public codec API never
 * hands out the connection's temporal engine. Each method is keyed off a {@link CodecContext}: the
 * {@code usesDouble} flag, the decode timezone (the per-call {@link Calendar}'s zone set by
 * {@code getDate/getTime/getTimestamp(col, Calendar)}, else the JVM default) and the client timezone
 * all come from the context. The bodies forward to the stateless {@code static} conversions in
 * {@link TimestampUtils}, allocating fresh scratch per call so the codecs stay stateless.</p>
 */
public final class TemporalCodecs {

  private TemporalCodecs() {
    // Static utility
  }

  /** The timezone to decode in: the per-call Calendar's zone, else the context default. */
  private static TimeZone decodeTz(CodecContext ctx) {
    Calendar cal = ctx.getCalendar();
    return cal != null ? cal.getTimeZone() : ctx.getDefaultTimeZone();
  }

  // ----------------------------- binary decode -----------------------------

  public static Date decodeDateBin(byte[] data, int off, int len, CodecContext ctx)
      throws SQLException {
    return TimestampUtils.toDateBin(decodeTz(ctx), null, data, off, len);
  }

  public static LocalDate decodeLocalDateBin(byte[] data, int off, int len, CodecContext ctx)
      throws SQLException {
    return TimestampUtils.parseLocalDateBin(data, off, len);
  }

  public static Time decodeTimeBin(byte[] data, int off, int len, CodecContext ctx)
      throws SQLException {
    return TimestampUtils.toTimeBin(ctx.usesDoubleDateTime(), decodeTz(ctx), null, data, off, len);
  }

  public static LocalTime decodeLocalTimeBin(byte[] data, int off, int len, CodecContext ctx)
      throws SQLException {
    return TimestampUtils.toLocalTimeBin(ctx.usesDoubleDateTime(), data, off, len);
  }

  public static OffsetTime decodeOffsetTimeBin(byte[] data, int off, int len, CodecContext ctx)
      throws SQLException {
    return TimestampUtils.toOffsetTimeBin(ctx.usesDoubleDateTime(), data, off, len);
  }

  public static Timestamp decodeTimestampBin(byte[] data, int off, int len, boolean timestamptz,
      CodecContext ctx) throws SQLException {
    return TimestampUtils.toTimestampBin(ctx.usesDoubleDateTime(), decodeTz(ctx), null, data, off,
        len, timestamptz);
  }

  public static LocalDateTime decodeLocalDateTimeBin(byte[] data, int off, int len, CodecContext ctx)
      throws SQLException {
    return TimestampUtils.toLocalDateTimeBin(ctx.usesDoubleDateTime(), data, off, len);
  }

  public static OffsetDateTime decodeOffsetDateTimeBin(byte[] data, int off, int len,
      CodecContext ctx) throws SQLException {
    return TimestampUtils.toOffsetDateTimeBin(ctx.usesDoubleDateTime(), data, off, len);
  }

  // ----------------------------- text decode -----------------------------

  public static Date decodeDateText(String data, CodecContext ctx) throws SQLException {
    return TimestampUtils.toDate(data.getBytes(StandardCharsets.UTF_8), decodeTz(ctx), null);
  }

  public static LocalDate decodeLocalDateText(String data, CodecContext ctx) throws SQLException {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    return TimestampUtils.toLocalDate(bytes, 0, bytes.length);
  }

  public static Time decodeTimeText(String data, CodecContext ctx) throws SQLException {
    return TimestampUtils.toTime(data.getBytes(StandardCharsets.UTF_8), decodeTz(ctx), null);
  }

  public static LocalTime decodeLocalTimeText(String data, CodecContext ctx) throws SQLException {
    return TimestampUtils.parseLocalTime(data);
  }

  public static OffsetTime decodeOffsetTimeText(String data, CodecContext ctx) throws SQLException {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    return TimestampUtils.toOffsetTime(bytes, 0, bytes.length);
  }

  public static Timestamp decodeTimestampText(String data, CodecContext ctx) throws SQLException {
    return TimestampUtils.toTimestamp(data.getBytes(StandardCharsets.UTF_8), decodeTz(ctx), null);
  }

  public static LocalDateTime decodeLocalDateTimeText(String data, CodecContext ctx)
      throws SQLException {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    return TimestampUtils.toLocalDateTime(bytes, 0, bytes.length);
  }

  public static OffsetDateTime decodeOffsetDateTimeText(String data, CodecContext ctx)
      throws SQLException {
    byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
    return TimestampUtils.toOffsetDateTime(bytes, 0, bytes.length);
  }

  // ----------------------------- cross-type helpers -----------------------------

  /** Truncates the instant {@code millis} to midnight in the context's timezone. */
  public static Date extractDate(long millis, CodecContext ctx) {
    return TimestampUtils.convertToDate(millis, decodeTz(ctx), null);
  }

  /** Anchors the instant {@code millis} to 1970-01-01 in the context's timezone. */
  public static Time extractTime(long millis, CodecContext ctx) {
    return TimestampUtils.convertToTime(millis, decodeTz(ctx), null);
  }

  // ----------------------------- text encode -----------------------------

  public static String formatDate(Date value, CodecContext ctx) {
    // A date has no time zone: the calendar uses the context zone to pick the day, but the text must
    // not carry a "+hh" suffix (PostgreSQL outputs a bare yyyy-mm-dd). A trailing offset is invalid
    // date syntax and breaks the decode round-trip.
    return TimestampUtils.toStringDate(ctx.getDefaultTimeZone(), value, false, null, null);
  }

  public static String formatLocalDate(LocalDate value, CodecContext ctx) {
    return TimestampUtils.toStringLocalDate(value, null);
  }

  public static String formatTime(Time value, CodecContext ctx) {
    // A `time` (without time zone) must not carry a "+hh" suffix; PostgreSQL outputs a bare HH:MM:SS.
    // A trailing offset is invalid time syntax and breaks the decode round-trip.
    return TimestampUtils.toStringTime(ctx.getDefaultTimeZone(), value, false, null, null);
  }

  /** Formats a {@link Time} as a {@code timetz}, assigning it the context zone offset. */
  public static String formatTimetz(Time value, CodecContext ctx) {
    return TimestampUtils.toStringTime(ctx.getDefaultTimeZone(), value, true, null, null);
  }

  public static String formatLocalTime(LocalTime value, CodecContext ctx) {
    return TimestampUtils.toStringLocalTime(value, null);
  }

  public static String formatOffsetTime(OffsetTime value, CodecContext ctx) {
    return TimestampUtils.toStringOffsetTime(value, null);
  }

  public static String formatTimestamp(Timestamp value, CodecContext ctx) {
    return TimestampUtils.toStringTimestamp(ctx.getDefaultTimeZone(), value, true, null, null);
  }

  public static String formatLocalDateTime(LocalDateTime value, CodecContext ctx) {
    return TimestampUtils.toStringLocalDateTime(value, null);
  }

  public static String formatOffsetDateTime(OffsetDateTime value, CodecContext ctx) {
    return TimestampUtils.toStringOffsetDateTime(value, null);
  }

  /** Formats the binary {@code timetz} payload as text without an intermediate object. */
  public static String formatOffsetTimeBin(byte[] data, CodecContext ctx) throws SQLException {
    return TimestampUtils.toStringOffsetTimeBin(ctx.usesDoubleDateTime(), ctx.getClientTimeZone(),
        data, null);
  }

  /** Formats the binary {@code timestamptz} payload as text without an intermediate object. */
  public static String formatOffsetDateTimeBin(byte[] data, CodecContext ctx) throws SQLException {
    return TimestampUtils.toStringOffsetDateTimeBin(ctx.usesDoubleDateTime(),
        ctx.getClientTimeZone(), data, null);
  }

  // ----------------------------- binary encode -----------------------------

  /** Encodes {@code value} into the 4-byte {@code out} buffer as a PostgreSQL {@code date}. */
  public static void encodeDateBin(Date value, byte[] out, CodecContext ctx) throws SQLException {
    TimestampUtils.writeBinDate(ctx.getDefaultTimeZone(), out, value);
  }

  /** Encodes {@code value} as a binary PostgreSQL {@code time} (8 bytes). */
  public static byte[] encodeTimeBin(Object value, CodecContext ctx) throws SQLException {
    return TimestampUtils.toBinTime(ctx.usesDoubleDateTime(), localTimeOf(value, ctx));
  }

  /** Encodes {@code value} as a binary PostgreSQL {@code timetz} (12 bytes). */
  public static byte[] encodeTimetzBin(Object value, CodecContext ctx) throws SQLException {
    return TimestampUtils.toBinTimeTz(ctx.usesDoubleDateTime(), offsetTimeOf(value, ctx));
  }

  /** Encodes {@code value} as a binary PostgreSQL {@code timestamp} (8 bytes). */
  public static byte[] encodeTimestampBin(Object value, CodecContext ctx) throws SQLException {
    boolean usesDouble = ctx.usesDoubleDateTime();
    if (value instanceof Timestamp) {
      Timestamp ts = (Timestamp) value;
      Long inf = infinityOf(ts);
      if (inf != null) {
        return TimestampUtils.infinityTimestamp(usesDouble, inf > 0);
      }
    }
    return TimestampUtils.toBinTimestamp(usesDouble, localDateTimeOf(value, ctx));
  }

  /** Encodes {@code value} as a binary PostgreSQL {@code timestamptz} (8 bytes). */
  public static byte[] encodeTimestamptzBin(Object value, CodecContext ctx) throws SQLException {
    boolean usesDouble = ctx.usesDoubleDateTime();
    if (value instanceof Timestamp) {
      Timestamp ts = (Timestamp) value;
      Long inf = infinityOf(ts);
      if (inf != null) {
        return TimestampUtils.infinityTimestamp(usesDouble, inf > 0);
      }
    }
    return TimestampUtils.toBinTimestampTz(usesDouble, instantOf(value, ctx));
  }

  // ----------------------------- binary-encode dispatch -----------------------------

  /** Returns {@code +1}/{@code -1} when {@code ts} is the +/-infinity sentinel, else {@code null}. */
  private static @Nullable Long infinityOf(Timestamp ts) {
    long t = ts.getTime();
    if (t == PGStatement.DATE_POSITIVE_INFINITY) {
      return 1L;
    }
    if (t == PGStatement.DATE_NEGATIVE_INFINITY) {
      return -1L;
    }
    return null;
  }

  @SuppressWarnings("JavaUtilDate")
  private static LocalTime localTimeOf(Object value, CodecContext ctx) throws SQLException {
    if (value instanceof LocalTime) {
      return (LocalTime) value;
    }
    if (value instanceof OffsetTime) {
      return ((OffsetTime) value).toLocalTime();
    }
    if (value instanceof String) {
      Time t = decodeTimeText((String) value, ctx);
      return TimestampUtils.localTimeOf(t.getTime(), ctx.getDefaultTimeZone(), null);
    }
    if (value instanceof java.util.Date) {
      return TimestampUtils.localTimeOf(((java.util.Date) value).getTime(),
          ctx.getDefaultTimeZone(), null);
    }
    throw cannotEncode(value, "time");
  }

  @SuppressWarnings("JavaUtilDate")
  private static OffsetTime offsetTimeOf(Object value, CodecContext ctx) throws SQLException {
    if (value instanceof OffsetTime) {
      return (OffsetTime) value;
    }
    if (value instanceof LocalTime) {
      // No offset on the value: assume the session zone, matching the server's text parsing.
      return ((LocalTime) value).atOffset(rawOffset(ctx.getClientTimeZone()));
    }
    if (value instanceof java.util.Date) {
      // The text path renders a java.util.Date time-of-day with the default-zone offset.
      java.util.Date d = (java.util.Date) value;
      TimeZone tz = ctx.getDefaultTimeZone();
      LocalTime lt = TimestampUtils.localTimeOf(d.getTime(), tz, null);
      return lt.atOffset(ZoneOffset.ofTotalSeconds(tz.getOffset(d.getTime()) / 1000));
    }
    if (value instanceof String) {
      return offsetTimeOf(decodeTimeText((String) value, ctx), ctx);
    }
    throw cannotEncode(value, "timetz");
  }

  @SuppressWarnings("JavaUtilDate")
  private static LocalDateTime localDateTimeOf(Object value, CodecContext ctx) throws SQLException {
    if (value instanceof LocalDateTime) {
      return (LocalDateTime) value;
    }
    if (value instanceof Timestamp) {
      return ((Timestamp) value).toLocalDateTime();
    }
    if (value instanceof OffsetDateTime) {
      return ((OffsetDateTime) value).toLocalDateTime();
    }
    if (value instanceof ZonedDateTime) {
      return ((ZonedDateTime) value).toLocalDateTime();
    }
    if (value instanceof Instant) {
      return LocalDateTime.ofInstant((Instant) value, ctx.getDefaultTimeZone().toZoneId());
    }
    if (value instanceof String) {
      return localDateTimeOf(decodeTimestampText((String) value, ctx), ctx);
    }
    if (value instanceof java.util.Date) {
      return LocalDateTime.ofInstant(Instant.ofEpochMilli(((java.util.Date) value).getTime()),
          ctx.getDefaultTimeZone().toZoneId());
    }
    throw cannotEncode(value, "timestamp");
  }

  @SuppressWarnings("JavaUtilDate")
  private static Instant instantOf(Object value, CodecContext ctx) throws SQLException {
    if (value instanceof Timestamp) {
      return ((Timestamp) value).toInstant();
    }
    if (value instanceof OffsetDateTime) {
      return ((OffsetDateTime) value).toInstant();
    }
    if (value instanceof ZonedDateTime) {
      return ((ZonedDateTime) value).toInstant();
    }
    if (value instanceof Instant) {
      return (Instant) value;
    }
    if (value instanceof LocalDateTime) {
      // No offset on the value: interpret in the session zone, matching the server's text parsing.
      return ((LocalDateTime) value).atZone(ctx.getClientTimeZone().toZoneId()).toInstant();
    }
    if (value instanceof String) {
      return instantOf(decodeTimestampText((String) value, ctx), ctx);
    }
    if (value instanceof java.util.Date) {
      return Instant.ofEpochMilli(((java.util.Date) value).getTime());
    }
    throw cannotEncode(value, "timestamptz");
  }

  private static ZoneOffset rawOffset(TimeZone tz) {
    return ZoneOffset.ofTotalSeconds(tz.getRawOffset() / 1000);
  }

  private static PSQLException cannotEncode(Object value, String typeName) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to {1}", value.getClass().getName(), typeName),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
