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
 * Hands out sockets that read a canned byte script and collect everything written to them. The
 * driver writes its request and then reads the reply, so a script is enough to drive a reader
 * without a server.
 */
public class CannedSocketFactory extends SocketFactory {
  private final byte[] script;
  private CannedSocket socket;

  public CannedSocketFactory(byte[] script) {
    this.script = script;
    this.socket = new CannedSocket(script);
  }

  /** Everything the driver has written so far. */
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

    @Override
    public void close() {
    }
  }
}
