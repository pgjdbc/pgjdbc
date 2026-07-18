/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.api.codec.CodecFormatSupport;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;

/**
 * {@link PGobjectCodec} must report binary-read capability honestly. It implements
 * {@link org.postgresql.api.codec.StreamingBinaryCodec}, so without overriding {@code decodesBinary()}
 * it would inherit the default {@code true} and claim a binary receive its delegate cannot honour —
 * decoding a non-null value as {@code null}. Its capability tracks the delegate for a plain
 * {@link PGobject} subclass, and is unconditionally true for a {@link PGBinaryObject} subclass, which
 * reads the wire itself.
 */
class PGobjectCodecFormatCapabilityTest {

  @Test
  void nonBinaryObjectReadsBinaryOnlyWhenTheDelegateDoes() {
    PGobjectCodec overBinaryDelegate = new PGobjectCodec(PGobject.class, Int4Codec.INSTANCE);
    assertTrue(CodecFormatSupport.canReadBinary(overBinaryDelegate),
        "a binary-reading delegate makes the adapter binary-readable");

    PGobjectCodec overTextOnlyDelegate = new PGobjectCodec(PGobject.class, FallbackCodec.INSTANCE);
    assertFalse(CodecFormatSupport.canReadBinary(overTextOnlyDelegate),
        "a delegate that cannot read binary must not let the adapter claim it");
  }

  @Test
  void binaryObjectAlwaysReadsBinary() {
    PGobjectCodec binaryObjectOverTextOnlyDelegate =
        new PGobjectCodec(BinaryPgObject.class, FallbackCodec.INSTANCE);
    assertTrue(CodecFormatSupport.canReadBinary(binaryObjectOverTextOnlyDelegate),
        "a PGBinaryObject subclass reads the binary wire itself, regardless of the delegate");
  }

  /** A {@link PGBinaryObject} subclass; only its type matters here, the bodies are never called. */
  static final class BinaryPgObject extends PGobject implements PGBinaryObject {
    @Override
    public int lengthInBytes() {
      return 0;
    }

    @Override
    public void toBytes(byte[] bytes, int offset) {
    }

    @Override
    public void setByteValue(byte[] value, int offset) {
    }
  }
}
