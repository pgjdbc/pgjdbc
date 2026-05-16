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
 * Codec for encoding and decoding PostgreSQL values in binary format.
 *
 * <p>Binary format is generally more efficient than text format as it avoids
 * parsing overhead and can represent values more compactly.</p>
 *
 * <p>Implementations must be stateless and thread-safe. All connection-specific
 * settings are provided via {@link CodecContext}.</p>
 *
 * <h2>Primitive Specializations</h2>
 *
 * <p>The interface provides specialized methods for primitive types to avoid boxing
 * overhead. Implementations should override these methods for numeric types:</p>
 * <ul>
 *   <li>{@link #decodeAsInt} - for int4, int2, and similar types</li>
 *   <li>{@link #decodeAsLong} - for int8 and similar types</li>
 *   <li>{@link #decodeAsFloat} - for float4 type</li>
 *   <li>{@link #decodeAsDouble} - for float8 type</li>
 *   <li>{@link #decodeAsBoolean} - for bool type</li>
 * </ul>
 *
 * <h2>Overflow Handling</h2>
 *
 * <p>Implementations MUST check for overflow when converting between numeric types
 * and throw {@link SQLException} on overflow. Reference implementation:
 * {@code PgResultSet.readLongValue()}.</p>
 *
 * @see TextCodec
 * @see CodecContext
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public interface BinaryCodec extends Codec {

  /**
   * Decodes a value from binary format.
   *
   * @param data the binary data (never null, may be empty)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the decoded Java object
   * @throws SQLException if decoding fails
   */
  @Nullable Object decodeBinary(byte[] data, PgType type, CodecContext ctx) throws SQLException;

  /**
   * Encodes a value to binary format.
   *
   * @param value the Java object to encode (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the binary representation
   * @throws SQLException if encoding fails
   */
  byte[] encodeBinary(Object value, PgType type, CodecContext ctx) throws SQLException;

  /**
   * Decodes binary data as an int value.
   *
   * <p>Default implementation boxes via {@link #decodeBinary} and unboxes.
   * Numeric codecs should override for efficiency.</p>
   *
   * <p>IMPORTANT: Implementations MUST check for overflow and throw
   * {@link SQLException} if the value cannot be represented as int.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the int value
   * @throws SQLException if decoding fails or value overflows int range
   */
  default int decodeAsInt(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    throw Codec.cannotConvert(value, "int");
  }

  /**
   * Decodes binary data as a long value.
   *
   * <p>Default implementation boxes via {@link #decodeBinary} and unboxes.
   * Numeric codecs should override for efficiency.</p>
   *
   * <p>IMPORTANT: Implementations MUST check for overflow and throw
   * {@link SQLException} if the value cannot be represented as long.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the long value
   * @throws SQLException if decoding fails or value overflows long range
   */
  default long decodeAsLong(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    throw Codec.cannotConvert(value, "long");
  }

  /**
   * Decodes binary data as a float value.
   *
   * <p>Default implementation boxes via {@link #decodeBinary} and unboxes.
   * Numeric codecs should override for efficiency.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the float value
   * @throws SQLException if decoding fails
   */
  default float decodeAsFloat(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).floatValue();
    }
    throw Codec.cannotConvert(value, "float");
  }

  /**
   * Decodes binary data as a double value.
   *
   * <p>Default implementation boxes via {@link #decodeBinary} and unboxes.
   * Numeric codecs should override for efficiency.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the double value
   * @throws SQLException if decoding fails
   */
  default double decodeAsDouble(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    throw Codec.cannotConvert(value, "double");
  }

  /**
   * Decodes binary data as a boolean value.
   *
   * <p>Default implementation boxes via {@link #decodeBinary} and unboxes.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the boolean value
   * @throws SQLException if decoding fails
   */
  default boolean decodeAsBoolean(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    return org.postgresql.jdbc.BooleanTypeUtil.castAndCheck(
        value, () -> decodeAsString(data, type, ctx));
  }

  /**
   * Decodes binary data as a String value.
   *
   * <p>Default implementation decodes and calls {@code toString()}.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the string value, or null if the decoded value is null
   * @throws SQLException if decoding fails
   */
  default @Nullable String decodeAsString(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    return value == null ? null : value.toString();
  }

  /**
   * Decodes binary data as a BigDecimal value.
   *
   * <p>Default implementation converts from the decoded number.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the BigDecimal value
   * @throws SQLException if decoding fails
   */
  default @Nullable BigDecimal decodeAsBigDecimal(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
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
    throw Codec.cannotConvert(value, "BigDecimal");
  }

  /**
   * Decodes binary data as a byte array.
   *
   * <p>Default implementation expects the decoded value to be a byte array.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the byte array
   * @throws SQLException if decoding fails or value is not a byte array
   */
  default byte @Nullable [] decodeAsBytes(byte[] data, PgType type, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (value instanceof byte[]) {
      return (byte[]) value;
    }
    throw Codec.cannotConvert(value, "byte[]");
  }

  /**
   * Decodes binary data into an instance of the specified target class.
   *
   * <p>Codecs implement this method to support conversions to various Java types.
   * For example, a timestamp codec might support conversion to
   * {@code LocalDateTime}, {@code Instant}, {@code OffsetDateTime}, etc.</p>
   *
   * <p>Note: primitive classes (int.class, long.class) are NOT supported.
   * Use boxed types (Integer.class, Long.class) instead.</p>
   *
   * @param data the binary data
   * @param type the PostgreSQL type information
   * @param targetClass the desired Java class for the result
   * @param ctx the codec context
   * @param <T> the target type
   * @return the decoded object as the target type
   * @throws SQLException if conversion to the target class is not supported
   */
  default <T> @Nullable T decodeBinaryAs(byte[] data, PgType type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    Object value = decodeBinary(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (targetClass.isInstance(value)) {
      return targetClass.cast(value);
    }
    throw Codec.cannotConvert(getTypeName(), targetClass.getName());
  }
}
