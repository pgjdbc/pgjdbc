/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import java.sql.SQLException;

/**
 * Format-capability facts about a {@link Codec}, and the enforcement helpers built on them.
 *
 * <p>The four {@code can…} predicates are the authoritative answer to whether a codec reads or
 * writes a given wire {@link Format}. Each folds the {@code instanceof} check and the codec's own
 * capability methods ({@link BinaryCodec#decodesBinary()}, {@link BinaryCodec#canEncodeBinary},
 * {@link TextCodec#decodesText()}) into one result, so callers that pick or enforce a format decide
 * it the same way instead of testing {@code instanceof} and the capability flags separately.</p>
 *
 * <p>The narrowing casts stay confined here: a predicate answers the capability question, and the
 * matching {@code require…} helper returns the narrowed codec or fails with a consistent error. A
 * write-side binary check is value-level ({@link #canWriteBinary} takes the value, since a codec may
 * binary-encode some values and not others); the read-side and text checks depend only on the
 * codec.</p>
 *
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public final class CodecFormatSupport {

  private CodecFormatSupport() {
  }

  /**
   * Whether {@code codec} reads the binary wire form for its type.
   *
   * @param codec the codec to inspect
   * @return true if {@code codec} is a {@link BinaryCodec} that decodes binary
   */
  public static boolean canReadBinary(Codec codec) {
    return codec instanceof BinaryCodec && ((BinaryCodec) codec).decodesBinary();
  }

  /**
   * Whether {@code codec} can write {@code value} as a real binary payload for {@code type}: it is a
   * {@link BinaryCodec}, its type-level {@link BinaryCodec#encodesBinary()} flag is set, and its
   * value-level {@link BinaryCodec#canEncodeBinary} accepts this value. Both flags are checked here
   * rather than trusting {@code canEncodeBinary} to fold in {@code encodesBinary()}, so a codec that
   * declares {@code encodesBinary()=false} cannot be coerced into a binary encode by a lax
   * {@code canEncodeBinary} override.
   *
   * @param codec the codec to inspect
   * @param value the value to be encoded
   * @param type the target type metadata
   * @param ctx the codec context
   * @return true if {@code codec} is a {@link BinaryCodec} that can binary-encode {@code value}
   * @throws SQLException if type metadata cannot be resolved
   */
  public static boolean canWriteBinary(Codec codec, Object value, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (!(codec instanceof BinaryCodec)) {
      return false;
    }
    BinaryCodec binary = (BinaryCodec) codec;
    return binary.encodesBinary() && binary.canEncodeBinary(value, type, ctx);
  }

  /**
   * Whether {@code codec} reads the text wire form for its type.
   *
   * @param codec the codec to inspect
   * @return true if {@code codec} is a {@link TextCodec} that decodes text
   */
  public static boolean canReadText(Codec codec) {
    return codec instanceof TextCodec && ((TextCodec) codec).decodesText();
  }

  /**
   * Whether {@code codec} can write text. Text encoding is mandatory once a codec implements
   * {@link TextCodec}, so this is exactly {@code codec instanceof TextCodec}.
   *
   * @param codec the codec to inspect
   * @return true if {@code codec} is a {@link TextCodec}
   */
  public static boolean canWriteText(Codec codec) {
    return codec instanceof TextCodec;
  }

  // The require… helpers narrow the codec for a fixed, caller-chosen format, or fail. They exist so
  // Codecs.encode/decode enforce the requested format through the same predicates a negotiating
  // caller would consult, rather than each site re-deriving the instanceof + capability check.

  static BinaryCodec requireBinaryEncoder(Codec codec, Object value, TypeDescriptor type,
      CodecContext ctx) throws SQLException {
    if (!canWriteBinary(codec, value, type, ctx)) {
      throw Exceptions.noCodecForFormat(type, "binary");
    }
    return (BinaryCodec) codec;
  }

  static TextCodec requireTextEncoder(Codec codec, TypeDescriptor type) throws SQLException {
    if (!canWriteText(codec)) {
      throw Exceptions.noCodecForFormat(type, "text");
    }
    return (TextCodec) codec;
  }

  static BinaryCodec requireBinaryDecoder(Codec codec, TypeDescriptor type) throws SQLException {
    if (!canReadBinary(codec)) {
      throw Exceptions.noCodecForFormat(type, "binary");
    }
    return (BinaryCodec) codec;
  }

  static TextCodec requireTextDecoder(Codec codec, TypeDescriptor type) throws SQLException {
    if (!canReadText(codec)) {
      throw Exceptions.noCodecForFormat(type, "text");
    }
    return (TextCodec) codec;
  }
}
