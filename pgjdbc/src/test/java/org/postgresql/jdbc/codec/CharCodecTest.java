/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.PgType;
import org.postgresql.jdbc.TestCodecContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

class CharCodecTest {

  private CharCodec codec;
  private PgType charType;
  private CodecContext ctx;

  @BeforeEach
  void setUp() {
    codec = CharCodec.INSTANCE;
    charType = new PgType(
        new ObjectName("pg_catalog", "char"),
        "\"char\"",
        Oid.CHAR,
        'b', 'Z', -1, 0, 0, 0
    );
    ctx = TestCodecContext.create();
  }

  @Test
  void encodeBinary_emptyString_isSingleZeroByte() throws SQLException {
    // The server's charin maps "" to '\0', whose charsend wire is a single 0x00. The generic charset
    // encoder emits zero bytes for "", which the server's charrecv rejects as insufficient data, so
    // CharCodec must emit [0x00].
    assertArrayEquals(new byte[]{0}, codec.encodeBinary("", charType, ctx));
  }

  @Test
  void encodeBinary_nonEmptyString_isUnchanged() throws SQLException {
    // A one-byte value still encodes as its charset byte; the empty-string special case must not disturb it.
    assertArrayEquals("A".getBytes(StandardCharsets.UTF_8), codec.encodeBinary("A", charType, ctx));
  }

  @Test
  void decodeBinary_zeroByte_isEmptyString() throws SQLException {
    // charout renders '\0' as the empty string; the binary decode normalises 0x00 to "" so it agrees with
    // the text wire instead of yielding a one-character U+0000 string.
    assertEquals("", codec.decodeBinary(new byte[]{0}, 0, 1, charType, ctx));
    assertEquals("", codec.decodeBinaryAs(new byte[]{0}, 0, 1, charType, String.class, ctx));
  }

  @Test
  void decodeBinary_nonZeroByte_isThatCharacter() throws SQLException {
    // A non-zero byte still decodes to its own character; the '\0' normalisation must not disturb it.
    assertEquals("A", codec.decodeBinary("A".getBytes(StandardCharsets.UTF_8), 0, 1, charType, ctx));
  }

  @Test
  void emptyString_roundTripsThroughBinary() throws SQLException {
    // "" -> 0x00 -> "": the '\0' char is the empty string in both directions, so the binary round-trip is
    // an identity (and matches the server, whose charin("")='\0' and charout('\0')="").
    byte[] wire = codec.encodeBinary("", charType, ctx);
    assertArrayEquals(new byte[]{0}, wire);
    assertEquals("", codec.decodeBinary(wire, 0, wire.length, charType, ctx));
  }

  @Test
  void decodeBinary_highByte_isBackslashOctalEscape() throws SQLException {
    // charout escapes a high byte as a backslash + three octal digits; the binary decode reproduces it so
    // getString matches the text wire and the server's ::text (rather than a charset U+FFFD).
    assertEquals("\\200", codec.decodeBinary(new byte[]{(byte) 0x80}, 0, 1, charType, ctx));
    assertEquals("\\377", codec.decodeBinary(new byte[]{(byte) 0xFF}, 0, 1, charType, ctx));
    assertEquals("\\360", codec.decodeBinaryAs(new byte[]{(byte) 0xF0}, 0, 1, charType, String.class, ctx));
  }

  @Test
  void encodeBinary_octalEscape_isThatByte() throws SQLException {
    // charin parses a "\NNN" octal escape back to its byte, so the high-byte round-trip closes.
    assertArrayEquals(new byte[]{(byte) 0x80}, codec.encodeBinary("\\200", charType, ctx));
    assertArrayEquals(new byte[]{(byte) 0xFF}, codec.encodeBinary("\\377", charType, ctx));
  }

  @Test
  void highByte_roundTripsThroughBinary() throws SQLException {
    // 0x80 -> "\200" -> 0x80: the escape is an exact round-trip, matching the server.
    byte[] wire = {(byte) 0x80};
    Object decoded = codec.decodeBinary(wire, 0, 1, charType, ctx);
    assertEquals("\\200", decoded);
    assertArrayEquals(wire, codec.encodeBinary(decoded, charType, ctx));
  }

  @Test
  void encodeBinary_charinEdgeCases_matchServer() throws SQLException {
    // Mirror charin: an incomplete/invalid escape is the literal backslash byte, a multi-char string is its
    // first byte, and octal 400 overflows to 0 -- all verified against the server.
    assertArrayEquals(new byte[]{0x5c}, codec.encodeBinary("\\0", charType, ctx));   // only 1 octal digit
    assertArrayEquals(new byte[]{0x5c}, codec.encodeBinary("\\x41", charType, ctx)); // 'x' not octal
    assertArrayEquals(new byte[]{0x41}, codec.encodeBinary("AB", charType, ctx));    // first byte
    assertArrayEquals(new byte[]{0}, codec.encodeBinary("\\400", charType, ctx));    // 256 -> 0
  }
}
