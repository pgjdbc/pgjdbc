/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * Fails when a read path of {@link VisibleBufferedInputStream} takes a wrapped stream that read
 * nothing for one that has ended, or reports it to the caller as a read of nothing.
 *
 * <p>Two things reach this stream and read nothing without having ended:
 * {@link org.postgresql.gss.GSSInputStream}, which reads nothing whenever a GSS frame is still
 * incomplete, and a socket whose timeout the driver set for its own purposes, such as the
 * replication status interval. Neither is the end of the stream, and neither is progress. Reading
 * a single byte is what tells them apart, because {@link InputStream#read()} can only block,
 * deliver a byte, or report {@code -1}.</p>
 *
 * <p>Getting that wrong shows up differently in each path.
 * {@link VisibleBufferedInputStream#read(byte[], int, int)} returns {@code 0} for a non-empty
 * request, which {@link InputStream#read(byte[], int, int)} forbids and which makes a caller spin
 * or read the connection as ended. {@link VisibleBufferedInputStream#ensureBytes(int)} and
 * {@link VisibleBufferedInputStream#scanCStringLength()} count it as progress and loop over a
 * request that is never satisfied.</p>
 *
 * <p>Two things stay as they were: a socket timeout the caller asked for through
 * {@link PGStream#setNetworkTimeout(int)} surfaces, and bytes already delivered come back as a
 * short read rather than being waited on. A non-blocking {@code ensureBytes} still reports that
 * nothing is ready instead of waiting for a byte, which is what
 * {@link PGStream#hasMessagePending()} probes with.</p>
 */
// A stream that reads nothing used to end the read; getting that wrong the other way spins here
// instead, so cap the wait. The separate thread is what makes the cap work, since the default mode
// only checks the clock once the test method returns, which a spinning one never does
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class VisibleBufferedInputStreamReadTest {
  /**
   * Payload the streams serve. Every byte differs from its neighbours, so a read that assembles the
   * payload out of order is visible in the result.
   */
  private static final byte[] DATA = new byte[64];

  /**
   * Request size that reaches the wrapped stream directly. Anything closer than
   * {@code MINIMUM_READ} to what is buffered is served through the buffer instead.
   */
  private static final int DIRECT = 4096;

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1);
    }
  }

  /**
   * Reads nothing into an array, the way {@link org.postgresql.gss.GSSInputStream} does while a
   * frame is still incomplete, and serves the payload one byte at a time through the single-byte
   * read that has no way to say "nothing".
   */
  private static class ReadsNothing extends InputStream {
    private final byte[] data;
    private int pos;
    int arrayReads;

    ReadsNothing(byte[] data) {
      this.data = data;
    }

    @Override
    public int read() {
      return pos < data.length ? data[pos++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      arrayReads++;
      return 0;
    }
  }

  /**
   * Serves one array read of {@code first} bytes and reads nothing after that. The single-byte read
   * reports the end, so a stream that has stalled is distinguishable from one that has ended only
   * by which of the two the driver asks.
   */
  private static class ReadsNothingAfter extends InputStream {
    private final byte[] data;
    private final int first;
    private int pos;

    ReadsNothingAfter(byte[] data, int first) {
      this.data = data;
      this.first = first;
    }

    @Override
    public int read() {
      return -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
      if (pos > 0) {
        return 0;
      }
      int n = Math.min(Math.min(len, first), data.length);
      System.arraycopy(data, 0, b, off, n);
      pos = n;
      return n;
    }
  }

  /**
   * Throws {@link SocketTimeoutException} for the first {@code timeouts} array reads, then serves
   * the payload.
   */
  private static class TimesOut extends InputStream {
    private final byte[] data;
    private int timeouts;
    private int pos;
    int arrayReads;

    TimesOut(byte[] data, int timeouts) {
      this.data = data;
      this.timeouts = timeouts;
    }

    @Override
    public int read() throws IOException {
      byte[] one = new byte[1];
      int n = read(one, 0, 1);
      return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      arrayReads++;
      if (timeouts > 0) {
        timeouts--;
        throw new SocketTimeoutException("Read timed out");
      }
      if (pos >= data.length) {
        return -1;
      }
      int n = Math.min(len, data.length - pos);
      System.arraycopy(data, pos, b, off, n);
      pos += n;
      return n;
    }
  }

  @Test
  void aStreamThatReadsNothingDeliversAByteInstead() throws IOException {
    ReadsNothing wrapped = new ReadsNothing(DATA);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);
    byte[] to = new byte[DIRECT];

    int read = in.read(to, 0, DIRECT);

    assertEquals(1, read, "bytes read from a stream that reads nothing into an array");
    assertEquals(DATA[0] & 0xFF, to[0] & 0xFF, "first byte delivered");
    assertTrue(wrapped.arrayReads > 0, "the array read was tried before falling back");
  }

  @Test
  void aStreamThatReadsNothingStillDeliversEverything() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ReadsNothing(DATA), 1024);
    byte[] to = new byte[DIRECT];

    int total = 0;
    while (total < DATA.length) {
      int read = in.read(to, total, DIRECT - total);
      assertTrue(read > 0, "read at offset " + total);
      total += read;
    }

    assertArrayEquals(DATA, Arrays.copyOf(to, DATA.length),
        "payload assembled one short read at a time");
    assertEquals(-1, in.read(to, 0, DIRECT), "read past the end of the stream");
  }

  @Test
  void aStreamThatReadsNothingAndHasEndedReportsTheEnd() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ReadsNothing(new byte[0]), 1024);
    byte[] to = new byte[DIRECT];

    assertEquals(-1, in.read(to, 0, DIRECT), "read of a stream that has ended");
  }

  @Test
  void aStreamThatReadsNothingAfterSomeBytesGivesAShortRead() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ReadsNothingAfter(DATA, 16), 1024);
    byte[] to = new byte[DIRECT];

    int read = in.read(to, 0, DIRECT);

    assertEquals(16, read, "bytes read before the stream stalled");
    assertArrayEquals(Arrays.copyOf(DATA, 16), Arrays.copyOf(to, 16), "bytes delivered");
  }

  @Test
  void ensureBytesWaitsForAByteWhenTheStreamReadsNothing() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ReadsNothing(DATA), 1024);

    assertTrue(in.ensureBytes(4), "ensureBytes(4) against a stream that reads nothing");
    assertEquals(DATA[0] & 0xFF, in.read(), "first buffered byte");
  }

  @Test
  void ensureBytesReportsTheEndWhenAStreamThatReadsNothingHasEnded() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ReadsNothing(new byte[0]), 1024);

    assertFalse(in.ensureBytes(4), "ensureBytes(4) against a stream that has ended");
  }

  @Test
  void aNonBlockingEnsureBytesDoesNotWaitForAByte() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ReadsNothing(DATA), 1024);

    assertFalse(in.ensureBytes(4, false), "non-blocking ensureBytes over a stream that read nothing");
    assertEquals(DATA[0] & 0xFF, in.read(), "the probe consumed no byte");
  }

  @Test
  void scanCStringLengthWaitsForAByteWhenTheStreamReadsNothing() throws IOException {
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new ReadsNothing(new byte[]{'a', 'b', 0}), 1024);

    assertEquals(3, in.scanCStringLength(), "length of the C string, terminator included");
  }

  @Test
  void aTimeoutTheCallerDidNotAskForIsWaitedOut() throws IOException {
    TimesOut wrapped = new TimesOut(DATA, 2);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);
    byte[] to = new byte[DIRECT];

    int read = in.read(to, 0, DIRECT);

    assertEquals(DATA.length, read, "bytes read after two timeouts were waited out");
    assertTrue(wrapped.arrayReads >= 3, "array reads: both timeouts were retried, then the payload");
    assertArrayEquals(DATA, Arrays.copyOf(to, DATA.length), "payload delivered");
  }

  /**
   * Which read of the wrapped stream raises the timeout. {@code SINGLE} is reached only through the
   * fallback, so its array read has to serve nothing.
   */
  private enum Thrower { ARRAY, SINGLE }

  /**
   * Times out from the read named by {@code thrower} until its budget runs out, then serves the
   * payload. What is left of that budget is how a test tells a timeout that was retried from one
   * that never fired.
   */
  private static class TimesOutFrom extends InputStream {
    private final byte[] data;
    private final Thrower thrower;
    private int timeouts;
    private int pos;

    TimesOutFrom(byte[] data, Thrower thrower, int timeouts) {
      this.data = data;
      this.thrower = thrower;
      this.timeouts = timeouts;
    }

    int timeoutsLeft() {
      return timeouts;
    }

    @Override
    public int read() throws IOException {
      if (thrower == Thrower.SINGLE && timeouts > 0) {
        timeouts--;
        throw new SocketTimeoutException("Read timed out");
      }
      return pos < data.length ? data[pos++] & 0xFF : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (thrower == Thrower.SINGLE) {
        // Reads nothing, which is what sends the driver to the single-byte read
        return 0;
      }
      if (timeouts > 0) {
        timeouts--;
        throw new SocketTimeoutException("Read timed out");
      }
      if (pos >= data.length) {
        return -1;
      }
      int n = Math.min(len, data.length - pos);
      System.arraycopy(data, pos, b, off, n);
      pos += n;
      return n;
    }
  }

  @ParameterizedTest
  @EnumSource(Thrower.class)
  void aTimeoutTheCallerAskedForIsThrownByRead(Thrower thrower) {
    TimesOutFrom wrapped = new TimesOutFrom(DATA, thrower, 1);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);
    in.setTimeoutRequested(true);
    byte[] to = new byte[DIRECT];

    assertThrows(SocketTimeoutException.class, () -> in.read(to, 0, DIRECT),
        "read of a stream that timed out from its " + thrower + " read");
    assertEquals(0, wrapped.timeoutsLeft(), "the timeout the throw came from actually fired");
  }

  @ParameterizedTest
  @EnumSource(Thrower.class)
  void aTimeoutTheCallerDidNotAskForIsWaitedOutByRead(Thrower thrower) throws IOException {
    TimesOutFrom wrapped = new TimesOutFrom(DATA, thrower, 2);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);
    byte[] to = new byte[DIRECT];

    int read = in.read(to, 0, DIRECT);

    assertTrue(read > 0, "bytes read after two " + thrower + " timeouts were waited out");
    assertEquals(DATA[0] & 0xFF, to[0] & 0xFF, "first byte delivered");
    assertEquals(0, wrapped.timeoutsLeft(), "both timeouts were retried rather than skipped");
  }

  @ParameterizedTest
  @EnumSource(Thrower.class)
  void aTimeoutTheCallerAskedForIsThrownByEnsureBytes(Thrower thrower) {
    TimesOutFrom wrapped = new TimesOutFrom(DATA, thrower, 1);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);
    in.setTimeoutRequested(true);

    assertThrows(SocketTimeoutException.class, () -> in.ensureBytes(4),
        "ensureBytes over a stream that timed out from its " + thrower + " read");
    assertEquals(0, wrapped.timeoutsLeft(), "the timeout the throw came from actually fired");
  }

  @ParameterizedTest
  @EnumSource(Thrower.class)
  void aTimeoutTheCallerDidNotAskForIsWaitedOutByEnsureBytes(Thrower thrower) throws IOException {
    TimesOutFrom wrapped = new TimesOutFrom(DATA, thrower, 2);
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);

    assertTrue(in.ensureBytes(4), "ensureBytes after two " + thrower + " timeouts were waited out");
    assertEquals(DATA[0] & 0xFF, in.read(), "first buffered byte");
    assertEquals(0, wrapped.timeoutsLeft(), "both timeouts were retried rather than skipped");
  }

  @Test
  void aTimeoutAfterSomeBytesGivesAShortRead() throws IOException {
    // One array read serves the payload, the next one times out
    VisibleBufferedInputStream in =
        new VisibleBufferedInputStream(new TimesOut(DATA, 0) {
          @Override
          public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n < 0) {
              throw new SocketTimeoutException("Read timed out");
            }
            return n;
          }
        }, 1024);
    in.setTimeoutRequested(true);
    byte[] to = new byte[DIRECT];

    int read = in.read(to, 0, DIRECT);

    assertEquals(DATA.length, read, "bytes read before the timeout");
    assertArrayEquals(DATA, Arrays.copyOf(to, DATA.length), "payload delivered");
  }
}
