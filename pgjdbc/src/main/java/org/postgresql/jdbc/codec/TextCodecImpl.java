/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.StreamingTextCodec;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL text type.
 */
public final class TextCodecImpl implements StreamingBinaryCodec, StreamingTextCodec {

  public static final TextCodecImpl INSTANCE = new TextCodecImpl();

  private TextCodecImpl() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "text";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return String.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsString(data, type, ctx);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    String s = toString(value);
    Charset encoding = ctx.getCharset();
    return s.getBytes(encoding);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    return toString(value);
  }

  @Override
  public void encodeBinary(Object value, PgType type, CodecContext ctx, OutputStream out)
      throws SQLException, IOException {
    String s = toString(value);
    Charset encoding = ctx.getCharset();
    out.write(s.getBytes(encoding));
  }

  @Override
  public void encodeText(Object value, PgType type, CodecContext ctx, Appendable out)
      throws SQLException, IOException {
    out.append(toString(value));
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Charset encoding = ctx.getCharset();
    return new String(data, encoding);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = castNonNull(decodeAsString(data, type, ctx));
    return parseAsInt(s);
  }

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    return parseAsInt(data);
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = castNonNull(decodeAsString(data, type, ctx));
    return parseAsLong(s);
  }

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    return parseAsLong(data);
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = castNonNull(decodeAsString(data, type, ctx));
    return parseAsDouble(s);
  }

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    return parseAsDouble(data);
  }

  @Override
  public float decodeAsFloat(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = castNonNull(decodeAsString(data, type, ctx));
    return (float) parseAsDouble(s);
  }

  @Override
  public float decodeAsFloat(String data, PgType type, CodecContext ctx) throws SQLException {
    return (float) parseAsDouble(data);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = castNonNull(decodeAsString(data, type, ctx));
    return parseAsBigDecimal(s);
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = castNonNull(decodeAsString(data, type, ctx));
    return parseAsBoolean(s);
  }

  @Override
  public boolean decodeAsBoolean(String data, PgType type, CodecContext ctx) throws SQLException {
    return parseAsBoolean(data);
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    String value = decodeAsString(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) value;
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf(parseAsInt(value));
    }
    if (targetClass == Long.class) {
      return (T) Long.valueOf(parseAsLong(value));
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(parseAsDouble(value));
    }
    if (targetClass == Float.class) {
      return (T) Float.valueOf((float) parseAsDouble(value));
    }
    if (targetClass == BigDecimal.class) {
      return (T) parseAsBigDecimal(value);
    }
    if (targetClass == Boolean.class) {
      return (T) Boolean.valueOf(parseAsBoolean(value));
    }
    if (targetClass == Short.class) {
      int i = parseAsInt(value);
      if (i < Short.MIN_VALUE || i > Short.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for short", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Short.valueOf((short) i);
    }
    if (targetClass == Byte.class) {
      int i = parseAsInt(value);
      if (i < Byte.MIN_VALUE || i > Byte.MAX_VALUE) {
        throw new PSQLException(
            GT.tr("Value {0} is out of range for byte", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return (T) Byte.valueOf((byte) i);
    }
    throw new PSQLException(
        GT.tr("Cannot convert text to {0}", targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    Charset encoding = ctx.getCharset();
    return decodeBinaryAs(data.getBytes(encoding), type, targetClass, ctx);
  }

  private String toString(Object value) throws SQLException {
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Character) {
      return value.toString();
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to text", value.getClass().getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  private static int parseAsInt(String s) throws SQLException {
    if (s == null) {
      return 0;
    }
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to int: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static long parseAsLong(String s) throws SQLException {
    if (s == null) {
      return 0;
    }
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to long: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static double parseAsDouble(String s) throws SQLException {
    if (s == null) {
      return 0;
    }
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to double: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static @Nullable BigDecimal parseAsBigDecimal(String s) throws SQLException {
    if (s == null) {
      return null;
    }
    try {
      return new BigDecimal(s.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to numeric: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static boolean parseAsBoolean(String s) throws SQLException {
    if (s == null) {
      return false;
    }
    return BooleanTypeUtil.fromString(s);
  }
}
