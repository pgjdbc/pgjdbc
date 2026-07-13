/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
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
 * <h2>Slice-based decoding</h2>
 *
 * <p>Every decode method is expressed over a slice {@code data[offset, offset + length)} of a larger
 * buffer, so a container codec (array, range, composite) decodes each element, bound, or field in
 * place without a per-element {@link java.util.Arrays#copyOfRange}. A codec implements the primary
 * {@link #decodeBinary(byte[], int, int, TypeDescriptor, CodecContext)}; the whole-array
 * {@code decodeBinary(byte[], ...)} and the {@code decodeAsString}/{@code decodeAsBytes}/
 * {@code decodeBinaryAs} accessors are convenience defaults that fan out from it. The
 * {@code decodeAsBigDecimal} accessor is an opt-in {@link PrimitiveBinaryDecoder} capability rather
 * than a base default. A codec overrides a slice accessor only when its result differs from decoding
 * through
 * {@code decodeBinary} and converting (for example {@code bytea}'s hex text, or a numeric codec that
 * converts to {@code Long}/{@code Double}).</p>
 *
 * <h2>Primitive Specializations</h2>
 *
 * <p>Decoding a value to a Java primitive without boxing it first is an opt-in capability: a codec
 * that can produce a primitive from its binary wire form implements {@link PrimitiveBinaryDecoder}.
 * A caller holding a base-typed reference goes through {@link PrimitiveDecoders}, which falls back to
 * boxing through {@link #decodeBinary} when the codec does not implement that capability.</p>
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
   * Decodes the value in {@code data[offset, offset + length)} from binary format.
   *
   * <p>This is the primary decode method every {@link BinaryCodec} implements. Container codecs
   * (arrays, ranges, composites) call it so each element, bound, or field is decoded in place instead
   * of through a per-element {@link java.util.Arrays#copyOfRange}.</p>
   *
   * @param data the backing buffer; only {@code [offset, offset + length)} is this value
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the decoded Java object
   * @throws SQLException if decoding fails
   */
  @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException;

  /**
   * Encodes a value to binary format.
   *
   * @param value the Java object to encode (never null)
   * @param type the PostgreSQL type information
   * @param ctx the codec context providing connection settings
   * @return the binary representation
   * @throws SQLException if encoding fails
   */
  byte[] encodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException;

  /**
   * Whether {@link #encodeBinary} produces a true PostgreSQL binary representation. Codecs whose
   * {@code encodeBinary} only emits the text encoding as bytes (for example the {@code time}/
   * {@code timestamp} codecs, which have no binary parameter encoder) return {@code false}, so
   * containers such as {@link org.postgresql.jdbc.codec.ArrayCodec} bind their values as text rather
   * than feeding the server an invalid binary payload.
   *
   * @return true if {@code encodeBinary} emits a real binary representation (the default)
   */
  default boolean encodesBinary() {
    return true;
  }

  /**
   * Whether {@code value} can be encoded for {@code type} as a real PostgreSQL binary payload. This
   * is the value-level companion to {@link #encodesBinary()}: that method decides at the
   * type level (a codec whose {@code encodeBinary} only emits text bytes returns {@code false}),
   * while this one lets a codec reject a particular value whose binary form it cannot produce. A
   * composite codec, for instance, binary-encodes only {@link java.sql.Struct} / {@link
   * java.sql.SQLData} / {@link org.postgresql.util.PGBinaryObject} values and binds a plain {@link
   * org.postgresql.util.PGobject} as text, while an array codec needs every leaf to be
   * binary-encodable. Containers and parameter-binding callers gate the binary path on this rather
   * than on {@link #encodesBinary()} alone. The default defers to {@link
   * #encodesBinary()}, so value-independent codecs need not override it.
   *
   * @param value the value to be encoded
   * @param type the target type metadata
   * @param ctx the codec context
   * @return true if {@code value} can be encoded as a real binary representation
   * @throws SQLException if type metadata cannot be resolved
   */
  default boolean canEncodeBinary(Object value, TypeDescriptor type, CodecContext ctx) throws SQLException {
    return encodesBinary();
  }

  /**
   * Whether {@link #decodeBinary} reads the real PostgreSQL binary wire format for this type. This
   * is the read-side counterpart to {@link #encodesBinary()}, and the capability the driver
   * gates binary <em>receive</em> on: only a type whose codec returns {@code true} is requested in
   * binary, so a codec that implements {@link BinaryCodec} only for the bind/encode direction (or
   * that cannot parse the server's binary representation) returns {@code false} and stays in text.
   * The default is {@code true}, so a codec that decodes binary needs no override.
   *
   * @return true if {@link #decodeBinary} reads the real binary representation (the default)
   */
  default boolean decodesBinary() {
    return true;
  }

  /**
   * Decodes {@code data[offset, offset + length)} as a String.
   *
   * <p>The default decodes through
   * {@link #decodeBinary(byte[], int, int, TypeDescriptor, CodecContext)} and calls {@code toString()}.
   * A codec whose canonical text differs from the decoded object's {@code toString()} — for example
   * {@code bytea}'s hex form — overrides this to read the slice directly.</p>
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the string value, or null if the decoded value is null
   * @throws SQLException if decoding fails
   */
  default @Nullable String decodeAsString(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, offset, length, type, ctx);
    return value == null ? null : value.toString();
  }

  /**
   * Decodes {@code data[offset, offset + length)} as a byte array.
   *
   * <p>The default expects the value decoded through
   * {@link #decodeBinary(byte[], int, int, TypeDescriptor, CodecContext)} to be a byte array.</p>
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param ctx the codec context
   * @return the byte array
   * @throws SQLException if decoding fails or value is not a byte array
   */
  default byte @Nullable [] decodeAsBytes(byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, offset, length, type, ctx);
    if (value == null) {
      return null;
    }
    if (value instanceof byte[]) {
      return (byte[]) value;
    }
    throw Codecs.cannotDecode(value, "byte[]");
  }

  /**
   * Decodes {@code data[offset, offset + length)} into an instance of {@code targetClass}.
   *
   * <p>Codecs override this to support conversions to various Java types — a timestamp codec to
   * {@code LocalDateTime}/{@code Instant}/{@code OffsetDateTime}, a numeric codec to
   * {@code Long}/{@code Double}, and so on. The default returns the value decoded through
   * {@link #decodeBinary(byte[], int, int, TypeDescriptor, CodecContext)} when it is already an
   * instance of {@code targetClass}, and otherwise fails.</p>
   *
   * <p>Note: primitive classes (int.class, long.class) are NOT supported.
   * Use boxed types (Integer.class, Long.class) instead.</p>
   *
   * @param data the backing buffer
   * @param offset start of this value's bytes within {@code data}
   * @param length number of bytes for this value
   * @param type the PostgreSQL type information
   * @param targetClass the desired Java class for the result
   * @param ctx the codec context
   * @param <T> the target type
   * @return the decoded object as the target type
   * @throws SQLException if conversion to the target class is not supported
   */
  default <T> @Nullable T decodeBinaryAs(byte[] data, int offset, int length, TypeDescriptor type,
      Class<T> targetClass, CodecContext ctx) throws SQLException {
    Object value = decodeBinary(data, offset, length, type, ctx);
    if (value == null) {
      return null;
    }
    if (targetClass.isInstance(value)) {
      return targetClass.cast(value);
    }
    throw Codecs.cannotDecode(getTypeName(), targetClass.getName());
  }

  static void writeElement(BackpatchingBinarySink out, Object element, BinaryCodec codec, TypeDescriptor type,
      CodecContext ctx) throws IOException, SQLException {
    if (codec instanceof StreamingBinaryCodec) {
      int lengthSlot = out.reserveInt32();
      int startPos = out.position();
      ((StreamingBinaryCodec) codec).encodeBinary(element, type, ctx, out);
      out.setInt32At(lengthSlot, out.position() - startPos);
    } else {
      byte[] encoded = codec.encodeBinary(element, type, ctx);
      out.writeInt32(encoded.length);
      out.write(encoded);
    }
  }
}
