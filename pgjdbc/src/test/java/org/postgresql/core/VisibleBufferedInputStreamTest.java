/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import org.postgresql.util.GT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * {@link VisibleBufferedInputStream#getPosition()} equals the offset of the next byte a caller
 * reads, whichever method consumed the bytes before it and however the buffer was refilled,
 * compacted, or bypassed; and {@link VisibleBufferedInputStream#scanCStringLength(int, String, int)}
 * finds a NUL within {@code maxBytes} bytes without consuming anything.
 *
 * <p>The position tests read {@link #pattern(int)}, where the byte at offset {@code i} is
 * {@code i % 251}, so a test can also check that the byte read at the reported position is the
 * byte at that offset. Each source returns at most a number of bytes per read that the test
 * chooses, so a test can make the buffer refill. The scan tests build their own bytes.</p>
 */
class VisibleBufferedInputStreamTest {

  /**
   * Returns {@code length} bytes where the byte at offset {@code i} is {@code i % 251}. The values
   * contain a zero every 251 bytes; the scan tests use their own data.
   */
  private static byte[] pattern(int length) {
    byte[] data = new byte[length];
    for (int i = 0; i < length; i++) {
      data[i] = (byte) (i % 251);
    }
    return data;
  }

  @Test
  void aFreshStreamIsAtPositionZero() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(10), 10), 1024);
    assertEquals(0, in.getPosition());
  }

  @Test
  void singleByteReadsAcrossSeveralRefillsCountEveryByte() throws IOException {
    // 3000 bytes in chunks of 100 through a 1024-byte buffer: the buffer drains and refills
    // many times while read() consumes it.
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(3000), 100), 1024);
    for (int i = 0; i < 2500; i++) {
      in.read();
    }
    assertAll(
        () -> assertEquals(2500, in.getPosition(), "after 2500 read() calls"),
        () -> assertEquals(2500 % 251, in.read(), "byte read at position 2500"));
  }

  @Test
  void readAtEndOfStreamDoesNotMoveThePosition() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(3), 3), 1024);
    in.read();
    in.read();
    in.read();
    assertAll(
        () -> assertEquals(-1, in.read(), "read() at end of stream"),
        () -> assertEquals(3, in.getPosition(), "position after reading past end of stream"));
  }

  @Test
  void integerAndRawReadsCountTheirWidth() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(100), 100), 1024);
    in.readInt2();
    assertEquals(2, in.getPosition(), "after readInt2()");
    in.readInt4();
    assertEquals(6, in.getPosition(), "after readInt2(), readInt4()");
    in.ensureBytes(1);
    in.readRaw();
    assertEquals(7, in.getPosition(), "after readInt2(), readInt4(), readRaw()");
  }

  @Test
  void peekAndEnsureBytesDoNotMoveThePosition() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(5000), 100), 1024);
    in.read();
    in.peek();
    in.ensureBytes(3000);
    assertAll(
        () -> assertEquals(1, in.getPosition(), "after read(), peek(), ensureBytes(3000)"),
        () -> assertEquals(1, in.read(), "byte read at position 1"));
  }

  @Test
  void arrayReadServedFromTheBufferCountsTheBytesCopied() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(1000), 1000), 1024);
    in.read();
    byte[] to = new byte[10];
    int n = in.read(to, 0, to.length);
    assertAll(
        () -> assertEquals(10, n, "read(byte[10]) return value"),
        () -> assertEquals(11, in.getPosition(), "after read(), read(byte[10])"),
        () -> assertEquals(11, in.read(), "byte read at position 11"));
  }

  /**
   * A read longer than the buffer holds copies what the buffer has and reads the rest straight
   * from the wrapped stream, so both parts have to be counted.
   */
  @Test
  void arrayReadThatBypassesTheBufferCountsBothParts() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(10000), 300), 1024);
    for (int i = 0; i < 10; i++) {
      in.read();
    }
    // The buffer now holds 290 unread bytes; 5000 - 290 exceeds the 1024-byte minimum read, so
    // the rest comes from the wrapped stream directly.
    byte[] to = new byte[5000];
    int n = in.read(to, 0, to.length);
    assertAll(
        () -> assertEquals(5000, n, "read(byte[5000]) return value"),
        () -> assertArrayEquals(Arrays.copyOfRange(pattern(10000), 10, 5010), to,
            "bytes read at offsets 10..5009"),
        () -> assertEquals(5010, in.getPosition(), "after 10 read(), read(byte[5000])"),
        () -> assertEquals(5010 % 251, in.read(), "byte read at position 5010"));
  }

  @Test
  void arrayReadCutShortByEndOfStreamCountsOnlyTheBytesReturned() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(3000), 300), 1024);
    in.read();
    byte[] to = new byte[8000];
    int n = in.read(to, 0, to.length);
    assertAll(
        () -> assertEquals(2999, n, "read(byte[8000]) return value"),
        () -> assertEquals(3000, in.getPosition(), "after read(), read(byte[8000])"));
  }

  @Test
  void skipWithinTheBufferCountsTheSkippedBytes() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(1000), 1000), 1024);
    in.read();
    long skipped = in.skip(100);
    assertAll(
        () -> assertEquals(100, skipped, "skip(100) return value"),
        () -> assertEquals(101, in.getPosition(), "after read(), skip(100)"),
        () -> assertEquals(101, in.read(), "byte read at position 101"));
  }

  /**
   * A skip past the buffered bytes discards the buffer and skips the rest in the wrapped stream.
   */
  @Test
  void skipPastTheBufferCountsTheBufferedAndTheSkippedBytes() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(10000), 300), 1024);
    for (int i = 0; i < 10; i++) {
      in.read();
    }
    long skipped = in.skip(4000);
    assertAll(
        () -> assertEquals(4000, skipped, "skip(4000) return value"),
        () -> assertEquals(4010, in.getPosition(), "after 10 read(), skip(4000)"),
        () -> assertEquals(4010 % 251, in.read(), "byte read at position 4010"));
  }

  /**
   * The position counts what the wrapped stream reports as skipped, not what was asked.
   */
  @Test
  void skipPastEndOfStreamCountsOnlyTheBytesThatExisted() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(2000), 300), 1024);
    in.read();
    long skipped = in.skip(5000);
    assertAll(
        () -> assertEquals(1999, skipped, "skip(5000) return value"),
        () -> assertEquals(2000, in.getPosition(), "after read(), skip(5000)"));
  }

  /**
   * Asking for more bytes than fit behind the read index moves the unread bytes to the front of
   * the buffer, which discards the consumed bytes in front of them. With 2000 of 2048 bytes
   * consumed, room for 100 more is made by compacting; with 10 consumed, by doubling.
   */
  @ParameterizedTest
  @ValueSource(ints = {2000, 10})
  void movingUnreadBytesToTheFrontOfTheBufferKeepsThePosition(int consumed) throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(pattern(10000), 10000), 2048);
    in.ensureBytes(1);
    in.skip(consumed);
    in.ensureBytes(2048 - consumed + 100);
    assertAll(
        () -> assertEquals(consumed, in.getPosition(), "after skip(" + consumed + ") and a refill"),
        () -> assertEquals(consumed % 251, in.read(), "byte read at position " + consumed));
  }

  @Test
  void scanReturnsTheLengthWithTheNulAndConsumesNothing() throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ChunkedStream("xabc\0tail".getBytes(StandardCharsets.US_ASCII), 100), 1024);
    in.read();
    int len = in.scanCStringLength(100, "Probe", 104);
    assertAll(
        () -> assertEquals(4, len, "scanCStringLength over \"abc\\0\""),
        () -> assertEquals(1, in.getPosition(), "position after the scan"),
        () -> assertEquals('a', in.read(), "byte read after the scan"));
  }

  /**
   * {@code maxBytes} counts the NUL, so a string whose NUL is byte {@code maxBytes} fits and one
   * whose NUL is byte {@code maxBytes + 1} does not.
   */
  @ParameterizedTest
  @ValueSource(ints = {4, 5})
  void scanAcceptsANulAtOrBeforeTheLastAllowedByte(int maxBytes) throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ChunkedStream("abc\0tail".getBytes(StandardCharsets.US_ASCII), 100), 1024);
    assertEquals(4, in.scanCStringLength(maxBytes, "Probe", 8), "scanCStringLength(" + maxBytes + ")");
  }

  @Test
  void scanRejectsANulOneBytePastTheLimit() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ChunkedStream("abc\0tail".getBytes(StandardCharsets.US_ASCII), 100), 1024);
    IOException e = assertThrowsExactly(IOException.class, () -> in.scanCStringLength(3, "Probe", 7));
    assertEquals(
        GT.tr("Protocol error. C-string in {0} message of {1} bytes exceeds remaining budget of {2} bytes.",
            "Probe", "7", "3"),
        e.getMessage());
  }

  /**
   * The scan fails on byte {@code maxBytes + 1}, whatever it is, without reading further. The
   * source serves exactly that many bytes and then fails the test if read again, the way a stream
   * out of sync keeps producing bytes that contain no NUL.
   */
  @Test
  void scanStopsReadingOnceTheLimitIsExceeded() {
    byte[] noNul = new byte[3001];
    Arrays.fill(noNul, (byte) 'x');
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new EndlessAfter(noNul, 700), 1024);
    IOException e = assertThrowsExactly(IOException.class, () -> in.scanCStringLength(3000, "Probe", 3004));
    assertEquals(
        GT.tr("Protocol error. C-string in {0} message of {1} bytes exceeds remaining budget of {2} bytes.",
            "Probe", "3004", "3000"),
        e.getMessage());
  }

  /**
   * A string longer than the buffer makes the scan refill it, and the refill moves the unread
   * bytes to the front. The returned length and the bytes at {@code getBuffer()[getIndex()]}
   * still describe the whole string.
   */
  @Test
  void scanFindsANulBeyondSeveralBufferRefills() throws IOException {
    byte[] data = new byte[6001];
    Arrays.fill(data, (byte) 'y');
    data[0] = 'z';
    data[5000] = 0;
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new ChunkedStream(data, 300), 1024);
    in.read();
    int len = in.scanCStringLength(10000, "Probe", 10004);
    assertAll(
        () -> assertEquals(5000, len, "scanCStringLength over 4999 bytes and a NUL"),
        () -> assertArrayEquals(Arrays.copyOfRange(data, 1, 5001),
            Arrays.copyOfRange(in.getBuffer(), in.getIndex(), in.getIndex() + len),
            "buffer contents at getIndex() after the scan"),
        () -> assertEquals(1, in.getPosition(), "position after the scan"));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1})
  void scanRejectsANonPositiveLimitWithoutReading(int maxBytes) {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(new EndlessAfter(new byte[0], 1), 1024);
    IOException e = assertThrowsExactly(IOException.class, () -> in.scanCStringLength(maxBytes, "Probe", 20));
    assertEquals(
        GT.tr("Protocol error. {0} message of {1} bytes has no room left for a C-string (remaining budget: {2} bytes).",
            "Probe", "20", String.valueOf(maxBytes)),
        e.getMessage());
  }

  @Test
  void scanReportsEndOfStreamBeforeANulWithinTheLimit() {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(
        new ChunkedStream("abc".getBytes(StandardCharsets.US_ASCII), 100), 1024);
    assertThrows(EOFException.class, () -> in.scanCStringLength(100, "Probe", 104));
  }

  /**
   * Serves a fixed array, at most {@code chunk} bytes per {@code read}, then end of stream. Skip
   * falls back to {@link InputStream#skip(long)}, which reads through this class.
   */
  private static final class ChunkedStream extends InputStream {
    private final byte[] data;
    private final int chunk;
    private int next;

    ChunkedStream(byte[] data, int chunk) {
      this.data = data;
      this.chunk = chunk;
    }

    @Override
    public int read() {
      return next < data.length ? data[next++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (len == 0) {
        return 0;
      }
      if (next >= data.length) {
        return -1;
      }
      int n = Math.min(Math.min(len, chunk), data.length - next);
      System.arraycopy(data, next, b, off, n);
      next += n;
      return n;
    }
  }

  /**
   * Serves a fixed array, at most {@code chunk} bytes per read, and throws {@link AssertionError}
   * on any read after it, so a test fails when the code under test reads further.
   */
  private static final class EndlessAfter extends InputStream {
    private final ChunkedStream data;
    private final int length;
    private int served;

    EndlessAfter(byte[] data, int chunk) {
      this.data = new ChunkedStream(data, chunk);
      this.length = data.length;
    }

    @Override
    public int read() {
      return read(new byte[1], 0, 1);
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (served >= length) {
        throw new AssertionError("read past the " + length + " bytes the test allows");
      }
      int n = data.read(b, off, len);
      served += n;
      return n;
    }
  }
}
