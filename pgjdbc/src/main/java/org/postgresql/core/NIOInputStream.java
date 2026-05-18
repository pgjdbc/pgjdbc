/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * NIO-based input stream for PostgreSQL protocol reads. Uses a SocketChannel
 * in non-blocking mode with a Selector for waiting on data.
 *
 * <p>This enables:
 * <ul>
 *   <li>Thread-safe readability checks via {@link #isReadable()} (Selector.selectNow())</li>
 *   <li>Interruptible blocking reads via Selector.select(timeout)</li>
 *   <li>Same API as VisibleBufferedInputStream for drop-in replacement</li>
 * </ul>
 *
 * <p>The channel is always in non-blocking mode. Blocking behavior is achieved
 * by calling Selector.select() before reading.
 */
public class NIOInputStream extends InputStream {

  private final SocketChannel channel;
  private final Selector selector;
  private final SelectionKey selectionKey;
  private final ByteBuffer buffer;
  private int timeoutMillis;

  /**
   * @param channel must be in non-blocking mode and connected
   * @param bufferSize size of the internal read buffer
   */
  public NIOInputStream(SocketChannel channel, int bufferSize) throws IOException {
    if (channel.isBlocking()) {
      throw new IllegalArgumentException("Channel must be in non-blocking mode");
    }
    this.channel = channel;
    this.selector = Selector.open();
    this.selectionKey = channel.register(selector, SelectionKey.OP_READ);
    this.buffer = ByteBuffer.allocate(bufferSize);
    this.buffer.flip(); // start with empty readable buffer
  }

  /**
   * Check if data is available without blocking or consuming bytes.
   * NOTE: Only safe to call from the same thread that calls ensureBytes().
   * For cross-thread readability checks, use a separate Selector on getChannel().
   */
  public boolean isReadable() throws IOException {
    if (buffer.hasRemaining()) {
      return true;
    }
    int ready = selector.selectNow();
    selector.selectedKeys().clear();
    return ready > 0;
  }

  /**
   * Block until data is available or timeout expires.
   * NOTE: Only safe to call from the same thread that calls ensureBytes().
   * For cross-thread waiting, use a separate Selector on getChannel().
   *
   * @param timeoutMillis max time to wait (0 = indefinite)
   * @return true if data is available
   */
  public boolean waitForData(long timeoutMillis) throws IOException {
    if (buffer.hasRemaining()) {
      return true;
    }
    int ready = selector.select(timeoutMillis);
    selector.selectedKeys().clear();
    return ready > 0;
  }

  /**
   * Wake up any thread blocked in a select() call.
   * Thread-safe — can be called from any thread.
   */
  public void wakeup() {
    selector.wakeup();
  }

  public void setTimeoutRequested(boolean timeoutRequested) {
    // Timeout is managed via select(timeout) — this is a compatibility shim
  }

  public void setTimeout(int millis) {
    this.timeoutMillis = millis;
  }

  public int getTimeout() {
    return timeoutMillis;
  }

  // --- VisibleBufferedInputStream compatible API ---

  @Override
  public int read() throws IOException {
    ensureBytes(1);
    return buffer.get() & 0xFF;
  }

  @Override
  public int read(byte[] to, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }
    ensureBytes(1); // at least 1 byte available
    int available = Math.min(len, buffer.remaining());
    buffer.get(to, off, available);
    return available;
  }

  public int readInt2() throws IOException {
    ensureBytes(2);
    return ((buffer.get() & 0xFF) << 8) | (buffer.get() & 0xFF);
  }

  public int readInt4() throws IOException {
    ensureBytes(4);
    return ((buffer.get() & 0xFF) << 24)
        | ((buffer.get() & 0xFF) << 16)
        | ((buffer.get() & 0xFF) << 8)
        | (buffer.get() & 0xFF);
  }

  /**
   * Peek at the next byte without consuming it.
   * @return the next byte, or -1 if no data available after timeout
   */
  public int peek() throws IOException {
    if (!ensureBytes(1, false)) {
      return -1;
    }
    return buffer.get(buffer.position()) & 0xFF;
  }

  public byte readRaw() {
    return buffer.get();
  }

  /**
   * Ensure at least n bytes are available in the buffer. Blocks until available.
   */
  public boolean ensureBytes(int n) throws IOException {
    return ensureBytes(n, true);
  }

  /**
   * Ensure at least n bytes are available in the buffer.
   * @param block if false, return false instead of throwing on timeout
   */
  public boolean ensureBytes(int n, boolean block) throws IOException {
    while (buffer.remaining() < n) {
      // Compact: move unread data to start of buffer, prepare for writing
      buffer.compact();

      // Wait for data
      long timeout = timeoutMillis > 0 ? timeoutMillis : (block ? 0 : 1);
      int ready = selector.select(timeout == 0 ? 0 : timeout);
      selector.selectedKeys().clear();

      if (ready == 0) {
        buffer.flip(); // restore read mode
        if (!block) {
          return false;
        }
        if (timeoutMillis > 0) {
          throw new SocketTimeoutException("Read timed out");
        }
        // timeout==0 means infinite wait, but select(0) means infinite too
        continue;
      }

      // Read from channel
      int bytesRead = channel.read(buffer);
      buffer.flip(); // switch back to read mode

      if (bytesRead == -1) {
        throw new IOException("Connection closed by server");
      }
    }
    return true;
  }

  @Override
  public int available() throws IOException {
    return buffer.remaining();
  }

  @Override
  public long skip(long n) throws IOException {
    long skipped = 0;
    while (skipped < n) {
      ensureBytes(1);
      int toSkip = (int) Math.min(n - skipped, buffer.remaining());
      buffer.position(buffer.position() + toSkip);
      skipped += toSkip;
    }
    return skipped;
  }

  public int getIndex() {
    return buffer.position();
  }

  public byte[] getBuffer() {
    return buffer.array();
  }

  /**
   * Scan for the length of a C-style null-terminated string starting at current position.
   * Returns the number of bytes before the null terminator (not including it).
   */
  public int scanCStringLength() throws IOException {
    int scanned = 0;
    while (true) {
      for (int i = buffer.position() + scanned; i < buffer.limit(); i++) {
        if (buffer.get(i) == 0) {
          return i - buffer.position();
        }
      }
      scanned = buffer.remaining();
      // Need more data — ensure we have at least one more byte than currently buffered
      ensureBytes(scanned + 1);
    }
  }

  /**
   * Returns the underlying channel for creating separate Selectors (e.g., NotificationPoller).
   */
  public SocketChannel getChannel() {
    return channel;
  }

  @Override
  public void close() throws IOException {
    selectionKey.cancel();
    selector.close();
  }
}
