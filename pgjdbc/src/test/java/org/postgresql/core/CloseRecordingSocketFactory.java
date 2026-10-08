/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.HostSpec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/**
 * Creates a socket that serves a fixed payload, records what the driver writes and flushes, and
 * counts how often the socket itself is closed. The output can be made to fail the way it does on
 * a connection that has already dropped.
 */
final class CloseRecordingSocketFactory extends SocketFactory {
  /** What a write or a flush on the socket's output does. */
  enum Output {
    /** Records the bytes and the flush. */
    WORKS,
    /**
     * Throws {@link IOException}: {@code Broken pipe} the first time, {@code Socket closed} after
     * that, so a test can tell which of two failures a caller reported.
     */
    FAILS,
    /**
     * Throws {@link IllegalStateException}, which a socket from the {@code socketFactory}
     * connection property may do, since the driver does not control its code.
     */
    FAILS_UNCHECKED
  }

  /** Bytes the socket serves. */
  private static final byte[] PAYLOAD = {1, 2, 3, 4, 5, 6, 7, 8};

  private final Output output;
  private final boolean socketCloseFails;

  /** Bytes that reached the socket's output. */
  final ByteArrayOutputStream written = new ByteArrayOutputStream();
  /** Flushes that reached the socket's output. */
  int flushes;
  /** Calls to {@link Socket#close()}, including failed ones. */
  int socketCloses;
  /** Whether closing the socket's input stream throws {@code Input close failed}. */
  boolean inputCloseFails;
  /** What {@link Socket#isClosed()} returns; {@link Socket#close()} leaves it unchanged. */
  boolean socketClosed;
  private int outputFailures;
  private int soTimeout;

  CloseRecordingSocketFactory(Output output, boolean socketCloseFails) {
    this.output = output;
    this.socketCloseFails = socketCloseFails;
  }

  /** Creates a {@link PGStream} over a new socket and buffers the whole payload. */
  PGStream primedStream() throws IOException {
    PGStream stream = new PGStream(this, new HostSpec("localhost", 5432), 0, 8192);
    // receiveChar returns the first byte and leaves the rest of the payload in the buffer
    stream.receiveChar();
    return stream;
  }

  private IOException outputFailure() {
    if (output == Output.FAILS_UNCHECKED) {
      throw new IllegalStateException("Broken pipe");
    }
    return new IOException(outputFailures++ == 0 ? "Broken pipe" : "Socket closed");
  }

  @Override
  public Socket createSocket() {
    return new Socket() {
      private final InputStream in = new ByteArrayInputStream(PAYLOAD) {
        @Override
        public void close() throws IOException {
          if (inputCloseFails) {
            throw new IOException("Input close failed");
          }
        }
      };
      private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
          if (output != Output.WORKS) {
            throw outputFailure();
          }
          written.write(b);
        }

        @Override
        public void flush() throws IOException {
          if (output != Output.WORKS) {
            throw outputFailure();
          }
          flushes++;
        }
      };

      @Override
      public boolean isConnected() {
        return true;
      }

      @Override
      public boolean isClosed() {
        return socketClosed;
      }

      @Override
      public void connect(SocketAddress endpoint, int timeout) {
      }

      @Override
      public void setTcpNoDelay(boolean on) {
      }

      @Override
      public int getSoTimeout() {
        return soTimeout;
      }

      @Override
      public void setSoTimeout(int timeout) {
        soTimeout = timeout;
      }

      @Override
      public void setSoLinger(boolean on, int linger) {
        // Socket.setSoLinger would create a real descriptor to set the option on.
      }

      @Override
      public int getSendBufferSize() {
        return 8192;
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
      public void close() throws IOException {
        socketCloses++;
        if (socketCloseFails) {
          throw new IOException("Socket close failed");
        }
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
