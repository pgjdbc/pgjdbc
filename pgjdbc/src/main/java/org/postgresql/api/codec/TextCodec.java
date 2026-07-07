/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

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
  @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) throws SQLException;

  /**
   * Decodes a value from a slice of a larger text literal, without copying the
   * slice out into its own {@code String} first.
   *
   * <p>Container codecs (arrays, ranges, composites) call this so each element,
   * bound, or field is decoded in place. The slice is the <em>already-unquoted</em>
   * logical value — the container's tokenizer has stripped the surrounding quotes
   * and unescaped the content, so implementations only parse the value and never
   * deal with array/composite syntax. The {@code data} buffer is a borrowed view
   * valid only during the call; implementations must not retain it.</p>
   *
   * <p>The default copies the slice and delegates to
   * {@link #decodeText(String, TypeDescriptor, CodecContext)}, so codecs that do not
   * override it keep the existing behaviour.</p>
   *
   * @param data backing buffer; only {@code [offset, offset + length)} is this value
   * @param offset start of this value's chars within {@code data}
   * @param length number of chars for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the decoded Java object
   * @throws SQLException if decoding fails
   */
  default @Nullable Object decodeText(char[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    return decodeText(new String(data, offset, length), type, ctx);
  }

  /**
   * Encodes a value to text format.
   *
   * @param value the Java object to encode (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the text representation
   * @throws SQLException if encoding fails
   */
  String encodeText(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException;

  /**
   * Whether this codec's {@link #encodeText} output can contain characters that require quoting
   * when embedded in a composite or array literal (a comma, parenthesis, brace, double quote,
   * backslash, leading/trailing whitespace, or the empty string). Numeric and boolean codecs emit
   * only quote-safe characters (digits, sign, dot, {@code e}, {@code t}/{@code f}, {@code NaN},
   * {@code Infinity}) and return {@code false}, letting a container stream such a field straight
   * into the literal without quoting. The default is {@code true} — assume quoting may be needed.
   *
   * @return true if the text output may need composite/array quoting
   */
  default boolean mayRequireQuoting() {
    return true;
  }

  /**
   * Whether {@link #decodeText} reads the PostgreSQL text wire format for this type. This is the
   * read-side counterpart to {@link BinaryCodec#supportsBinaryRead()}. Text is the universal receive
   * format, so the default is {@code true} and almost every codec keeps it; a codec that handles
   * only the binary representation returns {@code false}. The capability lets a caller pick a
   * readable format without resorting to {@code instanceof}, which matters for the offline and
   * {@code COPY} paths that have no format negotiation to fall back on.
   *
   * @return true if {@link #decodeText} reads the text representation (the default)
   */
  default boolean supportsTextRead() {
    return true;
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
  default @Nullable String decodeAsString(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
  default @Nullable BigDecimal decodeAsBigDecimal(String data, TypeDescriptor type, CodecContext ctx) throws SQLException {
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
      double doubleValue = ((Number) value).doubleValue();
      // BigDecimal has no non-finite form, so a NaN or infinite float refuses rather than letting
      // BigDecimal.valueOf throw an unchecked NumberFormatException. This mirrors the binary float
      // codecs (Float4Codec/Float8Codec.decodeAsBigDecimal), which raise the same state.
      if (Double.isNaN(doubleValue) || Double.isInfinite(doubleValue)) {
        throw new PSQLException(
            GT.tr("Cannot convert {0} to BigDecimal", value),
            PSQLState.NUMERIC_VALUE_OUT_OF_RANGE);
      }
      return BigDecimal.valueOf(doubleValue);
    }
    throw Codec.cannotDecode(value, "BigDecimal");
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
  default <T> @Nullable T decodeTextAs(String data, TypeDescriptor type, Class<T> targetClass, CodecContext ctx)
      throws SQLException {
    Object value = decodeText(data, type, ctx);
    if (value == null) {
      return null;
    }
    if (targetClass.isInstance(value)) {
      return targetClass.cast(value);
    }
    throw Codec.cannotDecode(getTypeName(), targetClass.getName());
  }
}
