/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.util.ByteConverter;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Buffered output stream. The key difference from {@link java.io.BufferedOutputStream} is that
 * {@code PgBufferedOutputStream} does not perform synchronization.
 * This is an internal class, and it is not meant to be used as a public API.
 */
public class PgBufferedOutputStream extends FilterOutputStream {
  /**
   * Buffer for the data
   */
  protected final byte[] buf;

  /**
   * Number of bytes stored in the buffer
   */
  protected int count;

  public PgBufferedOutputStream(OutputStream out, int bufferSize) {
    super(out);
    buf = new byte[bufferSize];
  }

  protected void flushBuffer() throws IOException {
    if (count > 0) {
      out.write(buf, 0, count);
      count = 0;
    }
  }

  @Override
  public void flush() throws IOException {
    flushBuffer();
    super.flush();
  }

  public void writeInt2(int val) throws IOException {
    byte[] buf = this.buf;
    if (buf.length - count < 2) {
      flushBuffer();
    }
    int count = this.count;
    ByteConverter.int2(buf, count, val);
    this.count = count + 2;
  }

  public void writeInt4(int val) throws IOException {
    byte[] buf = this.buf;
    if (buf.length - count < 4) {
      flushBuffer();
    }
    int count = this.count;
    ByteConverter.int4(buf, count, val);
    this.count = count + 4;
  }

  @Override
  public void write(int b) throws IOException {
    if (count >= buf.length) {
      flushBuffer();
    }
    buf[count++] = (byte) b;
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    if (len == 0) {
      return;
    }

    if (count > 0) {
      int avail = buf.length - count;
      if (avail + buf.length <= len) {
        // We have data in the buffer, however, even if we copy as much as it is possible
        // the leftover will exceed buffer size, so we issue two write calls
        // Sample test to trigger the branch:
        //   BatchedInsertReWriteEnabledTest.test32767Binds
        flushBuffer();
        out.write(b, off, len);
        return;
      }
      int prefixLength = Math.min(len, avail);
      System.arraycopy(b, off, buf, count, prefixLength);
      count += prefixLength;
      off += prefixLength;
      len -= prefixLength;
      if (count == buf.length) {
        flushBuffer();
      }
      if (len == 0) {
        return;
      }
    }

    // At this point, the buffer is empty
    if (len >= buf.length) {
      // Write big chunk
      // Sample tests to trigger the branch:
      //   LargeObjectManagerTest.objectWriteThenRead,
      //   BlobTransactionTest.concurrentReplace
      out.write(b, off, len);
      return;
    }
    // Buffer small one
    System.arraycopy(b, off, buf, 0, len);
    count = len;
  }
}
