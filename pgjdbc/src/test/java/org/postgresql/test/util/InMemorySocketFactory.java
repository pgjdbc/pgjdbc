/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/**
 * Hands out a connected socket whose input is a fixed byte array, so a {@code PGStream} can
 * be driven over a canned backend response without a network or a server. Everything the
 * driver writes is captured and can be read back with {@link #getSentBytes()}.
 *
 * <p>The socket answers {@code isConnected()} and {@code connect()} without doing anything,
 * which is all the {@code PGStream} constructor asks of it.</p>
 */
public class InMemorySocketFactory extends SocketFactory {
  private final byte[] inputBytes;
  private final boolean refuseToSkip;
  private final ByteArrayOutputStream sentBytes = new ByteArrayOutputStream();

  public InMemorySocketFactory(byte[] inputBytes) {
    this(inputBytes, false);
  }

  private InMemorySocketFactory(byte[] inputBytes, boolean refuseToSkip) {
    this.inputBytes = inputBytes.clone();
    this.refuseToSkip = refuseToSkip;
  }

  /**
   * Hands out a socket whose input stream returns 0 from every {@code skip} call, which
   * {@link InputStream#skip(long)} permits. A reader that takes that 0 as the end of the
   * stream stops early, and one that retries without reading never advances, so a reader
   * that discards bytes has to read to make progress.
   */
  public static InMemorySocketFactory refusingToSkip(byte[] inputBytes) {
    return new InMemorySocketFactory(inputBytes, true);
  }

  private InputStream openInput() {
    ByteArrayInputStream bytes = new ByteArrayInputStream(inputBytes);
    if (!refuseToSkip) {
      return bytes;
    }
    return new FilterInputStream(bytes) {
      @Override
      public long skip(long n) {
        return 0;
      }
    };
  }

  /**
   * Returns everything the driver has written to the socket so far.
   */
  public byte[] getSentBytes() {
    return sentBytes.toByteArray();
  }

  @Override
  public Socket createSocket() {
    return new Socket() {
      private final InputStream in = openInput();
      private boolean closed;

      @Override
      public boolean isConnected() {
        return !closed;
      }

      @Override
      public boolean isClosed() {
        return closed;
      }

      @Override
      public synchronized void close() {
        closed = true;
      }

      @Override
      public InputStream getInputStream() {
        return in;
      }

      @Override
      public OutputStream getOutputStream() {
        return sentBytes;
      }

      @Override
      public void connect(SocketAddress endpoint, int timeout) {
        // already connected
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
