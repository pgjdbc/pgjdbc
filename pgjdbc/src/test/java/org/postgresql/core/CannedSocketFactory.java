/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

import javax.net.SocketFactory;

/**
 * Creates sockets that read a canned byte script and record everything written to them. The script
 * is fixed before the test runs, so it does not depend on what the driver writes, and that is
 * enough to drive a reader with no server behind the socket. A read past the end of the script
 * reports end of stream.
 */
public class CannedSocketFactory extends SocketFactory {
  /** Every socket this factory creates replays these bytes from the start. */
  private final byte[] script;
  private CannedSocket socket;

  public CannedSocketFactory(byte[] script) {
    this.script = script;
    this.socket = new CannedSocket(script);
  }

  /**
   * Returns everything the driver has written to the socket created most recently.
   * {@link #createSocket()} replaces that socket, and what was written to the one before it is
   * dropped.
   */
  public byte[] getWritten() {
    return socket.written.toByteArray();
  }

  @Override
  public Socket createSocket() {
    socket = new CannedSocket(script);
    return socket;
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
  public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) {
    return createSocket();
  }

  /**
   * A socket with no operating system socket behind it. It reports itself connected, so the driver
   * neither connects it nor resolves an address, and each socket option is either recorded or
   * ignored.
   */
  private static class CannedSocket extends Socket {
    private final InputStream in;
    private final ByteArrayOutputStream written = new ByteArrayOutputStream();
    private int soTimeout;

    CannedSocket(byte[] script) {
      this.in = new ByteArrayInputStream(script);
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public InputStream getInputStream() {
      return in;
    }

    @Override
    public OutputStream getOutputStream() {
      return written;
    }

    @Override
    public void setTcpNoDelay(boolean on) {
    }

    @Override
    public int getSendBufferSize() {
      return 8192;
    }

    @Override
    public void setSoTimeout(int timeout) {
      this.soTimeout = timeout;
    }

    @Override
    public int getSoTimeout() {
      return soTimeout;
    }

    @Override
    public void setSoLinger(boolean on, int linger) {
      // Socket.setSoLinger would create a real descriptor to set the option on.
    }

    /**
     * Leaves the socket open, so {@link Socket#isClosed()} stays false and
     * {@link PGStream#isClosed()} reports only whether the driver gave up on the stream.
     */
    @Override
    public void close() {
    }
  }
}
