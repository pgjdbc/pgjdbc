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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Random;
import java.util.stream.Stream;

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

  static Stream<Arguments> validHexStringTestCases() {
    return Stream.of(
        Arguments.of("\\xcafebabe", "'\\xcafebabe'::bytea", "lowercase"),
        Arguments.of("\\xCAFEBABE", "'\\xCAFEBABE'::bytea", "uppercase"),
        Arguments.of("\\xCaFeBaBe", "'\\xCaFeBaBe'::bytea", "mixed case"),
        Arguments.of("\\x", "'\\x'::bytea", "empty hex"),
        Arguments.of("\\xca fe ba be", "'\\xcafebabe'::bytea", "with spaces"),
        Arguments.of("\\xca\tfe\tba\tbe", "'\\xcafebabe'::bytea", "with tabs"),
        Arguments.of("\\xca\nfe\nba\nbe", "'\\xcafebabe'::bytea", "with newlines"),
        Arguments.of("\\xca\rfe\rba\rbe", "'\\xcafebabe'::bytea", "with carriage returns"),
        Arguments.of("\\x0123456789abcdefABCDEF", "'\\x0123456789abcdefABCDEF'::bytea", "long value"),
        Arguments.of("\\xcafe ba", "'\\xcafeba'::bytea", "with whitespace at end"),
        Arguments.of("\\xff", "'\\xff'::bytea", "single byte"),
        Arguments.of("\\x00", "'\\x00'::bytea", "zero byte")
    );
  }

  @ParameterizedTest(name = "toPGLiteral valid hex string: {2}")
  @MethodSource("validHexStringTestCases")
  void toPGLiteral_validHexStrings(String input, String expected, String description) throws IOException {
    String result = PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    assertEquals(expected, result);
  }

  static Stream<Arguments> invalidHexStringTestCases() {
    return Stream.of(
        Arguments.of("cafebabe", "bytea string parameters must be hex format", "no hex prefix"),
        Arguments.of("\\ycafebabe", "bytea string parameters must be hex format", "wrong prefix"),
        Arguments.of("\\", "bytea string parameters must be hex format", "too short"),
        Arguments.of("", "bytea string parameters must be hex format", "empty string"),
        Arguments.of("\\xcafegabe", "Invalid bytea hex format character g", "invalid hex character"),
        Arguments.of("\\xcafe@abe", "Invalid bytea hex format character @", "invalid hex character symbol"),
        Arguments.of("\\xcafebab", "Truncated bytea hex format", "odd number of hex digits"),
        Arguments.of("\\xcafe b", "Truncated bytea hex format", "truncated after whitespace"),
        Arguments.of("\\xcafe\u1234abe", "Invalid bytea hex format character \u1234", "high unicode character")
    );
  }

  @ParameterizedTest(name = "toPGLiteral invalid hex string: {2}")
  @MethodSource("invalidHexStringTestCases")
  void toPGLiteral_invalidHexStrings(String input, String expectedMessage, String description) {
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
      PGbytea.toPGLiteral(input, SqlSerializationContext.of(true, true));
    });
    assertEquals(expectedMessage, exception.getMessage());
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
