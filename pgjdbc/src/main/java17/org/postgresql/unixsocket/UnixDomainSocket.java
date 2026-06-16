/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.unixsocket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A {@link Socket} adapter backed by a Unix domain {@link SocketChannel}.
 *
 * <p>{@code java.net.Socket} cannot speak the Unix protocol family directly, and
 * {@code SocketChannel.socket()} throws for Unix channels, so this class re-exposes a Unix channel
 * through the small slice of the {@code Socket} API that {@code PGStream} relies on:
 * blocking reads and writes with an {@code SO_TIMEOUT}, plus the buffer/keep-alive getters and
 * setters that the driver queries. TCP-only knobs such as {@code TCP_NODELAY} are accepted and
 * ignored.</p>
 */
class UnixDomainSocket extends Socket {

  private final String path;
  private final SocketChannel channel;
  private final Selector readSelector;
  private final Selector writeSelector;

  private volatile int soTimeout;
  private int sendBufferSize = 8192;
  private int receiveBufferSize = 8192;
  private boolean tcpNoDelay = true;
  private boolean keepAlive;
  private volatile boolean closed;

  private InputStream inputStream;
  private OutputStream outputStream;

  UnixDomainSocket(String path) throws IOException {
    super((java.net.SocketImpl) null);
    this.path = path;
    this.channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    this.readSelector = Selector.open();
    this.writeSelector = Selector.open();
  }

  /**
   * Resolves the directory or socket file argument and the port from the JDBC URL into a concrete
   * socket address. A bare directory is turned into {@code <dir>/.s.PGSQL.<port>}, matching the
   * layout that PostgreSQL uses for its Unix sockets; an argument that already points at a socket
   * file is used as is.
   *
   * @param path the {@code socketFactoryArg} value (a directory or a socket file)
   * @param port the port from the connection URL
   * @return the Unix domain socket address to connect to
   */
  static UnixDomainSocketAddress resolveAddress(String path, int port) {
    Path candidate = Path.of(path);
    Path fileName = candidate.getFileName();
    boolean isSocketFile =
        (fileName != null && fileName.toString().startsWith(".s.PGSQL."))
        || (Files.exists(candidate) && !Files.isDirectory(candidate));
    Path socket = isSocketFile ? candidate : candidate.resolve(".s.PGSQL." + port);
    return UnixDomainSocketAddress.of(socket);
  }

  @Override
  public void connect(SocketAddress endpoint) throws IOException {
    connect(endpoint, 0);
  }

  // google-java-format (autostyle) cannot parse pattern-matching instanceof, so use the classic form
  // and suppress the corresponding Error Prone suggestion.
  @Override
  @SuppressWarnings("PatternMatchingInstanceof")
  public void connect(SocketAddress endpoint, int timeout) throws IOException {
    UnixDomainSocketAddress address;
    if (endpoint instanceof UnixDomainSocketAddress) {
      address = (UnixDomainSocketAddress) endpoint;
    } else if (endpoint instanceof InetSocketAddress) {
      // PGStream builds an InetSocketAddress(host, port). The host is irrelevant for a Unix
      // socket; we only use the port to locate the .s.PGSQL.<port> file.
      address = resolveAddress(path, ((InetSocketAddress) endpoint).getPort());
    } else {
      throw new SocketException("Unsupported address type: " + endpoint);
    }
    // Connect in non-blocking mode so the connect honours the caller's timeout (PGStream passes
    // connectTimeout here). A local connect is normally instant, but a stalled listener with a full
    // accept backlog must not hang past the timeout, matching TCP behaviour. A timeout of 0 means
    // no timeout, as for java.net.Socket.
    channel.configureBlocking(false);
    if (!channel.connect(address)) {
      channel.register(writeSelector, SelectionKey.OP_CONNECT);
      while (!channel.finishConnect()) {
        int ready = writeSelector.select(timeout);
        writeSelector.selectedKeys().clear();
        if (ready == 0 && timeout > 0) {
          throw new SocketTimeoutException("Connect timed out after " + timeout + " ms");
        }
      }
    }
    // Reads and writes honour SO_TIMEOUT through the selectors below.
    channel.register(readSelector, SelectionKey.OP_READ);
    channel.register(writeSelector, SelectionKey.OP_WRITE);
  }

  @Override
  public boolean isConnected() {
    return channel.isConnected();
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void bind(SocketAddress bindpoint) throws IOException {
    // Binding the client side of a Unix domain socket is not meaningful here; ignore it so that
    // localSocketAddress handling in PGStream is a no-op rather than a failure.
  }

  @Override
  public InputStream getInputStream() throws IOException {
    if (closed) {
      throw new SocketException("Socket is closed");
    }
    if (inputStream == null) {
      inputStream = new ChannelInputStream();
    }
    return inputStream;
  }

  @Override
  public OutputStream getOutputStream() throws IOException {
    if (closed) {
      throw new SocketException("Socket is closed");
    }
    if (outputStream == null) {
      outputStream = new ChannelOutputStream();
    }
    return outputStream;
  }

  // The buffer-size and timeout accessors are synchronized to match the contract of the
  // java.net.Socket methods they override.

  @Override
  public synchronized void setSoTimeout(int timeout) throws SocketException {
    if (timeout < 0) {
      throw new IllegalArgumentException("timeout can't be negative");
    }
    this.soTimeout = timeout;
  }

  @Override
  public synchronized int getSoTimeout() throws SocketException {
    return soTimeout;
  }

  @Override
  public synchronized void setSendBufferSize(int size) throws SocketException {
    this.sendBufferSize = size;
  }

  @Override
  public synchronized int getSendBufferSize() throws SocketException {
    return sendBufferSize;
  }

  @Override
  public synchronized void setReceiveBufferSize(int size) throws SocketException {
    this.receiveBufferSize = size;
  }

  @Override
  public synchronized int getReceiveBufferSize() throws SocketException {
    return receiveBufferSize;
  }

  // TCP-only options below have no effect on a Unix domain socket, but the values are remembered so
  // the get/set contract behaves like java.net.Socket (PGStream copies them between sockets).

  @Override
  public void setTcpNoDelay(boolean on) throws SocketException {
    this.tcpNoDelay = on;
  }

  @Override
  public boolean getTcpNoDelay() throws SocketException {
    return tcpNoDelay;
  }

  @Override
  public void setKeepAlive(boolean on) throws SocketException {
    this.keepAlive = on;
  }

  @Override
  public boolean getKeepAlive() throws SocketException {
    return keepAlive;
  }

  @Override
  public synchronized void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      readSelector.close();
    } catch (IOException ignore) {
      // ignore
    }
    try {
      writeSelector.close();
    } catch (IOException ignore) {
      // ignore
    }
    channel.close();
  }

  private int readFromChannel(ByteBuffer dst) throws IOException {
    try {
      int n = channel.read(dst);
      if (n != 0) {
        return n;
      }
      while (true) {
        int ready = readSelector.select(soTimeout);
        readSelector.selectedKeys().clear();
        if (ready == 0) {
          // Can only happen when soTimeout > 0 (a zero timeout blocks indefinitely).
          throw new SocketTimeoutException("Read timed out after " + soTimeout + " ms");
        }
        n = channel.read(dst);
        if (n != 0) {
          return n;
        }
      }
    } catch (ClosedSelectorException | ClosedChannelException e) {
      throw closedDuringIo(e);
    }
  }

  private void writeToChannel(ByteBuffer src) throws IOException {
    try {
      while (src.hasRemaining()) {
        int n = channel.write(src);
        if (n == 0) {
          writeSelector.select();
          writeSelector.selectedKeys().clear();
        }
      }
    } catch (ClosedSelectorException | ClosedChannelException e) {
      throw closedDuringIo(e);
    }
  }

  /**
   * Converts a selector or channel closed from another thread (for example {@code
   * Connection.abort()} while a read is blocked) into a {@link SocketException}, so the driver maps
   * it to an {@code SQLException} rather than letting an unchecked exception escape.
   */
  private static SocketException closedDuringIo(Exception cause) {
    SocketException e = new SocketException("Socket closed");
    e.initCause(cause);
    return e;
  }

  // These streams are hand-written rather than obtained from java.nio.channels.Channels because the
  // channel is non-blocking (so SO_TIMEOUT can be honoured through the selectors): Channels streams
  // throw IllegalBlockingModeException on a non-blocking SelectableChannel, and a blocking channel
  // would ignore SO_TIMEOUT, which PGStream relies on (socketTimeout and the setSoTimeout(1) peek in
  // hasMessagePending).
  private class ChannelInputStream extends InputStream {
    private final byte[] single = new byte[1];

    @Override
    public int read() throws IOException {
      int n = read(single, 0, 1);
      return n == -1 ? -1 : single[0] & 0xff;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (len == 0) {
        return 0;
      }
      if (closed) {
        throw new SocketException("Socket is closed");
      }
      return readFromChannel(ByteBuffer.wrap(b, off, len));
    }
  }

  private class ChannelOutputStream extends OutputStream {
    @Override
    public void write(int b) throws IOException {
      write(new byte[]{(byte) b}, 0, 1);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (closed) {
        throw new SocketException("Socket is closed");
      }
      writeToChannel(ByteBuffer.wrap(b, off, len));
    }
  }
}
