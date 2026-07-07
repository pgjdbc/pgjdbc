/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.TemporalCodecs;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;

/**
 * Codec for PostgreSQL timestamp (without time zone) type.
 */
public final class TimestampCodec implements StreamingBinaryCodec, TextCodec {

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
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeBinary(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Check connection property for default type
    if (ctx.prefersJavaTimeForTimestamp()) {
      return TemporalCodecs.decodeLocalDateTimeBin(data, offset, length, ctx);
    }
    return TemporalCodecs.decodeTimestampBin(data, offset, length, false, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return TemporalCodecs.encodeTimestampBin(value, ctx);
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    TemporalCodecs.writeTimestampBin(value, out, ctx);
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Check connection property for default type
    if (ctx.prefersJavaTimeForTimestamp()) {
      return TemporalCodecs.decodeLocalDateTimeText(data, ctx);
    }
    return TemporalCodecs.decodeTimestampText(data, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Timestamp) {
      return TemporalCodecs.formatTimestamp((Timestamp) value, ctx);
    }
    if (value instanceof LocalDateTime) {
      return TemporalCodecs.formatLocalDateTime((LocalDateTime) value, ctx);
    }
    if (value instanceof OffsetDateTime) {
      return TemporalCodecs.formatLocalDateTime(((OffsetDateTime) value).toLocalDateTime(), ctx);
    }
    if (value instanceof ZonedDateTime) {
      return TemporalCodecs.formatLocalDateTime(((ZonedDateTime) value).toLocalDateTime(), ctx);
    }
    if (value instanceof Instant) {
      return TemporalCodecs.formatTimestamp(Timestamp.from((Instant) value), ctx);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return TemporalCodecs.formatTimestamp(new Timestamp(time), ctx);
    }
    if (value instanceof String) {
      // setObject(i, "2024-01-01 12:00:00", Types.TIMESTAMP) and friends.
      return TemporalCodecs.formatTimestamp(TemporalCodecs.decodeTimestampText((String) value, ctx), ctx);
    }
    throw Codec.cannotEncode(value, "timestamp");
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Timestamp.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampBin(data, 0, data.length, false, ctx));
    }
    if (targetClass == LocalDateTime.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalDateTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == LocalDate.class) {
      LocalDateTime ldt = TemporalCodecs.decodeLocalDateTimeBin(data, 0, data.length, ctx);
      return targetClass.cast(ldt.toLocalDate());
    }
    if (targetClass == OffsetDateTime.class) {
      // timestamp (no tz) interpreted in UTC
      return targetClass.cast(TemporalCodecs.decodeOffsetDateTimeBin(data, 0, data.length, ctx));
    }
    if (targetClass == ZonedDateTime.class) {
      // timestamp (no tz) interpreted in UTC
      OffsetDateTime odt = TemporalCodecs.decodeOffsetDateTimeBin(data, 0, data.length, ctx);
      return targetClass.cast(odt.toZonedDateTime());
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = TemporalCodecs.decodeOffsetDateTimeBin(data, 0, data.length, ctx);
      return targetClass.cast(odt.toInstant());
    }
    if (targetClass == Date.class) {
      // JDBC: getDate on a TIMESTAMP column truncates to midnight in the target time zone.
      Timestamp t = TemporalCodecs.decodeTimestampBin(data, 0, data.length, false, ctx);
      return t == null ? null : targetClass.cast(TemporalCodecs.extractDate(t.getTime(), ctx));
    }
    if (targetClass == Time.class) {
      // JDBC: getTime on a TIMESTAMP column keeps the time part in the target time zone.
      Timestamp t = TemporalCodecs.decodeTimestampBin(data, 0, data.length, false, ctx);
      return t == null ? null : targetClass.cast(TemporalCodecs.extractTime(t.getTime(), ctx));
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampBin(data, 0, data.length, false, ctx));
    }
    if (targetClass == Long.class) {
      Timestamp t = TemporalCodecs.decodeTimestampBin(data, 0, data.length, false, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      LocalDateTime ldt = TemporalCodecs.decodeLocalDateTimeBin(data, 0, data.length, ctx);
      return ldt == null ? null : targetClass.cast(TemporalCodecs.formatLocalDateTime(ldt, ctx));
    }
    throw Codec.cannotDecode("timestamp", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Timestamp.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampText(data, ctx));
    }
    if (targetClass == LocalDateTime.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalDateTimeText(data, ctx));
    }
    if (targetClass == LocalDate.class) {
      LocalDateTime ldt = TemporalCodecs.decodeLocalDateTimeText(data, ctx);
      return ldt == null ? null : targetClass.cast(ldt.toLocalDate());
    }
    if (targetClass == OffsetDateTime.class) {
      // timestamp (no tz) - parse and interpret in UTC
      return targetClass.cast(TemporalCodecs.decodeOffsetDateTimeText(data, ctx));
    }
    if (targetClass == ZonedDateTime.class) {
      OffsetDateTime odt = TemporalCodecs.decodeOffsetDateTimeText(data, ctx);
      return odt == null ? null : targetClass.cast(odt.toZonedDateTime());
    }
    if (targetClass == Instant.class) {
      OffsetDateTime odt = TemporalCodecs.decodeOffsetDateTimeText(data, ctx);
      return odt == null ? null : targetClass.cast(odt.toInstant());
    }
    if (targetClass == Date.class) {
      return targetClass.cast(TemporalCodecs.decodeDateText(data, ctx));
    }
    if (targetClass == Time.class) {
      return targetClass.cast(TemporalCodecs.decodeTimeText(data, ctx));
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeTimestampText(data, ctx));
    }
    if (targetClass == Long.class) {
      Timestamp t = TemporalCodecs.decodeTimestampText(data, ctx);
      return t == null ? null : targetClass.cast(t.getTime());
    }
    if (targetClass == String.class) {
      return targetClass.cast(data);
    }
    throw Codec.cannotDecode("timestamp", targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    LocalDateTime ldt = TemporalCodecs.decodeLocalDateTimeBin(data, 0, data.length, ctx);
    return ldt == null ? null : TemporalCodecs.formatLocalDateTime(ldt, ctx);
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Preserve the original text (with microsecond precision).
    return data;
  }

}
