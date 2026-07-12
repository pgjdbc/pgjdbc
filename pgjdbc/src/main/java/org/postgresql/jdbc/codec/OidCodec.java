/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BackpatchingBinarySink;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.StreamingBinaryCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.ByteConverter;
import org.postgresql.util.NumberParser;

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
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 4) {
      throw Exceptions.invalidBinaryLength("oid", length);
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
    // oid is unsigned 32-bit. getInt returns the raw 32-bit value reinterpreted as a signed int
    // (an oid above Integer.MAX_VALUE comes back negative), matching the legacy driver; callers that
    // need the numeric value use getLong. The unsigned form is preserved by getString/getObject.
    return (int) decodeAsLong(data, offset, length, type, ctx);
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return (int) decodeAsLong(data, type, ctx);
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 4) {
      throw Exceptions.invalidBinaryLength("oid", length);
    }
    // Treat as unsigned 32-bit
    return ByteConverter.int4(data, offset) & 0xFFFFFFFFL;
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Long bounds, matching decodeText(char[]): the oid text is parsed as a signed long without
    // masking to unsigned 32-bit.
    try {
      return NumberParser.getFastLong(data, 0, data.length(), Long.MIN_VALUE, Long.MAX_VALUE);
    } catch (NumberFormatException fast) {
      // The fast path rejects a leading '+', whitespace, or an out-of-range value; fall back to the
      // String parser, which owns the parse and the error message.
      String text = data.toString();
      try {
        return Long.parseLong(text.trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("oid", text, e);
      }
    }
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decodeAsLong(data, offset, length, type, ctx);
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return BigDecimal.valueOf(decodeAsLong(data, offset, length, type, ctx));
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    long value = decodeAsLong(data, offset, length, type, ctx);
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
      // oid is unsigned 32-bit: return the raw 32-bit value as a signed int (wrapping above
      // Integer.MAX_VALUE), matching getInt and the legacy driver. String keeps the unsigned form.
      return (T) Integer.valueOf((int) value);
    }
    if (targetClass == String.class) {
      // value already holds the unsigned 32-bit oid (0..2^32-1), so this never renders a negative.
      return (T) String.valueOf(value);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf((double) value);
    }
    if (targetClass == BigDecimal.class) {
      return (T) BigDecimal.valueOf(value);
    }
    throw Exceptions.cannotDecode("oid", targetClass.getName());
  }

  static long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseLong(((String) value).trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("oid", value, e);
      }
    }
    throw Exceptions.cannotEncode(value, "oid");
  }
}
