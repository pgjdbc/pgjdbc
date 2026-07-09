/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;

/**
 * Codec for PostgreSQL {@code oid8} type (PostgreSQL 18 and later).
 *
 * <p>{@code oid8} is an unsigned 64-bit object identifier, the wide successor of {@code oid}
 * ({@link OidCodec}). Unlike a 32-bit {@code oid}, its full range does not fit in a signed
 * {@code long} without ambiguity, so the driver represents the value by its raw bit pattern —
 * the same bits {@code oid8send}/{@code oid8recv} put on the wire — rather than widening into a
 * larger primitive the way {@code oid} widens into {@code long}. {@link #decodeAsLong} and
 * {@code getLong()} therefore return a negative value for {@code oid8} values at or above
 * 2<sup>63</sup>; {@link Long#toUnsignedString(long)} (used by {@link #encodeText}) and
 * {@link Long#parseUnsignedLong(String)} (used to decode text) are what recover the decimal
 * value PostgreSQL itself prints for {@code oid8out}.</p>
 */
public final class Oid8Codec implements StreamingBinaryCodec, PrimitiveBinaryDecoder,
    PrimitiveTextDecoder, ArrayElementCodec {

  public static final Oid8Codec INSTANCE = new Oid8Codec();

  private Oid8Codec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "oid8";
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
    return Oid8ArrayLeafCodec.INSTANCE;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (length != 8) {
      throw Exceptions.invalidBinaryLength("oid8", length);
    }
    return ByteConverter.int8(data, offset);
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    long v = toLong(value);
    byte[] result = new byte[8];
    ByteConverter.int8(result, 0, v);
    return result;
  }

  @Override
  public void encodeBinary(Object value, TypeDescriptor type, CodecContext ctx,
      BackpatchingBinarySink out) throws SQLException, IOException {
    out.writeInt64(toLong(value));
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return decodeAsLong(data, type, ctx);
  }

  @Override
  public @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return decodeAsLong(new String(data, offset, length), type, ctx);
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return Long.toUnsignedString(toLong(value));
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return UnsignedLongDecoders.toUnsignedInt(decodeAsLong(data, offset, length, type, ctx));
  }

  @Override
  public int decodeAsInt(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return UnsignedLongDecoders.toUnsignedInt(decodeAsLong(data, type, ctx));
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (length != 8) {
      throw Exceptions.invalidBinaryLength("oid8", length);
    }
    return ByteConverter.int8(data, offset);
  }

  @Override
  public long decodeAsLong(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    try {
      return Long.parseUnsignedLong(data.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("oid8", data, e);
    }
  }

  @Override
  public long decodeAsLong(char[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return decodeAsLong(new String(data, offset, length), type, ctx);
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return UnsignedLongDecoders.toDouble(decodeAsLong(data, offset, length, type, ctx));
  }

  @Override
  public double decodeAsDouble(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return UnsignedLongDecoders.toDouble(decodeAsLong(data, type, ctx));
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return new BigDecimal(UnsignedLongDecoders.toBigInteger(decodeAsLong(data, offset, length, type, ctx)));
  }

  @Override
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    long value = decodeAsLong(data, offset, length, type, ctx);
    return decodeOid8As(value, targetClass);
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    long value = decodeAsLong(data, type, ctx);
    return decodeOid8As(value, targetClass);
  }

  @SuppressWarnings("unchecked")
  private static <T> T decodeOid8As(long value, Class<T> targetClass) throws SQLException {
    if (targetClass == Long.class || targetClass == Object.class) {
      return (T) Long.valueOf(value);
    }
    if (targetClass == Integer.class) {
      return (T) Integer.valueOf(UnsignedLongDecoders.toUnsignedInt(value));
    }
    if (targetClass == Short.class) {
      return (T) Short.valueOf(UnsignedLongDecoders.toUnsignedShort(value));
    }
    if (targetClass == String.class) {
      return (T) Long.toUnsignedString(value);
    }
    if (targetClass == Double.class) {
      return (T) Double.valueOf(UnsignedLongDecoders.toDouble(value));
    }
    if (targetClass == BigInteger.class) {
      return (T) UnsignedLongDecoders.toBigInteger(value);
    }
    if (targetClass == BigDecimal.class) {
      return (T) new BigDecimal(UnsignedLongDecoders.toBigInteger(value));
    }
    throw Exceptions.cannotDecode("oid8", targetClass.getName());
  }

  static long toLong(Object value) throws SQLException {
    if (value instanceof Number) {
      // BigInteger#longValue() (like every Number#longValue()) truncates to the low 64 bits,
      // which is exactly the raw bit pattern oid8 needs for any value in its 0..2^64-1 domain.
      return ((Number) value).longValue();
    }
    if (value instanceof String) {
      try {
        return Long.parseUnsignedLong(((String) value).trim());
      } catch (NumberFormatException e) {
        throw Exceptions.cannotConvertValue("oid8", value, e);
      }
    }
    throw Exceptions.cannotEncode(value, "oid8");
  }
}
