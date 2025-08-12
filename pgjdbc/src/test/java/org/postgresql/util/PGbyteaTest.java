/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.v3.SqlSerializationContext;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Random;

class PGbyteaTest {

  private static final byte[] HEX_DIGITS_U = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B',
      'C', 'D', 'E', 'F'};
  private static final byte[] HEX_DIGITS_L = new byte[]{'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b',
      'c', 'd', 'e', 'f'};

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

  @Test
  void toPGLiteral_validHexString_lowercase() throws IOException {
    String input = "\\xcafebabe";
    String expected = "'\\xcafebabe'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_uppercase() throws IOException {
    String input = "\\xCAFEBABE";
    String expected = "'\\xCAFEBABE'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_mixed() throws IOException {
    String input = "\\xCaFeBaBe";
    String expected = "'\\xCaFeBaBe'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_empty() throws IOException {
    String input = "\\x";
    String expected = "'\\x'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_withWhitespace() throws IOException {
    String input = "\\xca fe ba be";
    String expected = "'\\xcafebabe'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_withTabs() throws IOException {
    String input = "\\xca\tfe\tba\tbe";
    String expected = "'\\xcafebabe'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_withNewlines() throws IOException {
    String input = "\\xca\nfe\nba\nbe";
    String expected = "'\\xcafebabe'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_withCarriageReturns() throws IOException {
    String input = "\\xca\rfe\rba\rbe";
    String expected = "'\\xcafebabe'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validHexString_longValue() throws IOException {
    String input = "\\x0123456789abcdefABCDEF";
    String expected = "'\\x0123456789abcdefABCDEF'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_invalidString_noHexPrefix() {
    String input = "cafebabe";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("bytea string parameters must be hex format", exception.getMessage());
  }

  @Test
  void toPGLiteral_invalidString_wrongPrefix() {
    String input = "\\ycafebabe";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("bytea string parameters must be hex format", exception.getMessage());
  }

  @Test
  void toPGLiteral_invalidString_tooShort() {
    String input = "\\";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("bytea string parameters must be hex format", exception.getMessage());
  }

  @Test
  void toPGLiteral_invalidString_emptyString() {
    String input = "";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("bytea string parameters must be hex format", exception.getMessage());
  }

  @Test
  void toPGLiteral_invalidString_invalidHexCharacter() {
    String input = "\\xcafegabe";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("Invalid bytea hex format character g", exception.getMessage());
  }

  @Test
  void toPGLiteral_invalidString_invalidHexCharacterSymbol() {
    String input = "\\xcafe@abe";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("Invalid bytea hex format character @", exception.getMessage());
  }

  @Test
  void toPGLiteral_invalidString_oddNumberOfHexDigits() {
    String input = "\\xcafebab";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("Truncated bytea hex format", exception.getMessage());
  }

  @Test
  void toPGLiteral_validHexString_withWhitespaceAtEnd() throws IOException {
    // This case works because whitespace is skipped and "cafeba" is valid hex
    String input = "\\xcafe ba";
    String expected = "'\\xcafeba'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_invalidString_truncatedAfterWhitespace() {
    // This case fails because after skipping whitespace, there's only one hex character left
    String input = "\\xcafe b";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("Truncated bytea hex format", exception.getMessage());
  }

  @Test
  void toPGLiteral_invalidString_highUnicodeCharacter() {
    String input = "\\xcafe\u1234abe";
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals("Invalid bytea hex format character \u1234", exception.getMessage());
  }

  @Test
  void toPGLiteral_validString_allHexDigits() throws IOException {
    String input = "\\x0123456789abcdefABCDEF";
    String expected = "'\\x0123456789abcdefABCDEF'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validString_singleByte() throws IOException {
    String input = "\\xff";
    String expected = "'\\xff'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_validString_zeroByte() throws IOException {
    String input = "\\x00";
    String expected = "'\\x00'::bytea";
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  @Test
  void toPGLiteral_deprecatedMethod_validString() throws IOException {
    String input = "\\xcafebabe";
    String expected = "'\\xcafebabe'::bytea";
    @SuppressWarnings("deprecation")
    String result = PGbytea.toPGLiteral(input);
    assertEquals(expected, result);
  }
}
