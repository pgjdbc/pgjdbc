/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
 * {@link PGStream#skip(int)} discards exactly the bytes it was asked for, and throws
 * {@link EOFException} when end of stream comes first. A size of zero or less discards nothing.
 *
 * <p>A short discard leaves the connection off a message boundary, which a later read reports as
 * a protocol error, and a discard that never finishes hangs the connection.</p>
 *
 * <p>{@link PGStream#skip(int)} treats a count short of the request as end of stream, which holds
 * because {@link VisibleBufferedInputStream#skip(long)} returns one only there. The source stream
 * stands in for one supplied through the {@code socketFactory} connection property, and its
 * {@link InputStream#skip(long)} skips nothing, which that method may do while data is still
 * coming. A {@link VisibleBufferedInputStream} that passed the discard on to it would fail these
 * tests.</p>
 */
// VisibleBufferedInputStream.skip discards in a loop, so a wrong loop condition spins instead of
// returning, and PGStream.skip spun at end of stream before PR #4358. The timeout turns a spin into
// a failure of the code under test instead of a stalled build, and it needs the separate thread
// mode to do that: the default mode checks the clock only after the test method returns, and a
// spinning method never returns
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class PGStreamSkipTest {
  private static final int LENGTH = 50000;

  /**
   * Payload the stream serves. A byte cannot encode an offset in a payload this long, so the
   * pattern separates the offsets a wrong discard is most likely to land on: multiplying by seven
   * separates neighbors, and adding {@code i >> 8} separates offsets a multiple of 256 apart. The
   * closest offsets it leaves equal are 73 apart, so the test also counts the bytes left after
   * the discard.
   */
  private static final byte[] DATA = new byte[LENGTH];

  static {
    for (int i = 0; i < DATA.length; i++) {
      DATA[i] = (byte) (i * 7 + 1 + (i >> 8));
    }
  }

  /**
   * 20000 needs several fills of the 8192-byte buffer under {@link PGStream}, so a single call to
   * {@link VisibleBufferedInputStream#skip(long)} has to discard across them, and
   * {@code LENGTH - 1} leaves one byte before end of stream.
   */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 20000, LENGTH - 1})
  void skipDiscardsExactlyTheRequestedBytes(int size) throws Exception {
    try (PGStream stream = openStream()) {
      stream.skip(size);
      int next = stream.receiveChar();
      int left = drain(stream);

      assertAll(
          () -> assertEquals(DATA[size] & 0xFF, next, "receiveChar() after the discard"),
          () -> assertEquals(LENGTH - size - 1, left, "bytes left after receiveChar()"));
    }
  }

  @Test
  void skipOfTheWholePayloadLeavesNothingToRead() throws Exception {
    try (PGStream stream = openStream()) {
      stream.skip(LENGTH);

      assertEquals(0, drain(stream), "bytes left after skip(" + LENGTH + ")");
    }
  }

  /**
   * {@link PGStream#skip(int)} passes a negative size on to
   * {@link VisibleBufferedInputStream#skip(long)}, which returns 0 for it, so the short-count check
   * must not read that 0 as end of stream.
   */
  @Test
  void skipOfANegativeSizeDiscardsNothing() throws Exception {
    try (PGStream stream = openStream()) {
      stream.skip(-1);

      assertEquals(DATA[0] & 0xFF, stream.receiveChar(), "receiveChar() after skip(-1)");
    }
  }

  @Test
  void skipPastEndOfStreamThrowsEOFException() throws Exception {
    try (PGStream stream = openStream()) {
      assertThrows(EOFException.class, () -> stream.skip(LENGTH + 1),
          "skip(" + (LENGTH + 1) + ") on a " + LENGTH + "-byte payload");
    }
  }

  /** Reads to end of stream and returns how many bytes were still there. */
  private static int drain(PGStream stream) throws IOException {
    int read = 0;
    while (true) {
      try {
        stream.receiveChar();
      } catch (EOFException e) {
        return read;
      }
      read++;
    }
  }

  /**
   * Opens a {@link PGStream} that reads {@link #DATA}, with no server behind it. The 8192
   * argument sizes the send buffer; it does not size the buffer the driver reads through when it
   * skips, which is a separate fixed 8192 set up when the socket is attached.
   */
  private static PGStream openStream() throws IOException {
    return new PGStream(new FixedSocketFactory(new SkipsNothingStream(new ByteArrayInputStream(DATA))),
        new HostSpec("localhost", 5432), 0, 8192);
  }

  /** Serves the wrapped stream and skips nothing. */
  static final class SkipsNothingStream extends FilterInputStream {
    SkipsNothingStream(InputStream in) {
      super(in);
    }

    @Override
    public long skip(long n) {
      return 0;
    }
  }

  /**
   * Supplies a socket whose input is the given stream, so no server is involved.
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
