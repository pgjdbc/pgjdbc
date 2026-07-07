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

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Codec for PostgreSQL enum types.
 *
 * <p>Enum types are decoded as String only. PostgreSQL enum → String conversion.
 * For setObject with Java enum, application should convert to String first.
 * No automatic Java Enum ↔ PostgreSQL enum mapping.</p>
 *
 * <p>This is a singleton codec used for all enum types - the codec resolution
 * happens via typtype='e' check in the codec registry.</p>
 */
public final class EnumCodec implements BinaryCodec, TextCodec {

  public static final EnumCodec INSTANCE = new EnumCodec();

  private EnumCodec() {
    // Singleton
  }

  @Override
  public String getTypeName() {
    // This codec handles all enum types (typtype='e')
    return "enum";
  }

  @Override
  public Class<?> getDefaultJavaType() {
    return String.class;
  }

  @Override
  public @Nullable Object decodeBinary(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Binary format for enum is the text representation as bytes
    return new String(data, ctx.getCharset());
  }

  @Override
  public byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    String stringValue = toEnumString(value);
    return stringValue.getBytes(ctx.getCharset());
  }

  @Override
  public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    // Text format - return as String directly
    return data;
  }

  @Override
  public String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return toEnumString(value);
  }

  @Override
  public @Nullable String decodeAsString(byte[] data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return new String(data, ctx.getCharset());
  }

  @Override
  public @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return data;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeBinaryAs(byte[] data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) decodeAsString(data, type, ctx);
    }
    throw Codec.cannotDecode("enum", targetClass.getName());
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    if (targetClass == String.class || targetClass == Object.class) {
      return (T) data;
    }
    throw Codec.cannotDecode("enum", targetClass.getName());
  }

  /**
   * Converts a Java value to its enum string representation.
   *
   * @param value the Java value (String or Enum)
   * @return the string representation
   * @throws SQLException if the value cannot be converted
   */
  private static String toEnumString(Object value) throws SQLException {
    if (value instanceof String) {
      return (String) value;
    }
    if (value instanceof Enum) {
      // For Java enums, use name() to get the exact enum constant name
      return ((Enum<?>) value).name();
    }
    // Allow any object via toString() for flexibility
    return value.toString();
  }
}
