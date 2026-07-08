/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

/**
 * Static dispatch for the primitive decode accessors (the read-side counterpart of
 * {@link TextSink}'s and {@link BackpatchingBinarySink}'s typed writers).
 *
 * <p>The primitive accessors ({@code decodeAsInt} and friends) live on the opt-in
 * {@link PrimitiveBinaryDecoder} / {@link PrimitiveTextDecoder} capabilities rather than on the base
 * {@link BinaryCodec} / {@link TextCodec}. A caller that has only a base-typed codec reference routes
 * through the {@code asInt}/{@code asLong}/... helpers here: they call the native primitive method
 * when the codec opts in, and otherwise box through {@link BinaryCodec#decodeBinary} /
 * {@link TextCodec#decodeText} and unbox — the same fallback the base interfaces used to inline. So a
 * codec that cannot decode a primitive (a range, a composite, a timestamp) simply does not implement
 * the capability, and the fallback raises the standard "cannot convert" error.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class PrimitiveDecoders {

  private PrimitiveDecoders() {
  }

  // ===========================================================================
  // Binary
  // ===========================================================================

  /** Decodes {@code data} as a int; see {@link #asInt(BinaryCodec, byte[], int, int, TypeDescriptor, CodecContext)}. */
  public static int asInt(BinaryCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return asInt(codec, data, 0, data.length, type, ctx);
  }

  /** Decodes {@code data} as a long; see {@link #asLong(BinaryCodec, byte[], int, int, TypeDescriptor, CodecContext)}. */
  public static long asLong(BinaryCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return asLong(codec, data, 0, data.length, type, ctx);
  }

  /** Decodes {@code data} as a float; see {@link #asFloat(BinaryCodec, byte[], int, int, TypeDescriptor, CodecContext)}. */
  public static float asFloat(BinaryCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return asFloat(codec, data, 0, data.length, type, ctx);
  }

  /** Decodes {@code data} as a double; see {@link #asDouble(BinaryCodec, byte[], int, int, TypeDescriptor, CodecContext)}. */
  public static double asDouble(BinaryCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return asDouble(codec, data, 0, data.length, type, ctx);
  }

  /** Decodes {@code data} as a boolean; see {@link #asBoolean(BinaryCodec, byte[], int, int, TypeDescriptor, CodecContext)}. */
  public static boolean asBoolean(BinaryCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    return asBoolean(codec, data, 0, data.length, type, ctx);
  }

  // ---------------------------------------------------------------------------
  // Binary, slice form -- decode a value in place off a larger buffer. A wrapping
  // codec (a domain over its base type) forwards the slice straight through.
  // ---------------------------------------------------------------------------

  /** Slice form of {@link #asInt(BinaryCodec, byte[], TypeDescriptor, CodecContext)}. */
  public static int asInt(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsInt(data, offset, length, type, ctx);
    }
    return boxToInt(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /** Slice form of {@link #asLong(BinaryCodec, byte[], TypeDescriptor, CodecContext)}. */
  public static long asLong(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsLong(data, offset, length, type, ctx);
    }
    return boxToLong(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /** Slice form of {@link #asFloat(BinaryCodec, byte[], TypeDescriptor, CodecContext)}. */
  public static float asFloat(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsFloat(data, offset, length, type, ctx);
    }
    return boxToFloat(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /** Slice form of {@link #asDouble(BinaryCodec, byte[], TypeDescriptor, CodecContext)}. */
  public static double asDouble(BinaryCodec codec, byte[] data, int offset, int length, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsDouble(data, offset, length, type, ctx);
    }
    return boxToDouble(codec.decodeBinary(data, offset, length, type, ctx));
  }

  /** Slice form of {@link #asBoolean(BinaryCodec, byte[], TypeDescriptor, CodecContext)}. */
  public static boolean asBoolean(BinaryCodec codec, byte[] data, int offset, int length,
      TypeDescriptor type, CodecContext ctx) throws SQLException {
    if (codec instanceof PrimitiveBinaryDecoder) {
      return ((PrimitiveBinaryDecoder) codec).decodeAsBoolean(data, offset, length, type, ctx);
    }
    return BooleanCoercion.castAndCheck(
        codec.decodeBinary(data, offset, length, type, ctx),
        () -> codec.decodeAsString(data, offset, length, type, ctx));
  }

  // ===========================================================================
  // Text
  // ===========================================================================

  /**
   * Decodes {@code data} as an int through {@code codec}'s native path when it implements
   * {@link PrimitiveTextDecoder}, otherwise by boxing through {@link TextCodec#decodeText}.
   */
  public static int asInt(TextCodec codec, String data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsInt(data, type, ctx);
    }
    return boxToInt(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a long; see {@link #asInt(TextCodec, String, TypeDescriptor, CodecContext)}. */
  public static long asLong(TextCodec codec, String data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsLong(data, type, ctx);
    }
    return boxToLong(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a float; see {@link #asInt(TextCodec, String, TypeDescriptor, CodecContext)}. */
  public static float asFloat(TextCodec codec, String data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsFloat(data, type, ctx);
    }
    return boxToFloat(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a double; see {@link #asInt(TextCodec, String, TypeDescriptor, CodecContext)}. */
  public static double asDouble(TextCodec codec, String data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsDouble(data, type, ctx);
    }
    return boxToDouble(codec.decodeText(data, type, ctx));
  }

  /** Decodes {@code data} as a boolean; see {@link #asInt(TextCodec, String, TypeDescriptor, CodecContext)}. */
  public static boolean asBoolean(TextCodec codec, String data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeAsBoolean(data, type, ctx);
    }
    return BooleanCoercion.castAndCheck(
        codec.decodeText(data, type, ctx), () -> codec.decodeAsString(data, type, ctx));
  }

  /**
   * Decodes ASCII text bytes as an int through {@code codec}'s native fast path when it implements
   * {@link PrimitiveTextDecoder}, otherwise by decoding the bytes to a {@code String} and boxing.
   */
  public static int asIntFromTextBytes(TextCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeTextBytesAsInt(data, type, ctx);
    }
    return asInt(codec, new String(data, UTF_8), type, ctx);
  }

  /** Decodes ASCII text bytes as a long; see {@link #asIntFromTextBytes}. */
  public static long asLongFromTextBytes(TextCodec codec, byte[] data, TypeDescriptor type, CodecContext ctx)
      throws SQLException {
    if (codec instanceof PrimitiveTextDecoder) {
      return ((PrimitiveTextDecoder) codec).decodeTextBytesAsLong(data, type, ctx);
    }
    return asLong(codec, new String(data, UTF_8), type, ctx);
  }

  // ===========================================================================
  // Boxing fallbacks, shared with the interface defaults
  // ===========================================================================

  static int boxToInt(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    throw Codec.cannotDecode(value, "int");
  }

  static long boxToLong(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).longValue();
    }
    throw Codec.cannotDecode(value, "long");
  }

  static float boxToFloat(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).floatValue();
    }
    throw Codec.cannotDecode(value, "float");
  }

  static double boxToDouble(@Nullable Object value) throws SQLException {
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    throw Codec.cannotDecode(value, "double");
  }
}
