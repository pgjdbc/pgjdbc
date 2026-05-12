/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * NIO-based output stream for PostgreSQL protocol writes. Buffers data in a
 * ByteBuffer and writes to the SocketChannel on flush.
 */
public class NIOOutputStream extends OutputStream {

  private final SocketChannel channel;
  private final ByteBuffer buffer;
  private @org.checkerframework.checker.nullness.qual.Nullable Selector writeSelector;

  public NIOOutputStream(SocketChannel channel, int bufferSize) {
    this.channel = channel;
    this.buffer = ByteBuffer.allocate(bufferSize);
  }

  @Override
  public void write(int b) throws IOException {
    if (!buffer.hasRemaining()) {
      flush();
    }
    buffer.put((byte) b);
  }

  @Override
  public void write(byte[] data, int off, int len) throws IOException {
    while (len > 0) {
      int space = buffer.remaining();
      if (space == 0) {
        flush();
        space = buffer.remaining();
      }
      int chunk = Math.min(len, space);
      buffer.put(data, off, chunk);
      off += chunk;
      len -= chunk;
    }
  }

  @Override
  public void flush() throws IOException {
    buffer.flip();
    while (buffer.hasRemaining()) {
      int written = channel.write(buffer);
      if (written == 0) {
        // OS send buffer full — wait for writability via Selector
        waitForWritable();
      }
    }
    buffer.clear();
  }

  private void waitForWritable() throws IOException {
    Selector sel = writeSelector;
    if (sel == null) {
      sel = Selector.open();
      channel.register(sel, SelectionKey.OP_WRITE);
      writeSelector = sel;
    }
    sel.select(); // blocks until channel is writable
    sel.selectedKeys().clear();
  }

  @Override
  public void close() throws IOException {
    flush();
    if (writeSelector != null) {
      writeSelector.close();
    }
  }
}
