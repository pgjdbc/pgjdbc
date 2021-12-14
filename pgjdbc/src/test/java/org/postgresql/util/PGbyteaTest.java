/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.Assert.assertArrayEquals;

import org.junit.Test;

import java.sql.SQLException;
import java.util.Random;

public class PGbyteaTest {

  private static final byte[] HEX_DIGITS_U = new byte[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B',
      'C', 'D', 'E', 'F' };
  private static final byte[] HEX_DIGITS_L = new byte[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b',
      'c', 'd', 'e', 'f' };

  @Test
  public void testHexDecode_lower() throws SQLException {
    final byte[] data = new byte[1023];
    new Random(7).nextBytes(data);
    final byte[] encoded = hexEncode(data, HEX_DIGITS_L);
    final byte[] decoded = PGbytea.toBytes(encoded);
    assertArrayEquals(data, decoded);
  }

  @Test
  public void testHexDecode_upper() throws SQLException {
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
    for (int i = 0; i < data.length; ++i) {
      final int idx = (i << 1) + 2;
      final byte b = data[i];
      encoded[idx] = hexDigits[(b & 0xF0) >>> 4];
      encoded[idx + 1] = hexDigits[b & 0x0F];
    }
    return encoded;
  }
}
