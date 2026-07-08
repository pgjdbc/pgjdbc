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
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * Codec for PostgreSQL date type.
 */
public final class DateCodec implements StreamingBinaryCodec, TextCodec {

  public static final DateCodec INSTANCE = new DateCodec();

  private DateCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "date";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Date.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Check connection property for default type
    if (ctx.prefersJavaTimeForDate()) {
      return TemporalCodecs.decodeLocalDateBin(data, offset, length, ctx);
    }
    return TemporalCodecs.decodeDateBin(data, offset, length, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    byte[] result = new byte[4];
    TemporalCodecs.encodeDateBin(toDate(value, ctx), result, ctx);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    TemporalCodecs.writeDateBin(toDate(value, ctx), out, ctx);
  }

  /** Coerces a supported date-like value to {@link Date}, shared by both encode paths. */
  private static Date toDate(Object value, CodecContext ctx) throws SQLException {
    if (value instanceof Date) {
      return (Date) value;
    }
    if (value instanceof LocalDate) {
      return Date.valueOf((LocalDate) value);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return new Date(time);
    }
    if (value instanceof String) {
      // decodeDateText already rejects a malformed literal with a clean SQLException (the parser no
      // longer leaks an ArrayIndexOutOfBoundsException), so the binary path needs no extra wrapping.
      return TemporalCodecs.decodeDateText((String) value, ctx);
    }
    throw Codec.cannotEncode(value, "date");
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Check connection property for default type
    if (ctx.prefersJavaTimeForDate()) {
      return TemporalCodecs.decodeLocalDateText(data, ctx);
    }
    return TemporalCodecs.decodeDateText(data, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof Date) {
      return TemporalCodecs.formatDate((Date) value, ctx);
    }
    if (value instanceof LocalDate) {
      return TemporalCodecs.formatLocalDate((LocalDate) value, ctx);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return TemporalCodecs.formatDate(new Date(time), ctx);
    }
    if (value instanceof String) {
      // setObject(i, "2024-01-01", Types.DATE) and friends — parse the literal so we match the
      // legacy behavior of the driver. decodeDateText rejects a malformed literal with a clean
      // SQLException (BAD_DATETIME_FORMAT), so no extra wrapping is needed here.
      return TemporalCodecs.formatDate(TemporalCodecs.decodeDateText((String) value, ctx), ctx);
    }
    throw Codec.cannotEncode(value, "date");
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == Date.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeDateBin(data, offset, length, ctx));
    }
    if (targetClass == LocalDate.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalDateBin(data, offset, length, ctx));
    }
    if (targetClass == Timestamp.class) {
      // JDBC: getTimestamp on a DATE column yields midnight of that day.
      Date d = TemporalCodecs.decodeDateBin(data, offset, length, ctx);
      return d == null ? null : targetClass.cast(new Timestamp(d.getTime()));
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeDateBin(data, offset, length, ctx));
    }
    if (targetClass == Long.class) {
      Date d = TemporalCodecs.decodeDateBin(data, offset, length, ctx);
      return d == null ? null : targetClass.cast(d.getTime());
    }
    if (targetClass == String.class) {
      LocalDate ld = TemporalCodecs.decodeLocalDateBin(data, offset, length, ctx);
      return ld == null ? null : targetClass.cast(TemporalCodecs.formatLocalDate(ld, ctx));
    }
    throw Codec.cannotDecode("date", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Date.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeDateText(data, ctx));
    }
    if (targetClass == LocalDate.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalDateText(data, ctx));
    }
    if (targetClass == Timestamp.class) {
      Date d = TemporalCodecs.decodeDateText(data, ctx);
      return d == null ? null : targetClass.cast(new Timestamp(d.getTime()));
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeDateText(data, ctx));
    }
    if (targetClass == Long.class) {
      Date d = TemporalCodecs.decodeDateText(data, ctx);
      return d == null ? null : targetClass.cast(d.getTime());
    }
    if (targetClass == String.class) {
      return targetClass.cast(data);
    }
    throw Codec.cannotDecode("date", targetClass.getName());
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    LocalDate ld = TemporalCodecs.decodeLocalDateBin(data, offset, length, ctx);
    return ld == null ? null : TemporalCodecs.formatLocalDate(ld, ctx);
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

}
