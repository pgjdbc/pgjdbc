/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.core.v3.SqlSerializationContext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.util.Random;
import java.util.stream.Stream;

class PGbyteaTest {

  private static final byte[] HEX_DIGITS_U = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B',
      'C', 'D', 'E', 'F'};
  private static final byte[] HEX_DIGITS_L = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b',
      'c', 'd', 'e', 'f'};

  /**
   * The four contexts {@link SqlSerializationContext#of(boolean, boolean)} returns. They are
   * constants of an enum that is package-private to {@code org.postgresql.core.v3}, so a test in
   * this package can only obtain them from the factory. Each field is named after its enum
   * constant, which a failing case id prints for the context argument.
   */
  private static final SqlSerializationContext STDSTR_IDEMPOTENT =
      SqlSerializationContext.of(true, true);
  private static final SqlSerializationContext NONSTDSTR_IDEMPOTENT =
      SqlSerializationContext.of(false, true);
  private static final SqlSerializationContext STDSTR_NONIDEMPOTENT =
      SqlSerializationContext.of(true, false);
  private static final SqlSerializationContext NONSTDSTR_NONIDEMPOTENT =
      SqlSerializationContext.of(false, false);

  @Test
  void hexDecode_lower() throws SQLException {
    final byte[] data = new byte[1023];
    new Random(7).nextBytes(data);
    final byte[] encoded = hexEncode(data, HEX_DIGITS_L);
    final byte[] decoded = PGbytea.toBytes(encoded);
    assertArrayEquals(data, decoded);
  }

  @Test
  void hexDecode_upper() throws SQLException {
    final byte[] data = new byte[9513];
    new Random(-8).nextBytes(data);
    final byte[] encoded = hexEncode(data, HEX_DIGITS_U);
    final byte[] decoded = PGbytea.toBytes(encoded);
    assertArrayEquals(data, decoded);
  }

  /**
   * Lists every input {@code toPGLiteral} accepts, each against both settings of
   * {@code standard_conforming_strings} and at both a non-empty and an empty length. A server with
   * the setting off reads a backslash in a plain literal as an escape character, so the {@code \x}
   * marker goes into an escape string constant with its backslash doubled. The four inputs open the
   * literal at four separate call sites, and a {@link StreamWrapper} splits again on whether it
   * holds bytes or a stream. An empty value leaves the opening with no hex digits after it.
   *
   * <p>A {@link StreamWrapper} over an {@link java.io.InputStream} is listed with a non-idempotent
   * context, which renders the SQL the driver sends. Only that mode reads the stream;
   * {@link #toPGLiteral_streamWrapper_idempotentContextRendersAPlaceholder()} covers the other
   * mode.</p>
   */
  static Stream<Arguments> hexLiterals() {
    return Stream.of(
        arguments(named("byte[]", new byte[]{0, 1, 2, 3}),
            STDSTR_IDEMPOTENT, "'\\x00010203'::bytea"),
        arguments(named("byte[]", new byte[]{0, 1, 2, 3}),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x00010203'::bytea"),
        arguments(named("empty byte[]", new byte[0]),
            STDSTR_IDEMPOTENT, "'\\x'::bytea"),
        arguments(named("empty byte[]", new byte[0]),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x'::bytea"),

        arguments(named("hex string", "\\x00010203"),
            STDSTR_IDEMPOTENT, "'\\x00010203'::bytea"),
        arguments(named("hex string", "\\x00010203"),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x00010203'::bytea"),
        arguments(named("empty hex string", "\\x"),
            STDSTR_IDEMPOTENT, "'\\x'::bytea"),
        arguments(named("empty hex string", "\\x"),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x'::bytea"),

        arguments(named("StreamWrapper over byte[]", new StreamWrapper(new byte[]{0, 1, 2, 3}, 0, 4)),
            STDSTR_IDEMPOTENT, "'\\x00010203'::bytea"),
        arguments(named("StreamWrapper over byte[]", new StreamWrapper(new byte[]{0, 1, 2, 3}, 0, 4)),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x00010203'::bytea"),
        arguments(named("empty StreamWrapper over byte[]", new StreamWrapper(new byte[0], 0, 0)),
            STDSTR_IDEMPOTENT, "'\\x'::bytea"),
        arguments(named("empty StreamWrapper over byte[]", new StreamWrapper(new byte[0], 0, 0)),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x'::bytea"),

        arguments(named("StreamWrapper over InputStream",
                new StreamWrapper(new ByteArrayInputStream(new byte[]{0, 1, 2, 3}), 4)),
            STDSTR_NONIDEMPOTENT, "'\\x00010203'::bytea"),
        arguments(named("StreamWrapper over InputStream",
                new StreamWrapper(new ByteArrayInputStream(new byte[]{0, 1, 2, 3}), 4)),
            NONSTDSTR_NONIDEMPOTENT, "E'\\\\x00010203'::bytea"),
        arguments(named("empty StreamWrapper over InputStream",
                new StreamWrapper(new ByteArrayInputStream(new byte[0]), 0)),
            STDSTR_NONIDEMPOTENT, "'\\x'::bytea"),
        arguments(named("empty StreamWrapper over InputStream",
                new StreamWrapper(new ByteArrayInputStream(new byte[0]), 0)),
            NONSTDSTR_NONIDEMPOTENT, "E'\\\\x'::bytea"),

        arguments(named("ByteStreamWriter",
                new ByteBufferByteStreamWriter(ByteBuffer.wrap(new byte[]{0, 1, 2, 3}))),
            STDSTR_IDEMPOTENT, "'\\x00010203'::bytea"),
        arguments(named("ByteStreamWriter",
                new ByteBufferByteStreamWriter(ByteBuffer.wrap(new byte[]{0, 1, 2, 3}))),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x00010203'::bytea"),
        arguments(named("empty ByteStreamWriter",
                new ByteBufferByteStreamWriter(ByteBuffer.wrap(new byte[0]))),
            STDSTR_IDEMPOTENT, "'\\x'::bytea"),
        arguments(named("empty ByteStreamWriter",
                new ByteBufferByteStreamWriter(ByteBuffer.wrap(new byte[0]))),
            NONSTDSTR_IDEMPOTENT, "E'\\\\x'::bytea"));
  }

  @ParameterizedTest
  @MethodSource("hexLiterals")
  void toPGLiteral_opensTheHexLiteralAccordingToStandardConformingStrings(Object value,
      SqlSerializationContext context, String expected) throws IOException {
    assertEquals(expected, PGbytea.toPGLiteral(value, context));
  }

  @Test
  void toPGLiteral_streamWrapper_idempotentContextRendersAPlaceholder() throws IOException {
    ByteArrayInputStream in = new ByteArrayInputStream(new byte[]{0, 1, 2, 3});
    assertEquals("?", PGbytea.toPGLiteral(new StreamWrapper(in, 4), STDSTR_IDEMPOTENT));
    // The placeholder is only correct if the bytes are still there for the real send
    assertEquals(4, in.available(), "bytes left unread after toPGLiteral");
  }

  @Test
  void toPGLiteral_hexString_upperCaseDigits() throws IOException {
    assertEquals("'\\xCAFEBABE'::bytea", PGbytea.toPGLiteral("\\xCAFEBABE", STDSTR_IDEMPOTENT));
  }

  @Test
  void toPGLiteral_hexString_whitespaceIsAllowed() throws IOException {
    assertEquals("'\\x00 01\t02'::bytea", PGbytea.toPGLiteral("\\x00 01\t02", STDSTR_IDEMPOTENT));
  }

  @Test
  void toPGLiteral_hexString_rejectsNonHexCharacter() {
    assertThrows(IllegalArgumentException.class, () -> PGbytea.toPGLiteral("\\x00zz", STDSTR_IDEMPOTENT));
  }

  @Test
  void toPGLiteral_hexString_rejectsOddNumberOfDigits() {
    // PostgreSQL rejects an odd number of hex digits, so validate it here too
    assertThrows(IllegalArgumentException.class, () -> PGbytea.toPGLiteral("\\xcaf", STDSTR_IDEMPOTENT));
  }

  @Test
  void toPGLiteral_hexString_rejectsFormFeed() {
    // PostgreSQL ignores space, tab, newline and carriage return, but not form feed
    assertThrows(IllegalArgumentException.class, () -> PGbytea.toPGLiteral("\\xca\ffe", STDSTR_IDEMPOTENT));
  }

  @Test
  void toPGLiteral_hexString_rejectsInjectionAttempt() {
    // A quote must not slip into the literal unescaped
    assertThrows(IllegalArgumentException.class,
        () -> PGbytea.toPGLiteral("\\x00'::bytea); drop table t; --", STDSTR_IDEMPOTENT));
  }

  @Test
  void toPGLiteral_string_rejectsMissingHexPrefix() {
    assertThrows(IllegalArgumentException.class, () -> PGbytea.toPGLiteral("00010203", STDSTR_IDEMPOTENT));
  }

  private static byte[] hexEncode(byte[] data, byte[] hexDigits) {

    // the string created will have 2 characters for each byte.
    // and 2 lead characters to indicate hex encoding
    final byte[] encoded = new byte[2 + (data.length << 1)];
    encoded[0] = '\\';
    encoded[1] = 'x';
    for (int i = 0; i < data.length; i++) {
      final int idx = (i << 1) + 2;
      final byte b = data[i];
      encoded[idx] = hexDigits[(b & 0xF0) >>> 4];
      encoded[idx + 1] = hexDigits[b & 0x0F];
    }
    return encoded;
  }
}
