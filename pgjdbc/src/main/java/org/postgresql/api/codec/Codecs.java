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
 * <p>{@link #encode} with {@link Format#BINARY} emits the codec's binary wire form. That form
 * round-trips through {@link #decode}, but for codecs whose binary encoder is not a true server-bound
 * representation it is not necessarily what a {@code PreparedStatement} would send — bind such values
 * through a statement instead.</p>
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
      if (!(codec instanceof BinaryCodec)) {
        throw noCodecForFormat(type, "binary");
      }
      return RawValue.binary(((BinaryCodec) codec).encodeBinary(value, type, ctx));
    }
    if (!(codec instanceof TextCodec)) {
      throw noCodecForFormat(type, "text");
    }
    String text = ((TextCodec) codec).encodeText(value, type, ctx);
    return RawValue.text(text.getBytes(ctx.getCharset()));
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
      if (!(codec instanceof BinaryCodec)) {
        throw noCodecForFormat(type, "binary");
      }
      return ((BinaryCodec) codec).decodeBinaryAs(bytesOf(value), type, targetClass, ctx);
    }
    if (!(codec instanceof TextCodec)) {
      throw noCodecForFormat(type, "text");
    }
    return ((TextCodec) codec)
        .decodeTextAs(value.asString(ctx.getCharset()), type, targetClass, ctx);
  }

  /**
   * Returns the value's bytes as a standalone array. Reuses the backing array when the value spans it
   * exactly, so the common {@link #encode}-then-{@link #decode} round-trip copies nothing.
   */
  private static byte[] bytesOf(RawValue value) {
    byte[] backing = value.backingArray();
    if (value.getOffset() == 0 && value.getLength() == backing.length) {
      return backing;
    }
    return value.toByteArray();
  }

  private static SQLException noCodecForFormat(TypeDescriptor type, String format) {
    return new PSQLException(
        GT.tr("No {0} codec is registered for type {1}.", format, type.getFullName()),
        PSQLState.INVALID_PARAMETER_TYPE);
  }
}
