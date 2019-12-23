/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.core.FixedLengthOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

public class FixedLengthOutputStreamTest {

  private ByteArrayOutputStream targetStream;
  private FixedLengthOutputStream fixedLengthStream;

  @Before
  public void setUp() throws Exception {
    targetStream = new ByteArrayOutputStream();
    fixedLengthStream = new FixedLengthOutputStream(10, targetStream);
  }

  @After
  public void tearDown() throws SQLException {
  }

  private void verifyExpectedOutput(byte[] expected) {
    assertArrayEquals("Incorrect data written to target stream",
        expected, targetStream.toByteArray());
  }

  @Test
  public void testSingleByteWrites() throws IOException {
    fixedLengthStream.write((byte) 1);
    assertEquals("Incorrect remaining value", 9, fixedLengthStream.remaining());
    fixedLengthStream.write((byte) 2);
    assertEquals("Incorrect remaining value", 8, fixedLengthStream.remaining());
    verifyExpectedOutput(new byte[]{1, 2});
  }

  @Test
  public void testMultipleByteWrites() throws IOException {
    fixedLengthStream.write(new byte[]{1, 2, 3, 4});
    assertEquals("Incorrect remaining value", 6, fixedLengthStream.remaining());
    fixedLengthStream.write(new byte[]{5, 6, 7, 8});
    assertEquals("Incorrect remaining value", 2, fixedLengthStream.remaining());
    verifyExpectedOutput(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
  }

  @Test
  public void testSingleByteOverLimit() throws IOException {
    byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    fixedLengthStream.write(data);
    assertEquals("Incorrect remaining value", 0, fixedLengthStream.remaining());
    try {
      fixedLengthStream.write((byte) 'a');
      fail("Expected exception not thrown");
    } catch (IOException e) {
      assertEquals("Incorrect exception message",
          "Attempt to write more than the specified 10 bytes", e.getMessage());
    }
    assertEquals("Incorrect remaining value after exception",
        0, fixedLengthStream.remaining());
    verifyExpectedOutput(data);
  }

  @Test
  public void testMultipleBytesOverLimit() throws IOException {
    byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    fixedLengthStream.write(data);
    assertEquals(2, fixedLengthStream.remaining());
    try {
      fixedLengthStream.write(new byte[]{'a', 'b', 'c', 'd'});
      fail("Expected exception not thrown");
    } catch (IOException e) {
      assertEquals("Incorrect exception message",
          "Attempt to write more than the specified 10 bytes", e.getMessage());
    }
    assertEquals("Incorrect remaining value after exception",
        2, fixedLengthStream.remaining());
    verifyExpectedOutput(data);
  }
}
