/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;

/**
 * A {@link ByteStreamWriter} that writes a {@link ByteBuffer java.nio.ByteBuffer} to a byte array
 * parameter.
 */
public class ByteBufferByteStreamWriter implements ByteStreamWriter {

  private final ByteBuffer buf;
  private final int length;

  /**
   * Construct the writer with the given {@link ByteBuffer}
   *
   * @param buf the buffer to use.
   */
  public ByteBufferByteStreamWriter(ByteBuffer buf) {
    this.buf = buf;
    this.length = buf.remaining();
  }

  @Override
  public int getLength() {
    return length;
  }

  @Override
  public void writeTo(ByteStreamTarget target) throws IOException {
    // this _does_ involve some copying to a temporary buffer, but that's unavoidable
    // as OutputStream itself only accepts single bytes or heap allocated byte arrays
    WritableByteChannel c = Channels.newChannel(target.getOutputStream());
    try {
      c.write(buf);
    } finally {
      c.close();
    }
  }
}
