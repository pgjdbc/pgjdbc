/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.TemporalCodecs;
import org.postgresql.util.GT;
import org.postgresql.util.PGUnknownBinary;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Fallback codec for unknown/unmapped PostgreSQL types.
 *
 * <p>Returns values as {@link PGobject} for text format.
 * For binary format, stores raw bytes in PGobject.</p>
 */
public final class FallbackCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder {

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
  public boolean supportsBinaryRead() {
    // The fallback does not interpret the real binary wire of an unmapped type; it only wraps
    // the raw bytes. Reporting false makes the receive-format choice request such a type in text,
    // so it arrives as a readable PGobject (decodeText) rather than PGUnknownBinary. Binary data
    // still reaches decodeBinary when it is unavoidable -- e.g. an unmapped field nested in a
    // record the driver requested in binary -- and is surfaced as PGUnknownBinary there.
    return false;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Reached only when the server sends binary for an unmapped type anyway (e.g. a field inside a
    // binary record): preserve the raw bytes as PGUnknownBinary instead of a lossy hex string.
    return new PGUnknownBinary(type.getTypeName().getName(), data);
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return value instanceof PGUnknownBinary || value instanceof String;
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (value instanceof PGUnknownBinary) {
      byte[] bytes = ((PGUnknownBinary) value).getBytes();
      return bytes != null ? bytes : new byte[0];
    }
    if (value instanceof String) {
      return ((String) value).getBytes(ctx.getCharset());
    }
    throw Codec.cannotEncode(value, type.getTypeName().getName());
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType(type.getTypeName().getName());
    obj.setValue(data);
    return obj;
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return new String(data, ctx.getCharset());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
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
    throw Codec.cannotDecode(type.getTypeName().getName(), targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == PGobject.class || targetClass == Object.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    // 'unknown' (oid 705, e.g. SELECT '2024-01-01' without a cast) and any other unmapped text
    // value is parsed as a date/time literal, matching how the legacy fallback handled any
    // text column. Mapped non-string types (json, xml, ...) carry their own codec and never
    // reach here, so they keep rejecting date/time coercion.
    if (targetClass == Date.class) {
      return (T) TemporalCodecs.decodeDateText(data, ctx);
    }
    if (targetClass == Time.class) {
      return (T) TemporalCodecs.decodeTimeText(data, ctx);
    }
    if (targetClass == Timestamp.class) {
      return (T) TemporalCodecs.decodeTimestampText(data, ctx);
    }
    throw Codec.cannotDecode(type.getTypeName().getName(), targetClass.getName());
  }

  public int decodeAsInt(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsInt(data, 0, data.length, type, ctx);
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to int: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Integer.parseInt(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to int: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  public long decodeAsLong(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, 0, data.length, type, ctx);
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to long: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Long.parseLong(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to long: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  public double decodeAsDouble(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsDouble(data, 0, data.length, type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to double: {0}", s),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Double.parseDouble(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to double: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

}
