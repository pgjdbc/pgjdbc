/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertEquals;

import org.junit.Assert;

import java.lang.reflect.Array;

public class ByteaArraysTest extends AbstractArraysTest<byte[][]> {

  private static final byte[][][][] longs = new byte[][][][] {
      { { { 0x1, 0x2, (byte) 0xFF, 0x4 }, { 0x5, 0x6, 0x7, (byte) 0xFF }, null, { 0x9, 0x10, 0x11, 0x12 } },
          { null, { 0x13, 0x14, 0x15, 0x16 }, { 0x17, 0x18, (byte) 0xFF, 0x20 }, { 0x1, 0x2, (byte) 0xFF, 0x4 } },
          { { 0x1, 0x2, (byte) 0xFF, 0x4 }, { 0x1, 0x2, (byte) 0xFF, 0x4 }, { 0x1, 0x2, (byte) 0xFF, 0x4 },
              { 0x1, 0x2, (byte) 0xFF, 0x4 } } },
      { { { 0x1, 0x2, (byte) 0xFF, 0x4 }, { 0x1, 0x2, (byte) 0xFF, 0x4 }, { 0x1, 0x2, (byte) 0xFF, 0x4 },
          { 0x1, 0x2, (byte) 0xFE, 0x4 } },
          { { 0x1, 0x2, (byte) 0xCD, 0x4 }, { 0x1, 0x2, (byte) 0xFF, 0x4 }, { 0x1, 0x2, (byte) 0xFF, 0x4 },
              { 0x1, 0x2, (byte) 0xFF, 0x4 } },
          { { 0x1, 0x2, (byte) 0xFF, 0x4 }, { 0x1, 0x2, (byte) 0xFE, 0x10 }, { 0x1, 0x2, (byte) 0xFF, 0x4 },
              { 0x1, 0x2, (byte) 0xFF, 0x4 } } } };

  public ByteaArraysTest() {
    super(longs, true);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected String getExpectedString(byte[][] expected, char delim) {
    final StringBuilder sb = new StringBuilder(1024);
    sb.append('{');
    for (int i = 0; i < expected.length; ++i) {
      if (i != 0) {
        sb.append(delim);
      }
      if (expected[i] == null) {
        sb.append("NULL");
      } else {
        sb.append('"');
        sb.append("\\\\x");
        for (int j = 0; j < expected[i].length; ++j) {
          final String hexString = Integer.toHexString(expected[i][j] & 0xFF);
          if (hexString.length() == 1) {
            sb.append('0');
          }
          sb.append(hexString);
        }
        sb.append('"');
      }
    }
    sb.append('}');

    return sb.toString();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void assertArraysEquals(String message, byte[][] expected, Object actual) {
    final int expectedLength = Array.getLength(expected);
    assertEquals(message + " size", expectedLength, Array.getLength(actual));
    for (int i = 0; i < expectedLength; ++i) {
      Assert.assertArrayEquals(message + " value at " + i, expected[i], (byte[]) Array.get(actual, i));
    }
  }
}
