/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Encodes a Java value to its PostgreSQL wire form and decodes it back, without a
 * {@link java.sql.ResultSet} or {@link java.sql.PreparedStatement}.
 *
 * <p>Both calls resolve the codec from {@code ctx} by the descriptor's OID
 * ({@link CodecContext#resolveCodec(int)}), so they work with any {@link CodecContext}, including
 * the connectionless one built offline. Scalar and temporal types round-trip offline; container
 * types (array, composite, range, domain) still need a live connection and report that with a clear
 * error rather than a silent failure.</p>
 *
 * <p>Both directions enforce the codec's format capability. {@link #encode} to a {@link Format} the
 * codec cannot produce for the value, or {@link #decode} of a value whose format the codec cannot
 * read, fails rather than returning bytes the far side cannot interpret. A successful {@code encode}
 * to {@link Format#BINARY} is therefore a real server binary payload. The offline and
 * {@code COPY BINARY} paths rely on this, since they have no format negotiation to fall back on.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class Codecs {

  private Codecs() {
  }

  /**
   * Encodes {@code value} as {@code type} in the requested {@code format}.
   *
   * @param value the value to encode (must be non-null; SQL NULL has no wire bytes)
   * @param type the PostgreSQL type to encode as
   * @param ctx the codec context that resolves the codec and carries the wire settings
   * @param format the target wire format
   * @return the encoded value, backed by a freshly allocated array
   * @throws SQLException if no codec for {@code type} supports {@code format}, or encoding fails
   */
  public static RawValue encode(Object value, TypeDescriptor type, CodecContext ctx, Format format)
      throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    if (format == Format.BINARY) {
      BinaryCodec binary = CodecFormatSupport.requireBinaryEncoder(codec, value, type, ctx);
      return RawValue.binary(binary.encodeBinary(value, type, ctx));
    }
    TextCodec text = CodecFormatSupport.requireTextEncoder(codec, type);
    return RawValue.text(text.encodeText(value, type, ctx).getBytes(ctx.getCharset()));
  }

  /**
   * Decodes {@code value} as {@code type} into {@code targetClass}, using the wire format the value
   * carries.
   *
   * <p>{@code targetClass} must be a reference type; pass {@code Integer.class}, not {@code int.class}.
   * A codec rejects a target it cannot produce.</p>
   *
   * @param value the wire value to decode
   * @param type the PostgreSQL type of the value
   * @param ctx the codec context that resolves the codec and carries the wire settings
   * @param targetClass the Java type to decode into
   * @param <T> the target type
   * @return the decoded value, or null if the codec decodes it as null
   * @throws SQLException if no codec for {@code type} supports the value's format, or decoding fails
   */
  public static <T> @Nullable T decode(RawValue value, TypeDescriptor type, CodecContext ctx,
      Class<T> targetClass) throws SQLException {
    Codec codec = ctx.resolveCodec(type.getOid());
    if (value.getFormat() == Format.BINARY) {
      BinaryCodec binary = CodecFormatSupport.requireBinaryDecoder(codec, type);
      return binary.decodeBinaryAs(
          value.backingArray(), value.getOffset(), value.getLength(), type, targetClass, ctx);
    }
    TextCodec text = CodecFormatSupport.requireTextDecoder(codec, type);
    return text.decodeTextAs(value.asString(ctx.getCharset()), type, targetClass, ctx);
  }

  /**
   * Creates a standard error for a value read from the database that cannot be
   * represented as {@code targetType}.
   *
   * <p>This is the read/decode direction (for example {@code getDate} on an int4
   * column), so the error carries {@link org.postgresql.util.PSQLState#DATA_TYPE_MISMATCH}.</p>
   *
   * <p>Public (unlike {@link Exceptions}) because codecs outside this package — and outside
   * {@code org.postgresql.jdbc.codec} — need to report the same conversion errors.</p>
   *
   * @param value decoded value, or null when the decoded value is SQL NULL
   * @param targetType target Java type name
   * @return conversion error
   */
  public static SQLException cannotDecode(@Nullable Object value, String targetType) {
    return cannotDecode(value == null ? "null" : value.getClass().getName(), targetType);
  }

  /**
   * Creates a standard decode error from a source type name to a target Java type name.
   *
   * @param sourceType source type name
   * @param targetType target Java type name
   * @return conversion error, carrying {@link org.postgresql.util.PSQLState#DATA_TYPE_MISMATCH}
   */
  public static SQLException cannotDecode(String sourceType, String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to {1}", sourceType, targetType),
        PSQLState.DATA_TYPE_MISMATCH);
  }

  /**
   * Creates a standard error for a Java value that cannot be encoded as the codec's PostgreSQL
   * type.
   *
   * @param value the value being encoded, or null
   * @param targetType target PostgreSQL type name
   * @return conversion error, carrying {@link org.postgresql.util.PSQLState#INVALID_PARAMETER_TYPE}
   */
  public static SQLException cannotEncode(@Nullable Object value, String targetType) {
    return cannotEncode(value == null ? "null" : value.getClass().getName(), targetType);
  }

  /**
   * Creates a standard encode error from a source type name to a target PostgreSQL type name.
   *
   * @param sourceType source type name
   * @param targetType target PostgreSQL type name
   * @return conversion error, carrying {@link org.postgresql.util.PSQLState#INVALID_PARAMETER_TYPE}
   */
  public static SQLException cannotEncode(String sourceType, String targetType) {
    return new PSQLException(
        GT.tr("Cannot convert {0} to {1}", sourceType, targetType),
        PSQLState.INVALID_PARAMETER_TYPE);
  }

  // Composite binary wire format: shared by org.postgresql.jdbc.codec.CompositeCodec (via its own
  // package-private Exceptions) and org.postgresql.jdbc.PgSQLInputBinary (via its own), which parse
  // the same wire format from two different entry points.

  /**
   * The binary composite value was too short to hold a field count.
   *
   * @return decode error, carrying {@link org.postgresql.util.PSQLState#DATA_ERROR}
   */
  public static SQLException invalidCompositeTooShort() {
    return new PSQLException(GT.tr("Invalid binary composite data: too short"), PSQLState.DATA_ERROR);
  }

  /**
   * The binary composite value declared a negative field count.
   *
   * @param fieldCount the (negative) declared field count
   * @return decode error, carrying {@link org.postgresql.util.PSQLState#DATA_ERROR}
   */
  public static SQLException invalidCompositeNegativeFieldCount(int fieldCount) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: negative field count {0}", fieldCount),
        PSQLState.DATA_ERROR);
  }

  /**
   * The binary composite value ended before its declared field count was satisfied.
   *
   * @param fieldIndex the 0-based field index where the data ran out
   * @return decode error, carrying {@link org.postgresql.util.PSQLState#DATA_ERROR}
   */
  public static SQLException invalidCompositeUnexpectedEnd(int fieldIndex) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: unexpected end at field {0}", fieldIndex),
        PSQLState.DATA_ERROR);
  }

  /**
   * A composite field declared a negative length.
   *
   * @param length the (negative) declared field length
   * @param fieldIndex the 0-based field index
   * @return decode error, carrying {@link org.postgresql.util.PSQLState#DATA_ERROR}
   */
  public static SQLException invalidCompositeFieldLength(int length, int fieldIndex) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: invalid length {0} at field {1}", length, fieldIndex),
        PSQLState.DATA_ERROR);
  }

  /**
   * A composite field's declared length exceeds the remaining data.
   *
   * @param fieldIndex the 0-based field index
   * @return decode error, carrying {@link org.postgresql.util.PSQLState#DATA_ERROR}
   */
  public static SQLException invalidCompositeNotEnoughData(int fieldIndex) {
    return new PSQLException(
        GT.tr("Invalid binary composite data: not enough data for field {0}", fieldIndex),
        PSQLState.DATA_ERROR);
  }
}
