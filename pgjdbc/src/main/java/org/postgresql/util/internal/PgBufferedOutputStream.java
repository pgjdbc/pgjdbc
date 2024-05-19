/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;

import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

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

  /**
   * Writes the given amount of bytes from an input stream to this buffered stream.
   * @param inStream input data
   * @param remaining the number of bytes to transfer
   * @throws IOException in case writing to the output stream fails
   * @throws SourceStreamIOException in case reading from the source stream fails
   */
  public void write(InputStream inStream, int remaining) throws IOException {
    int expectedLength = remaining;
    byte[] buf = this.buf;

    while (remaining > 0) {
      int readSize = Math.min(remaining, buf.length - count);
      int readCount;

      try {
        readCount = inStream.read(buf, count, readSize);
      } catch (IOException e) {
        throw new SourceStreamIOException(remaining, e);
      }

      if (readCount < 0) {
        throw new SourceStreamIOException(
            remaining,
            new EOFException(
                GT.tr("Premature end of input stream, expected {0} bytes, but only read {1}.",
                    expectedLength, expectedLength - remaining)));
      }

      count += readCount;
      remaining -= readCount;
      if (count == buf.length) {
        flushBuffer();
      }
    }
  }

  /**
   * Writes the required number of zero bytes to the output stream.
   * @param len number of bytes to write
   * @throws IOException in case writing to the underlying stream fails
   */
  public void writeZeros(int len) throws IOException {
    int startPos = count;
    if (count > 0) {
      int avail = buf.length - count;
      int prefixLength = Math.min(len, avail);
      Arrays.fill(buf, count, count + prefixLength, (byte) 0);
      count += prefixLength;
      len -= prefixLength;
      if (count == buf.length) {
        flushBuffer();
      }
      if (len == 0) {
        return;
      }
    }
    // The buffer is empty at this point, and startPos..buf.length is filled with zeros
    // So fill the beginning with zeros as well.
    Arrays.fill(buf, 0, Math.min(startPos, len), (byte) 0);

    while (len >= buf.length) {
      // Pretend we have the full buffer
      count = buf.length;
      flushBuffer();
      len -= buf.length;
    }
    // Pretend we have the remaining zeros in the buffer.
    count = len;
  }
}
