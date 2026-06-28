/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.TemporalCodecs;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;

/**
 * Codec for PostgreSQL date type.
 */
public final class DateCodec implements BinaryCodec, TextCodec {

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
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeBinary(data, 0, data.length, type, ctx);
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
    if (value instanceof Date) {
      TemporalCodecs.encodeDateBin((Date) value, result, ctx);
    } else if (value instanceof LocalDate) {
      // Convert to Date and encode
      TemporalCodecs.encodeDateBin(Date.valueOf((LocalDate) value), result, ctx);
    } else if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      TemporalCodecs.encodeDateBin(new Date(time), result, ctx);
    } else if (value instanceof String) {
      TemporalCodecs.encodeDateBin(TemporalCodecs.decodeDateText((String) value, ctx), result, ctx);
    } else {
      throw Codec.cannotEncode(value, "date");
    }
    return result;
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
      // setObject(i, "2024-01-01", Types.DATE) and friends — parse the literal so
      // we match the legacy behavior of the driver.
      try {
        return TemporalCodecs.formatDate(TemporalCodecs.decodeDateText((String) value, ctx), ctx);
      } catch (Exception e) {
        throw new PSQLException(
            GT.tr("Cannot convert {0} to date", value),
            PSQLState.INVALID_PARAMETER_TYPE, e);
      }
    }
    throw Codec.cannotEncode(value, "date");
  }

  @Override
  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    Date d = (Date) decodeBinary(data, type, ctx);
    return d != null ? d.getTime() : 0;
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    Date d = (Date) decodeText(data, type, ctx);
    return d != null ? d.getTime() : 0;
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Date.class || targetClass == Object.class) {
      return targetClass.cast(TemporalCodecs.decodeDateBin(data, 0, data.length, ctx));
    }
    if (targetClass == LocalDate.class) {
      return targetClass.cast(TemporalCodecs.decodeLocalDateBin(data, 0, data.length, ctx));
    }
    if (targetClass == Timestamp.class) {
      // JDBC: getTimestamp on a DATE column yields midnight of that day.
      Date d = TemporalCodecs.decodeDateBin(data, 0, data.length, ctx);
      return d == null ? null : targetClass.cast(new Timestamp(d.getTime()));
    }
    if (targetClass == java.util.Date.class) {
      return targetClass.cast(TemporalCodecs.decodeDateBin(data, 0, data.length, ctx));
    }
    if (targetClass == Long.class) {
      Date d = TemporalCodecs.decodeDateBin(data, 0, data.length, ctx);
      return d == null ? null : targetClass.cast(d.getTime());
    }
    if (targetClass == String.class) {
      LocalDate ld = TemporalCodecs.decodeLocalDateBin(data, 0, data.length, ctx);
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
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    LocalDate ld = TemporalCodecs.decodeLocalDateBin(data, 0, data.length, ctx);
    return ld == null ? null : TemporalCodecs.formatLocalDate(ld, ctx);
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert date to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert date to int"),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsLong(data, type, ctx));
  }
}
