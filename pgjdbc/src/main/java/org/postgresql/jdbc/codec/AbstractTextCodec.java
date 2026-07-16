/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.PrimitiveBinaryDecoder;
import org.postgresql.api.codec.PrimitiveTextDecoder;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.jdbc.BooleanTypeUtil;
import org.postgresql.jdbc.TemporalCodecs;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;

/**
 * Shared decode/encode logic for the {@code String}-natural built-in types: {@code text}, {@code varchar},
 * {@code bpchar}, {@code name}, and {@code "char"}. Their {@code typsend}/{@code typreceive} pair leaves the
 * value as its charset text, so the wire is just the string in the connection charset in both formats; a
 * subclass supplies only the type name (see {@link #getPrimaryTypeName()}).
 *
 * <p>This base advertises only {@link PrimitiveBinaryDecoder} and {@link PrimitiveTextDecoder}. None of
 * these codecs stream: a {@code String} must be materialized into charset bytes before it is written
 * either way, so a streaming encoder would save nothing over the {@code byte[]}/{@code String} form
 * (unlike a fixed-width primitive such as {@code int4}). The format-negotiation layer sees them all as
 * leaf codecs.</p>
 */
abstract class AbstractTextCodec implements PrimitiveBinaryDecoder, PrimitiveTextDecoder {

  private final String typeName;

  AbstractTextCodec(String typeName) {
    this.typeName = typeName;
  }

  @Override
  public String getPrimaryTypeName() {
    return typeName;
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return String.class;
  }

  @Override
  public Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return new String(data, offset, length, ctx.getCharset());
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String s = toString(value);
    Charset encoding = ctx.getCharset();
    return s.getBytes(encoding);
  }

  @Override
  public Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toString(value);
  }

  @Override
  public int decodeAsInt(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    return parseAsInt(s);
  }

  @Override
  public int decodeAsInt(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseAsInt(data.toString());
  }

  @Override
  public long decodeAsLong(byte[] data, int offset, int length, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    return parseAsLong(s);
  }

  @Override
  public long decodeAsLong(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseAsLong(data.toString());
  }

  @Override
  public double decodeAsDouble(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    return parseAsDouble(s);
  }

  @Override
  public double decodeAsDouble(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseAsDouble(data.toString());
  }

  @Override
  public float decodeAsFloat(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    return parseAsFloat(s);
  }

  @Override
  public float decodeAsFloat(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseAsFloat(data.toString());
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return parseAsBigDecimal(new String(data, offset, length, ctx.getCharset()));
  }

  @Override
  public @Nullable BigDecimal decodeAsBigDecimal(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseAsBigDecimal(data.toString());
  }

  @Override
  public boolean decodeAsBoolean(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    String s = new String(data, offset, length, ctx.getCharset());
    return parseAsBoolean(s);
  }

  @Override
  public boolean decodeAsBoolean(CharSequence data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return parseAsBoolean(data.toString());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    String value = new String(data, offset, length, ctx.getCharset());
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
      return (T) Float.valueOf(parseAsFloat(value));
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
        throw Exceptions.outOfRange(value, "short");
      }
      return (T) Short.valueOf((short) i);
    }
    if (targetClass == Byte.class) {
      int i = parseAsInt(value);
      if (i < Byte.MIN_VALUE || i > Byte.MAX_VALUE) {
        throw Exceptions.outOfRange(value, "byte");
      }
      return (T) Byte.valueOf((byte) i);
    }
    if (targetClass == Date.class) {
      return (T) TemporalCodecs.decodeDateText(value, ctx);
    }
    if (targetClass == Time.class) {
      return (T) TemporalCodecs.decodeTimeText(value, ctx);
    }
    if (targetClass == Timestamp.class) {
      return (T) TemporalCodecs.decodeTimestampText(value, ctx);
    }
    throw Exceptions.cannotDecode("text", targetClass.getName());
  }

  @Override
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    Charset encoding = ctx.getCharset();
    // TODO: optimize
    byte[] data1 = data.getBytes(encoding);
    return decodeBinaryAs(data1, 0, data1.length, type, targetClass, ctx);
  }

  // Package-private so the subclass encoders (such as CharCodec) reuse the same value-to-string rule.
  static String toString(Object value) throws SQLException {
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }
    if (value instanceof Character) {
      return value.toString();
    }
    throw Exceptions.cannotEncode(value, "text");
  }

  private static int parseAsInt(String s) throws SQLException {
    if (s == null) {
      return 0;
    }
    try {
      return Integer.parseInt(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("int", s, e);
    }
  }

  private static long parseAsLong(String s) throws SQLException {
    if (s == null) {
      return 0;
    }
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("long", s, e);
    }
  }

  private static float parseAsFloat(String s) throws SQLException {
    if (s == null) {
      return 0;
    }
    try {
      // Round the decimal straight to float. Going through Double.parseDouble and narrowing would
      // round twice, so a value near a float boundary could land one ULP off from the correctly
      // rounded result (and from Float4Codec and the legacy getFloat path, which both use this).
      return Float.parseFloat(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("float", s, e);
    }
  }

  private static double parseAsDouble(String s) throws SQLException {
    if (s == null) {
      return 0;
    }
    try {
      return Double.parseDouble(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("double", s, e);
    }
  }

  private static @Nullable BigDecimal parseAsBigDecimal(String s) throws SQLException {
    if (s == null) {
      return null;
    }
    try {
      return new BigDecimal(s.trim());
    } catch (NumberFormatException e) {
      throw Exceptions.cannotConvertValue("numeric", s, e);
    }
  }

  private static boolean parseAsBoolean(String s) throws SQLException {
    if (s == null) {
      return false;
    }
    return BooleanTypeUtil.fromString(s);
  }
}
