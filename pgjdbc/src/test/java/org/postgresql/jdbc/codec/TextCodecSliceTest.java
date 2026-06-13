/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.api.codec.TextCodec;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Arrays;

/**
 * Verifies the slice form {@code decodeText(char[], offset, length, ...)} for the
 * integer codecs that override it. Each value decodes the same whether it is passed
 * as a standalone String, as a whole-buffer slice, or embedded inside a larger
 * char[] with surrounding noise. Container codecs (ranges, composites and the
 * generic array leaf) decode each element through this path to skip a per-value
 * String, so the slice result must match the String result exactly.
 *
 * <p>End-to-end coverage through {@code RangeCodec} / {@code CompositeCodec} with a
 * live element codec lives in the integration suites; resolving the subtype codec
 * needs a real {@code CodecContext}.</p>
 */
class TextCodecSliceTest {

  private static final PgType ANY = new PgType(
      new ObjectName("pg_catalog", "int4"), "integer", Oid.INT4, 'b', 'N', -1, 0, 0, 0);
  private static final CodecContext CTX = TestCodecContext.create();

  /** Embeds {@code value} at offset 5 of a noise-filled char[] with trailing padding. */
  private static char[] embed(String value) {
    char[] buf = new char[5 + value.length() + 3];
    Arrays.fill(buf, '#'); // non-digit noise to catch offset/length bugs
    value.getChars(0, value.length(), buf, 5);
    return buf;
  }

  /** Decodes {@code text} via the String form, the whole-buffer slice and an embedded slice. */
  private static void assertSliceMatches(TextCodec codec, String text) throws SQLException {
    Object viaString = codec.decodeText(text, ANY, CTX);
    char[] whole = text.toCharArray();
    assertEquals(viaString, codec.decodeText(whole, 0, whole.length, ANY, CTX),
        "whole-buffer slice");
    assertEquals(viaString, codec.decodeText(embed(text), 5, text.length(), ANY, CTX),
        "embedded slice");
  }

  @Test
  void int2_sliceMatchesString() throws SQLException {
    for (String v : new String[]{"0", "-1", "1234", "32767", "-32768"}) {
      assertSliceMatches(Int2Codec.INSTANCE, v);
    }
    assertEquals(1234, Int2Codec.INSTANCE.decodeText(embed("1234"), 5, 4, ANY, CTX));
  }

  @Test
  void int4_sliceMatchesString() throws SQLException {
    for (String v : new String[]{"0", "-1", "123456", "2147483647", "-2147483648"}) {
      assertSliceMatches(Int4Codec.INSTANCE, v);
    }
    assertEquals(-123456, Int4Codec.INSTANCE.decodeText(embed("-123456"), 5, 7, ANY, CTX));
  }

  @Test
  void int8_sliceMatchesString() throws SQLException {
    for (String v : new String[]{"0", "-1", "9000000000",
        "9223372036854775807", "-9223372036854775808"}) {
      assertSliceMatches(Int8Codec.INSTANCE, v);
    }
    assertEquals(9_000_000_000L,
        Int8Codec.INSTANCE.decodeText(embed("9000000000"), 5, 10, ANY, CTX));
  }

  @Test
  void oid_sliceMatchesString() throws SQLException {
    // 4000000000 exceeds Integer.MAX_VALUE; oid text decodes as the full signed long.
    for (String v : new String[]{"0", "16384", "4000000000"}) {
      assertSliceMatches(OidCodec.INSTANCE, v);
    }
    assertEquals(4_000_000_000L,
        OidCodec.INSTANCE.decodeText(embed("4000000000"), 5, 10, ANY, CTX));
  }

  @Test
  void fallbackPath_leadingPlus_matchesStringParser() throws SQLException {
    // A leading '+' is not part of the fast path; both forms fall back to the
    // String integer parser and yield the same value.
    assertEquals(Int4Codec.INSTANCE.decodeText("+5", ANY, CTX),
        Int4Codec.INSTANCE.decodeText("+5".toCharArray(), 0, 2, ANY, CTX));
  }

  @Test
  void outOfRange_throwsLikeStringForm() {
    // An int2 overflow must fail through the slice form exactly as through the String form.
    assertThrows(SQLException.class, () -> Int2Codec.INSTANCE.decodeText("99999", ANY, CTX));
    assertThrows(SQLException.class,
        () -> Int2Codec.INSTANCE.decodeText("99999".toCharArray(), 0, 5, ANY, CTX));
  }

  @Test
  void invalidDigits_throwsLikeStringForm() {
    assertThrows(SQLException.class,
        () -> Int4Codec.INSTANCE.decodeText(embed("12x4"), 5, 4, ANY, CTX));
  }

  @Test
  void defaultSlice_copiesWindow_whenNotOverridden() throws SQLException {
    // float8 and bool keep the TextCodec default, which copies the window out to a
    // String; it must still read exactly the [offset, offset + length) slice.
    assertEquals(Float8Codec.INSTANCE.decodeText("2.5", ANY, CTX),
        Float8Codec.INSTANCE.decodeText(embed("2.5"), 5, 3, ANY, CTX));
    assertEquals(BoolCodec.INSTANCE.decodeText("t", ANY, CTX),
        BoolCodec.INSTANCE.decodeText(embed("t"), 5, 1, ANY, CTX));
  }
}
