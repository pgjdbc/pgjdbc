/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import org.postgresql.api.codec.BinaryCodec;
import org.postgresql.api.codec.Codec;
import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.TextCodec;
import org.postgresql.api.codec.TypeDescriptor;
import org.postgresql.util.PGobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.Arrays;

/**
 * Codec for codec-less types whose server {@code typsend} emits raw charset text
 * ({@code textsend}/{@code varcharsend}/{@code bpcharsend}/{@code namesend}), such as
 * {@code refcursor} and extension types that reuse one of those sends.
 *
 * <p>For such a type the binary wire bytes are exactly the charset text, so both wire formats decode
 * to a text {@link PGobject} carrying the type name (like {@link FallbackCodec}). Unlike
 * {@code FallbackCodec}, this reports {@link #supportsBinaryRead()} {@code true}: the binary form is
 * genuinely decodable, so a value nested in a binary {@code record} reads as a readable
 * {@code PGobject} rather than a {@link org.postgresql.util.PGUnknownBinary}.</p>
 *
 * <p>On the bind side it accepts a {@link String} or a {@link PGobject} of the <em>matching</em>
 * type and writes the charset text bytes in either wire format.</p>
 */
public final class TextLikeCodec implements BinaryCodec, TextCodec {

  public static final TextLikeCodec INSTANCE = new TextLikeCodec();

  private TextLikeCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    return "textlike";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return PGobject.class;
  }

  @Override
  public boolean supportsBinaryRead() {
    // The binary wire of a text-send type is the charset text, so it is genuinely decodable here
    // (unlike FallbackCodec). Returning true makes the type eligible for binary receive and lets a
    // value nested in a binary record decode to a PGobject instead of PGUnknownBinary.
    return true;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return toPgObject(type, new String(data, offset, length, ctx.getCharset()));
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toPgObject(type, data);
  }

  private static PGobject toPgObject(TypeDescriptor type, String value) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType(type.getTypeName().getName());
    obj.setValue(value);
    return obj;
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return new String(data, offset, length, ctx.getCharset());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    if (targetClass == Object.class || targetClass == PGobject.class) {
      return (T) decodeBinary(data, offset, length, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) decodeAsString(data, offset, length, type, ctx);
    }
    if (targetClass == byte[].class) {
      return (T) Arrays.copyOfRange(data, offset, offset + length);
    }
    throw Codec.cannotDecode(getTypeName(), targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == Object.class || targetClass == PGobject.class) {
      return (T) decodeText(data, type, ctx);
    }
    if (targetClass == String.class) {
      return (T) data;
    }
    if (targetClass == byte[].class) {
      // The text form already is the value; its charset bytes mirror decodeBinaryAs(byte[]).
      return (T) data.getBytes(ctx.getCharset());
    }
    throw Codec.cannotDecode(getTypeName(), targetClass.getName());
  }

  @Override
  public boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return value instanceof String || encodableText(value, type) != null;
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return encodeValue(value, type).getBytes(ctx.getCharset());
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return encodeValue(value, type);
  }

  // Accepts a String, or a PGobject of the matching type (a null PGobject value encodes as "").
  private static String encodeValue(Object value, TypeDescriptor type) throws SQLException {
    if (value instanceof String) {
      return (String) value;
    }
    String text = encodableText(value, type);
    if (text != null) {
      return text;
    }
    throw Codec.cannotEncode(value, type.getTypeName().getName());
  }

  // The text of a matching-type PGobject (a null value as ""), or null when value is not such a PGobject.
  private static @Nullable String encodableText(Object value, TypeDescriptor type) {
    if (value instanceof PGobject
        && type.getTypeName().getName().equals(((PGobject) value).getType())) {
      String v = ((PGobject) value).getValue();
      return v != null ? v : "";
    }
    return null;
  }
}
