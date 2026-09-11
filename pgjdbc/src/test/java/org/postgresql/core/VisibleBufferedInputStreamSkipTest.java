/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link VisibleBufferedInputStream#skip(long)} discards exactly the count it returns, and the
 * count falls short of the request only at end of stream.
 *
 * <p>The caller subtracts the count from the protocol message it is discarding, so a count that
 * differs from the bytes discarded leaves the connection off a message boundary. Where a test
 * checks the count, it also checks the byte a read returns after the discard.</p>
 *
 * <p>A {@link SocketTimeoutException} from the wrapped stream is waited out, as it is for a read,
 * unless the caller requested the timeout with
 * {@link VisibleBufferedInputStream#setTimeoutRequested(boolean)}. A requested timeout is
 * thrown.</p>
 */
// Discarding runs in a loop, so a wrong loop condition spins here. The timeout runs the test on a
// separate thread because the default thread mode measures the elapsed time only after the test
// method returns, and a spinning test never returns
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VisibleBufferedInputStreamSkipTest {
  private static final int LENGTH = 4096;

  /**
   * Payload the wrapped stream serves. Adjacent offsets, and offsets a multiple of 256 apart,
   * hold different bytes, so the byte read after a discard shows where the discard stopped.
   */
  private static final byte[] DATA = new byte[LENGTH];

  /** Largest read the wrapped stream serves, in bytes, so a longer discard spans several reads. */
  private static final int CHUNK = 64;

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1 + (i >> 8));
    }
  }

  /**
   * Serves {@link #DATA} in reads of at most {@link #CHUNK} bytes, and throws
   * {@link SocketTimeoutException} from the reads whose zero-based ordinals the constructor
   * receives.
   *
   * <p>It inherits {@link InputStream#skip(long)}, which discards by reading, and the JDK socket
   * stream's {@code skip} discards by reading too. A discard that calls the wrapped stream's
   * {@code skip} therefore meets the same timeouts as one that reads.</p>
   */
  private static class ChunkedStream extends InputStream {
    private final Set<Integer> timeoutReads = new HashSet<>();
    private int reads;
    private int pos;

    ChunkedStream(int... timeoutReads) {
      for (int read : timeoutReads) {
        this.timeoutReads.add(read);
      }
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (timeoutReads.contains(reads++)) {
        throw new SocketTimeoutException("Read timed out");
      }
      if (pos >= DATA.length) {
        return -1;
      }
      int n = Math.min(Math.min(len, CHUNK), DATA.length - pos);
      System.arraycopy(DATA, pos, b, off, n);
      pos += n;
      return n;
    }
  }

  private static VisibleBufferedInputStream stream(InputStream wrapped) {
    return new VisibleBufferedInputStream(wrapped, 1024);
  }

  /**
   * A discard that the buffer can serve must not read the wrapped stream, because on a socket
   * that read can block. After the first read the buffer holds {@code CHUNK - 1} bytes, which is
   * the largest count this test uses.
   */
  @ParameterizedTest
  @ValueSource(ints = {1, CHUNK - 1})
  void aDiscardWithinTheBufferedBytesDoesNotReadTheWrappedStream(int count) throws IOException {
    ChunkedStream wrapped = new ChunkedStream();
    VisibleBufferedInputStream in = stream(wrapped);
    in.read();
    int readsBefore = wrapped.reads;

    long skipped = in.skip(count);
    int readsDuring = wrapped.reads - readsBefore;
    int next = in.read();

    assertAll(
        () -> assertEquals(count, skipped, "skip(" + count + ")"),
        () -> assertEquals(0, readsDuring, "reads on the wrapped stream during the discard"),
        () -> assertEquals(DATA[1 + count] & 0xFF, next, "read() after the discard"));
  }

  /**
   * {@code CHUNK} is one byte more than the buffer holds after the first read, and 1000 spans
   * several reads of the wrapped stream.
   */
  @ParameterizedTest
  @ValueSource(ints = {CHUNK, 1000})
  void aDiscardBeyondTheBufferedBytesCountsEveryByte(int count) throws IOException {
    VisibleBufferedInputStream in = stream(new ChunkedStream());
    in.read();

    long skipped = in.skip(count);
    int next = in.read();

    assertAll(
        () -> assertEquals(count, skipped, "skip(" + count + ")"),
        () -> assertEquals(DATA[1 + count] & 0xFF, next, "read() after the discard"));
  }

  /**
   * The driver discards whole protocol messages, and the backend declares their length, so a
   * discard must not size the buffer to the count.
   */
  @Test
  void aDiscardLongerThanTheBufferDoesNotGrowIt() throws IOException {
    VisibleBufferedInputStream in = stream(new ChunkedStream());
    byte[] before = in.getBuffer();

    in.skip(3000);

    assertSame(before, in.getBuffer(), "getBuffer() after skip(3000) on a 1024-byte buffer");
  }

  @ParameterizedTest
  @ValueSource(longs = {LENGTH, LENGTH + 1, Long.MAX_VALUE})
  void aDiscardReachingEndOfStreamReturnsTheBytesThatWereLeft(long count) throws IOException {
    VisibleBufferedInputStream in = stream(new ChunkedStream());

    long skipped = in.skip(count);
    int next = in.read();

    assertAll(
        () -> assertEquals(LENGTH, skipped, "skip(" + count + ")"),
        () -> assertEquals(-1, next, "read() after the discard"));
  }

  /**
   * A negative count used to be returned as the count discarded, and one within the {@code int}
   * range also moved the read position back by that many bytes. After one byte is read, -2 moves
   * it below the start of the buffer, and the next read threw
   * {@link ArrayIndexOutOfBoundsException}.
   */
  @ParameterizedTest
  @ValueSource(longs = {0, -1, -2, Long.MIN_VALUE})
  void aCountOfZeroOrLessDiscardsNothing(long count) throws IOException {
    VisibleBufferedInputStream in = stream(new ChunkedStream());
    in.read();

    long skipped = in.skip(count);
    int next = in.read();

    assertAll(
        () -> assertEquals(0, skipped, "skip(" + count + ")"),
        () -> assertEquals(DATA[1] & 0xFF, next, "read() after the discard"));
  }

  /**
   * The driver sets the socket timeout for its own purposes as well, such as the replication
   * status interval, and a read waits such a timeout out. A discard used to call the wrapped
   * stream's {@code skip} once the buffer was empty, and let its {@link SocketTimeoutException}
   * reach the caller.
   */
  @Test
  void aTimeoutTheCallerDidNotRequestIsWaitedOut() throws IOException {
    VisibleBufferedInputStream in = stream(new ChunkedStream(0, 1));

    long skipped = in.skip(100);
    int next = in.read();

    assertAll(
        () -> assertEquals(100, skipped, "skip(100) across two timeouts"),
        () -> assertEquals(DATA[100] & 0xFF, next, "read() after the discard"));
  }

  /**
   * The bytes the discard took before the timeout stay discarded, and the exception does not
   * report how many there were, so the caller cannot resume the discard.
   */
  @Test
  void aTimeoutTheCallerRequestedIsThrownPartWayThrough() throws IOException {
    // The second read times out, after the discard has taken the CHUNK bytes of the first
    VisibleBufferedInputStream in = stream(new ChunkedStream(1));
    in.setTimeoutRequested(true);

    assertThrows(SocketTimeoutException.class, () -> in.skip(CHUNK + 50),
        "skip(CHUNK + 50) with the timeout requested");
    assertEquals(DATA[CHUNK] & 0xFF, in.read(), "read() after the failed discard");
  }

  /**
   * {@link InputStream#skip(long)} may skip nothing while data is still coming, and a stream
   * supplied through the {@code socketFactory} connection property may do so. The count used to
   * include what the wrapped stream's {@code skip} returned, so such a stream cut the discard
   * short.
   */
  @Test
  void aWrappedStreamThatSkipsNothingDoesNotShortenTheDiscard() throws IOException {
    VisibleBufferedInputStream in = stream(new ChunkedStream() {
      @Override
      public long skip(long n) {
        return 0;
      }
    });

    long skipped = in.skip(100);
    int next = in.read();

    assertAll(
        () -> assertEquals(100, skipped, "skip(100)"),
        () -> assertEquals(DATA[100] & 0xFF, next, "read() after the discard"));
  }
}
