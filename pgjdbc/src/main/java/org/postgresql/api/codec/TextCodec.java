/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PgType;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Codec for encoding and decoding PostgreSQL values in text format.
 *
 * <p>Text format is the default wire format for PostgreSQL. While less efficient
 * than binary format, it's universally supported for all types.</p>
 *
 * <p>Implementations must be stateless and thread-safe. All connection-specific
 * settings are provided via {@link CodecContext}.</p>
 *
 * @see BinaryCodec
 * @see CodecContext
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface TextCodec extends Codec {

  /**
   * Decodes a value from text format.
   *
   * @param data the text representation (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the decoded Java object
   * @throws SQLException if decoding fails
   */
  @Nullable Object decodeText(String data, PgType type, CodecContext ctx) throws SQLException;

  /**
   * Encodes a value to text format.
   *
   * @param value the Java object to encode (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the text representation
   * @throws SQLException if encoding fails
   */
  String encodeText(Object value, PgType type, CodecContext ctx) throws SQLException;

  /**
   * Decodes text data as an int value.
   *
   * <p>Default implementation boxes via {@link #decodeText} and unboxes.
   * Numeric codecs should override for efficiency.</p>
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or value overflows int range
   */
  default int decodeAsInt(String data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    throw new SQLException("Cannot convert " + (value == null ? "null" : value.getClass()) + " to int");
  }

  /**
   * Decodes raw text bytes as an int value.
   *
   * <p>This method provides a fast path for numeric codecs that can parse
   * ASCII-encoded numbers directly from bytes without String conversion.</p>
   *
   * <p>Default implementation converts to String and delegates to {@link #decodeAsInt(String, PgType, CodecContext)}.</p>
   *
   * @param data the raw text bytes
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or value overflows int range
   */
  default int decodeTextBytesAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsInt(new String(data, java.nio.charset.StandardCharsets.UTF_8), type, ctx);
  }

  /**
   * Decodes text data as a long value.
   *
   * <p>Default implementation boxes via {@link #decodeText} and unboxes.
   * Numeric codecs should override for efficiency.</p>
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or value overflows long range
   */
  default long decodeAsLong(String data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    throw new SQLException("Cannot convert " + (value == null ? "null" : value.getClass()) + " to long");
  }

  /**
   * Decodes raw text bytes as a long value.
   *
   * <p>This method provides a fast path for numeric codecs that can parse
   * ASCII-encoded numbers directly from bytes without String conversion.</p>
   *
   * <p>Default implementation converts to String and delegates to {@link #decodeAsLong(String, PgType, CodecContext)}.</p>
   *
   * @param data the raw text bytes
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or value overflows long range
   */
  default long decodeTextBytesAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    return decodeAsLong(new String(data, java.nio.charset.StandardCharsets.UTF_8), type, ctx);
  }

  /**
   * Decodes text data as a float value.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the float value
   * @throws SQLException if decoding fails
   */
  default float decodeAsFloat(String data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).floatValue();
    }
    throw new SQLException("Cannot convert " + (value == null ? "null" : value.getClass()) + " to float");
  }

  /**
   * Decodes text data as a double value.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the double value
   * @throws SQLException if decoding fails
   */
  default double decodeAsDouble(String data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    throw new SQLException("Cannot convert " + (value == null ? "null" : value.getClass()) + " to double");
  }

  /**
   * Decodes text data as a boolean value.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the boolean value
   * @throws SQLException if decoding fails
   */
  default boolean decodeAsBoolean(String data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    return org.postgresql.jdbc.BooleanTypeUtil.castAndCheck(
        value, () -> decodeAsString(data, type, ctx));
  }

  /**
   * Decodes text data as a String value.
   *
   * <p>Default implementation decodes and calls {@code toString()}.</p>
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the string value
   * @throws SQLException if decoding fails
   */
  default @Nullable String decodeAsString(String data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    return value == null ? null : value.toString();
  }

  /**
   * Decodes text data as a BigDecimal value.
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the BigDecimal value
   * @throws SQLException if decoding fails
   */
  default @Nullable BigDecimal decodeAsBigDecimal(String data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    if (value instanceof Number) {
      if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
        return BigDecimal.valueOf(((Number) value).longValue());
      }
      return BigDecimal.valueOf(((Number) value).doubleValue());
    }
    throw new SQLException("Cannot convert " + value.getClass() + " to BigDecimal");
  }

  /**
   * Decodes text data into an instance of the specified target class.
   *
   * <p>Codecs implement this method to support conversions to various Java types.
   * For example, a timestamp codec might support conversion to
   * {@code LocalDateTime}, {@code Instant}, {@code OffsetDateTime}, etc.</p>
   *
   * @param data the text data
   * @param type the PostgreSQL type information
   * @param targetClass the desired Java class for the result
   * @param ctx the codec context
   * @param <T> the target type
   * @return the decoded object as the target type
   * @throws SQLException if conversion to the target class is not supported
   */
  default <T> @Nullable T decodeTextAs(String data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (targetClass.isInstance(value)) {
      return targetClass.cast(value);
    }
    throw new SQLException("Conversion to " + targetClass.getName() + " not supported for " + getTypeName());
  }
}
