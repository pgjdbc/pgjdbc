/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.core.FixedLengthOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;

class FixedLengthOutputStreamTest {

  private ByteArrayOutputStream targetStream;
  private FixedLengthOutputStream fixedLengthStream;

  @BeforeEach
  void setUp() throws Exception {
    targetStream = new ByteArrayOutputStream();
    fixedLengthStream = new FixedLengthOutputStream(10, targetStream);
  }

  @AfterEach
  void tearDown() throws SQLException {
  }

  private void verifyExpectedOutput(byte[] expected) {
    assertArrayEquals(expected, targetStream.toByteArray(), "Incorrect data written to target stream");
  }

  @Test
  void singleByteWrites() throws IOException {
    fixedLengthStream.write((byte) 1);
    assertEquals(9, fixedLengthStream.remaining(), "Incorrect remaining value");
    fixedLengthStream.write((byte) 2);
    assertEquals(8, fixedLengthStream.remaining(), "Incorrect remaining value");
    verifyExpectedOutput(new byte[]{1, 2});
  }

  @Test
  void multipleByteWrites() throws IOException {
    fixedLengthStream.write(new byte[]{1, 2, 3, 4});
    assertEquals(6, fixedLengthStream.remaining(), "Incorrect remaining value");
    fixedLengthStream.write(new byte[]{5, 6, 7, 8});
    assertEquals(2, fixedLengthStream.remaining(), "Incorrect remaining value");
    verifyExpectedOutput(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
  }

  @Test
  void singleByteOverLimit() throws IOException {
    byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 0};
    fixedLengthStream.write(data);
    assertEquals(0, fixedLengthStream.remaining(), "Incorrect remaining value");
    try {
      fixedLengthStream.write((byte) 'a');
      fail("Expected exception not thrown");
    } catch (IOException e) {
      assertEquals("Attempt to write more than the specified 10 bytes", e.getMessage(), "Incorrect exception message");
    }
    assertEquals(0, fixedLengthStream.remaining(), "Incorrect remaining value after exception");
    verifyExpectedOutput(data);
  }

  @Test
  void multipleBytesOverLimit() throws IOException {
    byte[] data = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
    fixedLengthStream.write(data);
    assertEquals(2, fixedLengthStream.remaining());
    try {
      fixedLengthStream.write(new byte[]{'a', 'b', 'c', 'd'});
      fail("Expected exception not thrown");
    } catch (IOException e) {
      assertEquals("Attempt to write more than the specified 10 bytes", e.getMessage(), "Incorrect exception message");
    }
    assertEquals(2, fixedLengthStream.remaining(), "Incorrect remaining value after exception");
    verifyExpectedOutput(data);
  }
}
