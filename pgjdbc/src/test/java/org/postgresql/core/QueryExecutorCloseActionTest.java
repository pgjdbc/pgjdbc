/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/**
 * Fails when {@link QueryExecutorCloseAction} leaves open a descriptor that
 * {@link PGStream#markBroken(Throwable)} could not release, or when it raises an error on a
 * socket {@code markBroken} already closed.
 *
 * <p>{@code markBroken} swallows an {@code IOException} raised by {@code Socket.close()}, so
 * a stream can report itself closed while the descriptor is still open. Releasing it is the
 * close path's job.</p>
 */
class QueryExecutorCloseActionTest {

  /**
   * In-memory socket whose {@code close()} can be made to fail once, reproducing the case
   * where {@link PGStream#markBroken(Throwable)} flags the stream but cannot release the
   * descriptor.
   */
  private static final class FlakyCloseSocket extends Socket {
    private final InputStream in = new ByteArrayInputStream(new byte[0]);
    private final OutputStream out = new OutputStream() {
      @Override
      public void write(int b) {
        // discard
      }
    };
    private boolean closed;
    boolean failNextClose;

    @Override
    public boolean isConnected() {
      return !closed;
    }

    @Override
    public boolean isClosed() {
      return closed;
    }

    @Override
    public synchronized void close() throws IOException {
      if (failNextClose) {
        failNextClose = false;
        throw new IOException("synthetic close failure");
      }
      closed = true;
    }

    @Override
    public InputStream getInputStream() {
      return in;
    }

    @Override
    public OutputStream getOutputStream() {
      return out;
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) {
      // already connected
    }
  }

  private static SocketFactory factoryFor(final Socket socket) {
    return new SocketFactory() {
      @Override
      public Socket createSocket() {
        return socket;
      }

      @Override
      public Socket createSocket(String host, int port) {
        return socket;
      }

      @Override
      public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
        return socket;
      }

      @Override
      public Socket createSocket(InetAddress host, int port) {
        return socket;
      }

      @Override
      public Socket createSocket(InetAddress address, int port, InetAddress localAddress,
          int localPort) {
        return socket;
      }
    };
  }

  @Test
  void closeReleasesTheDescriptorMarkBrokenCouldNotRelease() throws IOException {
    FlakyCloseSocket socket = new FlakyCloseSocket();
    PGStream pgStream = new PGStream(factoryFor(socket), new HostSpec("localhost", 1), 0, 8192);

    socket.failNextClose = true;
    pgStream.markBroken(new IOException("synthetic desync"));

    // markBroken swallowed the close failure, so the stream reports itself unusable while
    // the descriptor is still open. This is the state the close path has to clean up.
    assertTrue(pgStream.isClosed(), "markBroken must flag the stream regardless");
    assertFalse(pgStream.isSocketClosed(),
        "The synthetic failure must leave the socket open, or the test proves nothing");

    new QueryExecutorCloseAction(pgStream).close();

    assertTrue(pgStream.isSocketClosed(),
        "close() must release the descriptor markBroken could not");
  }

  @Test
  void closeIsQuietWhenTheSocketIsAlreadyGone() throws IOException {
    FlakyCloseSocket socket = new FlakyCloseSocket();
    PGStream pgStream = new PGStream(factoryFor(socket), new HostSpec("localhost", 1), 0, 8192);

    pgStream.markBroken(new IOException("synthetic desync"));
    assertTrue(pgStream.isSocketClosed(), "markBroken closed the socket on the happy path");

    // Nothing left to do, and in particular nothing to flush at a peer that is gone.
    new QueryExecutorCloseAction(pgStream).close();

    assertTrue(pgStream.isSocketClosed());
  }
}
