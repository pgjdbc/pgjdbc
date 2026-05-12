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
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.SQLException;
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
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    // Check connection property for default type
    if (ctx.prefersJavaTimeForDate()) {
      return ts.toLocalDateBin(data);
    }
    return ts.toDateBin(null, data);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    byte[] result = new byte[4];
    if (value instanceof Date) {
      ts.toBinDate(null, result, (Date) value);
    } else if (value instanceof LocalDate) {
      // Convert to Date and encode
      LocalDate ld = (LocalDate) value;
      ts.toBinDate(null, result, Date.valueOf(ld));
    } else if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      ts.toBinDate(null, result, new Date(time));
    } else if (value instanceof String) {
      ts.toBinDate(null, result, ts.toDate(null, (String) value));
    } else {
      throw new PSQLException(
          GT.tr("Cannot convert {0} to date", value.getClass().getName()),
          PSQLState.INVALID_PARAMETER_TYPE);
    }
    return result;
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    // Check connection property for default type
    if (ctx.prefersJavaTimeForDate()) {
      return ts.toLocalDate(data.getBytes(StandardCharsets.UTF_8));
    }
    return ts.toDate(null, data);
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (value instanceof Date) {
      return ts.toString(null, (Date) value);
    }
    if (value instanceof LocalDate) {
      return ts.toString((LocalDate) value);
    }
    if (value instanceof java.util.Date) {
      @SuppressWarnings("JavaUtilDate")
      long time = ((java.util.Date) value).getTime();
      return ts.toString(null, new Date(time));
    }
    if (value instanceof String) {
      // setObject(i, "2024-01-01", Types.DATE) and friends — let TimestampUtils
      // parse the literal so we match the legacy behavior of the driver.
      try {
        return ts.toString(null, ts.toDate(null, (String) value));
      } catch (Exception e) {
        throw new PSQLException(
            GT.tr("Cannot convert {0} to date", value),
            PSQLState.INVALID_PARAMETER_TYPE, e);
      }
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to date", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Date d = (Date) decodeBinary(data, type, ctx);
    return d != null ? d.getTime() : 0;
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    Date d = (Date) decodeText(data, type, ctx);
    return d != null ? d.getTime() : 0;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (targetClass == Date.class || targetClass == Object.class) {
      return (T) ts.toDateBin(null, data);
    }
    if (targetClass == LocalDate.class) {
      return (T) ts.toLocalDateBin(data);
    }
    if (targetClass == java.util.Date.class) {
      return (T) ts.toDateBin(null, data);
    }
    if (targetClass == Long.class) {
      Date d = ts.toDateBin(null, data);
      return d == null ? null : (T) Long.valueOf(d.getTime());
    }
    if (targetClass == String.class) {
      LocalDate ld = ts.toLocalDateBin(data);
      return ld == null ? null : (T) ts.toString(ld);
    }
    throw new PSQLException(
        GT.tr("Cannot convert date to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    if (targetClass == Date.class || targetClass == Object.class) {
      return (T) ts.toDate(null, data);
    }
    if (targetClass == LocalDate.class) {
      return (T) ts.toLocalDate(data.getBytes(StandardCharsets.UTF_8));
    }
    if (targetClass == java.util.Date.class) {
      return (T) ts.toDate(null, data);
    }
    if (targetClass == Long.class) {
      Date d = ts.toDate(null, data);
      return d == null ? null : (T) Long.valueOf(d.getTime());
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert date to {0}", targetClass.getName()),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    TimestampUtils ts = ctx.getTimestampUtils();
    LocalDate ld = ts.toLocalDateBin(data);
    return ld == null ? null : ts.toString(ld);
  }

  @Override
  public @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert date to int"),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    throw new PSQLException(
        GT.tr("Cannot convert date to int"),
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
