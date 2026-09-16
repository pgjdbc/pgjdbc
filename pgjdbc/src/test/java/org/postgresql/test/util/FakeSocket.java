/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * Serves the given input as socket input, and records the bytes written, SO_LINGER, and the calls
 * to {@code close()}. Other socket options are accepted and ignored.
 *
 * <p>Like a real socket, it throws once closed: a write throws {@link IOException}, and the
 * SO_TIMEOUT accessors throw {@link SocketException} with the message
 * {@value #CLOSED_MESSAGE}. A test can make SO_LINGER or {@code close()} fail; a failed
 * {@code close()} leaves the socket open.</p>
 */
public final class FakeSocket extends Socket {
  /** Message of the {@link IOException} thrown by a {@code close()} the test made fail. */
  public static final String CLOSE_FAILURE = "close failed by the test";

  /** Message of the {@link SocketException} the SO_TIMEOUT accessors throw once closed. */
  public static final String CLOSED_MESSAGE = "Socket is closed";

  private final InputStream input;
  private final ByteArrayOutputStream sent = new ByteArrayOutputStream();
  private final OutputStream output = new OutputStream() {
    @Override
    public void write(int b) throws IOException {
      ensureOpen();
      sent.write(b);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      ensureOpen();
      sent.write(b, off, len);
    }
  };

  /** When true, {@code setSoLinger} throws {@link SocketException}. */
  public volatile boolean refuseSoLinger;
  /** The number of upcoming {@code close()} calls that throw and leave the socket open. */
  public volatile int failingCloses;
  public volatile int closeCalls;
  public volatile boolean lingerSet;
  public volatile boolean lingerOn;
  public volatile int lingerSeconds = -1;
  public volatile boolean closed;
  private int soTimeout;

  public FakeSocket() {
    this(new byte[0]);
  }

  public FakeSocket(byte[] input) {
    this(new ByteArrayInputStream(input));
  }

  public FakeSocket(InputStream input) {
    this.input = input;
  }

  /** Returns the bytes written to the socket output so far. */
  public synchronized byte[] written() {
    return sent.toByteArray();
  }

  private synchronized void ensureOpen() throws IOException {
    if (closed) {
      throw new IOException("write to a closed socket");
    }
  }

  private void ensureOpenForOption() throws SocketException {
    if (closed) {
      throw new SocketException(CLOSED_MESSAGE);
    }
  }

  @Override
  public boolean isConnected() {
    return true;
  }

  @Override
  public void connect(SocketAddress endpoint, int timeout) {
  }

  @Override
  public void connect(SocketAddress endpoint) {
  }

  @Override
  public void setTcpNoDelay(boolean on) {
  }

  @Override
  public boolean getTcpNoDelay() {
    return true;
  }

  @Override
  public void setKeepAlive(boolean on) {
  }

  @Override
  public boolean getKeepAlive() {
    return false;
  }

  @Override
  public synchronized int getSoTimeout() throws SocketException {
    ensureOpenForOption();
    return soTimeout;
  }

  @Override
  public synchronized void setSoTimeout(int timeout) throws SocketException {
    ensureOpenForOption();
    soTimeout = timeout;
  }

  @Override
  public synchronized void setSendBufferSize(int size) {
  }

  @Override
  public synchronized int getSendBufferSize() {
    return 8192;
  }

  @Override
  public synchronized void setReceiveBufferSize(int size) {
  }

  @Override
  public synchronized int getReceiveBufferSize() {
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
  public void setSoLinger(boolean on, int linger) throws SocketException {
    if (refuseSoLinger) {
      throw new SocketException("SO_LINGER refused by the test");
    }
    lingerSet = true;
    lingerOn = on;
    lingerSeconds = linger;
  }

  @Override
  public synchronized void close() throws IOException {
    closeCalls++;
    if (failingCloses > 0) {
      failingCloses--;
      throw new IOException(CLOSE_FAILURE);
    }
    closed = true;
  }

  @Override
  public boolean isClosed() {
    return closed;
  }
}
