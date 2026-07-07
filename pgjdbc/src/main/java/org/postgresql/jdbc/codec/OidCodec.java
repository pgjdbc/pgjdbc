/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;
import org.postgresql.util.NumberParser;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL oid type.
 *
 * <p>OID is an unsigned 32-bit integer, represented as Long in Java
 * to handle the full range without overflow.</p>
 */
public final class OidCodec implements StreamingBinaryCodec, PrimitiveBinaryDecoder,
    PrimitiveTextDecoder, ArrayElementCodec {

  public static final OidCodec INSTANCE = new OidCodec();

  private OidCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "oid";
  }

  @Override
  public boolean mayRequireQuoting() {
    // Output is digits — never needs composite/array quoting.
    return false;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return Long.class;
  }

  @Override
  public ArrayLeafCodec arrayLeaf() {
    return OidArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, 0, data.length, type, ctx);
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid oid binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    // Treat as unsigned 32-bit
    return ByteConverter.int4(data, offset) & 0xFFFFFFFFL;
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    long v = toLong(value);
    byte[] result = new byte[4];
    ByteConverter.int4(result, 0, (int) v);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    out.writeInt32((int) toLong(value));
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    // Long bounds, not 0..2^32-1: decodeText(String) parses the full signed long
    // without masking to unsigned 32-bit, and the slice form has to match it.
    try {
      return NumberParser.getFastLong(data, offset, length, Long.MIN_VALUE, Long.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // Anything the fast path rejects (a leading '+', whitespace, out-of-range)
      // falls back to the String parser, which owns the error message.
      return decodeText(new String(data, offset, length), type, ctx);
    }
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return String.valueOf(toLong(value));
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid oid binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    return ByteConverter.int4(data, offset);
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      long v = Long.parseLong(data.trim());
      return (int) v;
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to oid: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 4) {
      throw new PSQLException(
          GT.tr("Invalid oid binary data length: {0}", length),
          PSQLState.DATA_ERROR);
    }
    // Treat as unsigned 32-bit
    return ByteConverter.int4(data, offset) & 0xFFFFFFFFL;
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Long.parseLong(data.trim());
    } catch (NumberFormatException e) {
      throw new PSQLException(
          GT.tr("Cannot convert value to oid: {0}", data),
          PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
    }
  }

  @Override
  public long decodeAsLong(char[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    // Long bounds, matching decodeAsLong(String)/decodeText(char[]): the oid text is parsed as a
    // signed long without masking to unsigned 32-bit.
    try {
      return NumberParser.getFastLong(data, offset, length, Long.MIN_VALUE, Long.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // The fast path rejects a leading '+', whitespace, or an out-of-range value; the String
      // primitive form owns the parse and the error message.
      return decodeAsLong(new String(data, offset, length), type, ctx);
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decodeAsLong(data, offset, length, type, ctx);
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsLong(data, 0, data.length, type, ctx));
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    long value = decodeAsLong(data, 0, data.length, type, ctx);
    return decodeOidAs(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    return decodeOidAs(value, targetClass);
  }

  @SuppressWarnings("unchecked")
  private static <T> T decodeOidAs(long value, Class<T> targetClass) throws SQLException {
    if (targetClass == Long.class || targetClass == Object.class) {
      return (T) Long.valueOf(value);
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf((int) value);
    }
    if (targetClass == String.class) {
      return (T) String.valueOf(value);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf((double) value);
    }
    if (targetClass == BigDecimal.class) {
      return (T) BigDecimal.valueOf(value);
    }
    throw Codec.cannotDecode("oid", targetClass.getName());
  }

  static long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong(((String) value).trim());
      } catch (NumberFormatException e) {
        throw new PSQLException(
            GT.tr("Cannot convert value to oid: {0}", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE, e);
      }
    }
    throw Codec.cannotEncode(value, "oid");
  }
}
