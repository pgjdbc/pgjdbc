/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.core.FixedLengthOutputStream;
import org.postgresql.util.PGbytea;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

class FixedLengthOutputStreamTest {

  private ByteArrayOutputStream targetStream = new ByteArrayOutputStream();
  private FixedLengthOutputStream fixedLengthStream = new FixedLengthOutputStream(10, targetStream);

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

  /**
   * The target this stream forwards to when a simple query builds a bytea literal. It is used
   * rather than modelled, because what matters is real: {@link PGbytea#appendHexString} bounds its
   * loop with the same addition that wraps here, so on a wrapping range it appends nothing and
   * raises nothing, and the check in FixedLengthOutputStream is the only one between that range
   * and a value quietly missing bytes.
   */
  private static class HexLiteralTarget extends OutputStream {
    private final StringBuilder literal = new StringBuilder();
    private int calls;

    @Override
    public void write(int b) {
      calls++;
      PGbytea.appendHexString(literal, new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] buf, int off, int len) {
      calls++;
      PGbytea.appendHexString(literal, buf, off, len);
    }
  }

  /**
   * An offset near {@link Integer#MAX_VALUE} makes {@code offset + len} wrap negative, so a check
   * written that way lets the range through. Nothing downstream is obliged to catch it, and the
   * stream then counts bytes it never wrote.
   */
  @Test
  void offsetThatWrapsTheBoundsCheckIsRejectedBeforeTheTargetSeesIt() {
    HexLiteralTarget target = new HexLiteralTarget();
    FixedLengthOutputStream stream = new FixedLengthOutputStream(100, target);
    assertThrows(IndexOutOfBoundsException.class,
        () -> stream.write(new byte[10], Integer.MAX_VALUE - 5, 10));
    assertEquals(0, target.calls, "the range was forwarded to the target");
    assertEquals(0, target.literal.length(), "the target appended something for such a range");
    assertEquals(100, stream.remaining(), "the stream counted bytes it never wrote");
  }

  /**
   * The same wrap from the other side. Here the size guard did catch it, but reported it as the
   * writer exceeding its declared length, which is not what happened.
   */
  @Test
  void lengthThatWrapsTheBoundsCheckIsReportedAsARange() {
    IndexOutOfBoundsException e = assertThrows(IndexOutOfBoundsException.class,
        () -> fixedLengthStream.write(new byte[10], 3, Integer.MAX_VALUE));
    assertEquals("Range [3, 3 + 2147483647) out of bounds for length 10", e.getMessage(),
        "the message has to say which range was refused");
    assertEquals(10, fixedLengthStream.remaining(), "nothing may be counted as written");
  }

  /**
   * The successful case with a non-zero offset. Every other write in this class that is expected
   * to succeed starts at zero, so without this one a target handed {@code (buf, 0, len)} instead
   * of {@code (buf, offset, len)} would deliver the wrong window and nothing would notice.
   */
  @Test
  void rangeThatFitsIsPassedThroughAndCounted() throws IOException {
    byte[] data = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
    fixedLengthStream.write(data, 3, 4);
    verifyExpectedOutput(new byte[]{3, 4, 5, 6});
    assertEquals(6, fixedLengthStream.remaining(), "Incorrect remaining value");
    // An empty range at the very end of the array is the legal side of that boundary
    fixedLengthStream.write(data, 10, 0);
    verifyExpectedOutput(new byte[]{3, 4, 5, 6});
    assertEquals(6, fixedLengthStream.remaining(), "Incorrect remaining value");
  }

  /**
   * A range that starts inside the array and runs off the end, which is the ordinary mistake.
   */
  @Test
  void rangeRunningPastTheEndOfTheArrayIsRejected() {
    byte[] data = new byte[10];
    assertThrows(IndexOutOfBoundsException.class, () -> fixedLengthStream.write(data, 5, 8));
    assertThrows(IndexOutOfBoundsException.class, () -> fixedLengthStream.write(data, -1, 1));
    assertThrows(IndexOutOfBoundsException.class, () -> fixedLengthStream.write(data, 0, -1));
    assertThrows(IndexOutOfBoundsException.class, () -> fixedLengthStream.write(data, 11, 0));
    assertEquals(10, fixedLengthStream.remaining(), "nothing may be counted as written");
    verifyExpectedOutput(new byte[0]);
  }
}
