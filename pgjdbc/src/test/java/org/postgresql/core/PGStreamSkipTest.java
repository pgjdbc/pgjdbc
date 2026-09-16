/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import javax.net.SocketFactory;

/**
 * Fails when {@link PGStream#skip(int)} does not discard exactly the bytes it was asked for.
 *
 * <p>To discard bytes, the driver calls {@link InputStream#skip(long)}, which may return 0 even
 * when more data is coming. A stream at end-of-stream also returns 0, on every call.
 * {@code skip()} must distinguish the two: a 0 at end-of-stream must raise {@link EOFException},
 * while a 0 from a live stream must be followed by a read, not treated as the end. Confusing them
 * either hangs the connection (retrying a stream at end-of-stream forever) or breaks a working one
 * (giving up on a live stream). Discarding the wrong number of bytes leaves the connection off a
 * message boundary, which a later read reports as a protocol error.</p>
 *
 * <p>The streams below simulate what a custom {@code socketFactory} may hand the driver: an
 * {@link InputStream} implementation the driver did not write and cannot make assumptions
 * about.</p>
 */
// Before the fix, a stream that never makes progress made skip() spin forever. Cap the wait
// so that a hang fails the test instead of stalling the build. SEPARATE_THREAD is required:
// the default timeout mode only checks the clock after the test method returns, so it can
// never interrupt a test that is spinning.
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PGStreamSkipTest {
  /**
   * Payload the stream serves. Every byte differs from its neighbours, so a skip that lands at the
   * wrong offset is visible in the byte read after it.
   */
  private static final byte[] DATA = new byte[256];

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1);
    }
  }

  /**
   * How many bytes a wrapped stream agrees to skip per call.
   */
  enum SkipStyle {
    /** Skips the full amount requested (the normal case). */
    HONEST,
    /** Always skips 0, which InputStream.skip() is allowed to do. */
    REFUSES,
    /** Skips one byte per call: real progress, but slow. */
    ONE_AT_A_TIME
  }

  @Test
  void skipDiscardsExactlyTheRequestedBytes() throws Exception {
    // A zero-byte skip never calls down to the wrapped stream, and the read buffer starts empty
    // on a fresh stream, so every other size here reaches the wrapped stream on its first skip.
    // DATA.length is included so a skip of exactly the payload is covered: the byte-after-the-skip
    // assertion below is then skipped, since there is no byte after it.
    for (SkipStyle style : SkipStyle.values()) {
      for (int size : new int[]{0, 1, 100, DATA.length - 1, DATA.length}) {
        CountingStream source = new CountingStream(new ByteArrayInputStream(DATA), style);
        try (PGStream stream = openStream(source)) {
          String label = style + " skip=" + size;
          stream.skip(size);
          if (size < DATA.length) {
            assertEquals(DATA[size] & 0xFF, stream.receiveChar(), label + ": byte after the skip");
          }
          assertTrue(source.skipCalls > 0 || size == 0,
              label + ": the wrapped stream must have been asked to skip");
        }
      }
    }
  }

  @Test
  void skipReportsAStreamThatEndsEarly() throws Exception {
    for (SkipStyle style : SkipStyle.values()) {
      CountingStream source = new CountingStream(new ByteArrayInputStream(DATA), style);
      try (PGStream stream = openStream(source)) {
        assertThrows(EOFException.class, () -> stream.skip(DATA.length + 1),
            style + ": a stream that ends before the count must be reported, not waited on");
      }
    }
  }

  /**
   * A stream that refuses to skip must not degrade to one read per byte: the read that breaks the
   * refusal primes the buffer, and the next skip drains it wholesale.
   */
  @Test
  void skipDoesNotFallBackToReadingByteByByte() throws Exception {
    byte[] payload = new byte[50000];
    CountingStream source =
        new CountingStream(new ByteArrayInputStream(payload), SkipStyle.REFUSES);
    try (PGStream stream = openStream(source)) {
      stream.skip(payload.length);
      assertTrue(source.readCalls < 100,
          "expected the buffer to carry the skip, got " + source.readCalls + " reads");
    }
  }

  /**
   * Opens a {@link PGStream} that reads from {@code source}, with no server behind it. The 8192
   * argument sizes the send buffer; it does not size the buffer the driver reads through when it
   * skips, which is a separate fixed 8192 set up when the socket is attached.
   */
  private static PGStream openStream(InputStream source) throws IOException {
    return new PGStream(new FixedSocketFactory(source), new HostSpec("localhost", 5432), 0, 8192);
  }

  /**
   * Counts what the driver asked of the stream, and answers skip as the style dictates.
   *
   * <p>Guards against a caller that ignores a 0 return from {@link #skip(long)}: after
   * {@link #ZERO_SKIP_LIMIT} consecutive zero-skips with no intervening read, it throws with an
   * explanatory message instead of letting the caller spin. This turns the bug under test into an
   * immediate, self-describing failure rather than one the {@code @Timeout} has to catch.</p>
   */
  static final class CountingStream extends FilterInputStream {
    /**
     * Number of consecutive skip()==0 calls, with no read() between them, that we treat as a
     * caller stuck making no progress. A read() resets the count, since reading is the correct
     * response to a skip that returned 0.
     */
    static final int ZERO_SKIP_LIMIT = 10;

    private final SkipStyle style;
    private int zeroSkipsInARow;
    int skipCalls;
    int readCalls;

    CountingStream(InputStream in, SkipStyle style) {
      super(in);
      this.style = style;
    }

    @Override
    public long skip(long n) throws IOException {
      skipCalls++;
      long skipped;
      switch (style) {
        case HONEST:
          skipped = super.skip(n);
          break;
        case ONE_AT_A_TIME:
          skipped = n == 0 ? 0 : super.skip(1);
          break;
        default:
          skipped = 0;
          break;
      }
      if (skipped > 0 || n == 0) {
        zeroSkipsInARow = 0;
        return skipped;
      }
      if (++zeroSkipsInARow > ZERO_SKIP_LIMIT) {
        throw new IllegalStateException("skip() returned 0 " + zeroSkipsInARow
            + " times in a row and the caller kept calling skip() without reading anything."
            + " A caller that does that makes no progress and never stops.");
      }
      return 0;
    }

    @Override
    public int read() throws IOException {
      readCalls++;
      zeroSkipsInARow = 0;
      return super.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      readCalls++;
      zeroSkipsInARow = 0;
      return super.read(b, off, len);
    }
  }

  /**
   * Hands the driver a socket whose input is the given stream, so no server is involved.
   */
  static final class FixedSocketFactory extends SocketFactory {
    private final InputStream input;

    FixedSocketFactory(InputStream input) {
      this.input = input;
    }

    @Override
    public Socket createSocket() {
      return new Socket() {
        private final OutputStream output = new ByteArrayOutputStream();

        @Override
        public boolean isConnected() {
          return true;
        }

        @Override
        public void connect(SocketAddress endpoint, int timeout) {
        }

        @Override
        public void setTcpNoDelay(boolean on) {
        }

        @Override
        public int getSendBufferSize() {
          return 8192;
        }

        @Override
        public InputStream getInputStream() {
          return input;
        }

        @Override
        public OutputStream getOutputStream() {
          return output;
        }

        @Override
        public void close() {
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
        int localPort) {
      return createSocket();
    }
  }
}
