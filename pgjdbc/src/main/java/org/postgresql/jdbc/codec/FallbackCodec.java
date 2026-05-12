/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.GT;
import org.postgresql.util.PGobject;
import org.postgresql.util.PGUnknownBinary;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Fallback codec for unknown/unmapped PostgreSQL types.
 *
 * <p>Returns values as {@link PGobject} for text format.
 * For binary format, stores raw bytes in PGobject.</p>
 */
public final class FallbackCodec implements BinaryCodec, TextCodec {

  public static final FallbackCodec INSTANCE = new FallbackCodec();

  private FallbackCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "unknown";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGobject.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    // Use PGUnknownBinary to preserve raw binary data without hex conversion
    return new PGUnknownBinary(type.getTypeName().getName(), data);
  }

  @Override
  public byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof PGUnknownBinary) {
      byte[] bytes = ((PGUnknownBinary) value).getBytes();
      return bytes != null ? bytes : new byte[0];
    }
    if (value instanceof PGobject) {
      String strValue = ((PGobject) value).getValue();
      if (strValue != null) {
        return strValue.getBytes(ctx.getCharset());
      }
      return new byte[0];
    }
    if (value instanceof String) {
      return ((String) value).getBytes(ctx.getCharset());
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to {1}", value.getClass().getName(), type.getTypeName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType(type.getTypeName().getName());
    obj.setValue(data);
    return obj;
  }

  @Override
  public String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException {
    if (value instanceof PGobject) {
      String strValue = ((PGobject) value).getValue();
      return strValue != null ? strValue : "";
    }
    if (value instanceof String) {
      return (String) value;
    }
    return value.toString();
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return new String(data, ctx.getCharset());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGUnknownBinary.class || targetClass == Object.class) {
      return (T) decodeBinary(data, type, ctx);
    }
    if (targetClass == PGobject.class) {
      // Return text-based PGobject for compatibility
      PGobject obj = new PGobject();
      obj.setType(type.getTypeName().getName());
      obj.setValue(decodeAsString(data, type, ctx));
      return (T) obj;
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, type, ctx);
    }
    if (targetClass == byte[].class) {
      return (T) data.clone();
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to {1}", type.getTypeName(), targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    throw new PSQLException(
        GT.tr("Cannot convert {0} to {1}", type.getTypeName(), targetClass.getName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  @Override
  public int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = decodeAsString(data, type, ctx);
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

  @Override
  public int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Integer.parseInt(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to int: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = decodeAsString(data, type, ctx);
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

  @Override
  public long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Long.parseLong(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to long: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    String s = decodeAsString(data, type, ctx);
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

  @Override
  public double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    try {
      return Double.parseDouble(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to double: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

  private static String bytesToHex(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      hexChars[i * 2] = HEX_CHARS[v >>> 4];
      hexChars[i * 2 + 1] = HEX_CHARS[v & 0x0F];
    }
    return new String(hexChars);
  }
}
