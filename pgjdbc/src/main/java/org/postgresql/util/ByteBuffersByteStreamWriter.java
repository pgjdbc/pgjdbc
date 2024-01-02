/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * A {@link ByteStreamWriter} that writes a {@link ByteBuffer java.nio.ByteBuffer} to a byte array
 * parameter.
 */
class ByteBuffersByteStreamWriter implements ByteStreamWriter {

  private final ByteBuffer[] buffers;
  private final int length;

  /**
   * Construct the writer with the given {@link ByteBuffer}
   *
   * @param buffers the buffer to use.
   */
  ByteBuffersByteStreamWriter(ByteBuffer... buffers) {
    this.buffers = buffers;
    int length = 0;
    for (ByteBuffer buffer : buffers) {
      length += buffer.remaining();
    }
    this.length = length;
  }

  @Override
  public int getLength() {
    return length;
  }

  @Override
  public void writeTo(ByteStreamTarget target) throws IOException {
    boolean allArraysAreAccessible = true;
    for (ByteBuffer buffer : buffers) {
      if (!buffer.hasArray()) {
        allArraysAreAccessible = false;
        break;
      }
    }

    OutputStream os = target.getOutputStream();
    if (allArraysAreAccessible) {
      for (ByteBuffer buffer : buffers) {
        os.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
      }
      return;
    }
    // Channels.newChannel does not buffer writes, so we can mix writes to the channel with writes
    // to the OutputStream
    try (WritableByteChannel c = Channels.newChannel(os)) {
      for (ByteBuffer buffer : buffers) {
        if (buffer.hasArray()) {
          os.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        } else {
          c.write(buffer);
        }
      }
    }
  }
}
