/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import org.postgresql.jdbc.ResourceLock;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;

/**
 * This implements a basic output stream that writes to a LargeObject.
 */
public class BlobOutputStream extends OutputStream {
  /**
   * The parent LargeObject.
   */
  private @Nullable LargeObject lo;
  private final ResourceLock lock = new ResourceLock();

  /**
   * Buffer.
   */
  private byte @Nullable [] buf;

  /**
   * Size of the buffer (default 1K).
   */
  private final int bufferSize;

  /**
   * Position within the buffer.
   */
  private int bufferPosition;

  /**
   * Create an OutputStream to a large object.
   *
   * @param lo LargeObject
   */
  public BlobOutputStream(LargeObject lo) {
    this(lo, 1024);
  }

  /**
   * Create an OutputStream to a large object.
   *
   * @param lo LargeObject
   * @param bufferSize The size of the buffer for single-byte writes
   */
  public BlobOutputStream(LargeObject lo, int bufferSize) {
    this.lo = lo;
    this.bufferSize = bufferSize;
  }

  public void write(int b) throws java.io.IOException {
    LargeObject lo = checkClosed();
    try (ResourceLock ignore = lock.obtain()) {
      byte[] buf = this.buf;
      if (buf == null) {
        // Allocate the buffer on the first use
        this.buf = buf = new byte[bufferSize];
      }
      buf[bufferPosition++] = (byte) b;
      if (bufferPosition >= bufferSize) {
        lo.write(buf);
        bufferPosition = 0;
      }
    } catch (SQLException se) {
      throw new IOException(se.toString());
    }
  }

  public void write(byte[] b, int off, int len) throws java.io.IOException {
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = checkClosed();
      byte[] buf = this.buf;
      // Initialize the buffer if needed
      // Suppose we have bufferPosition=10KiB buffered out of bufferSize=64KiB, and the user issues
      // 100KiB write. We will copy 54KiB to the buffer and flush it, then we will copy
      // the remaining 46KiB to the buffer without flushing to the DB. It avoids small writes
      // hitting the database.
      // If the incoming request is 300KiB, then we copy 54KiB to the buffer and flush it.
      // Then we write the remaining 246KiB directly to the database.
      // At worst, it results in two DB calls
      // If the buffer was not there, we do not create one if the write request is big enough
      if (buf == null && len < 2 * bufferSize) {
        this.buf = buf = new byte[bufferSize];
      }
      if (buf != null && bufferPosition < bufferSize) {
        // Copy the part of the user-provided data that fits in the buffer
        int avail = Math.min(bufferSize - bufferPosition, len);
        System.arraycopy(b, off, buf, bufferPosition, avail);
        bufferPosition += avail;
        len -= avail;
        off += avail;
      }
      if (len == 0) {
        return;
      }
      // TODO: ideally, we should be able to use lo.write(buffer1, buffer2) to avoid copying
      flush();
      if (buf == null || len >= bufferSize) {
        // The remaining data exceeds the buffer, so we can write it directly to the database
        lo.write(b, off, len);
      } else {
        // Buffer the remaining data
        System.arraycopy(b, off, buf, bufferPosition, len);
        bufferPosition += len;
      }
    } catch (SQLException se) {
      throw new IOException(se.toString());
    }
  }

  /**
   * Flushes this output stream and forces any buffered output bytes to be written out. The general
   * contract of <code>flush</code> is that calling it is an indication that, if any bytes
   * previously written have been buffered by the implementation of the output stream, such bytes
   * should immediately be written to their intended destination.
   *
   * @throws IOException if an I/O error occurs.
   */
  public void flush() throws IOException {
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = checkClosed();
      byte[] buf = this.buf;
      if (buf != null && bufferPosition > 0) {
        lo.write(buf, 0, bufferPosition);
      }
      bufferPosition = 0;
    } catch (SQLException se) {
      throw new IOException(se.toString());
    }
  }

  public void close() throws IOException {
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = this.lo;
      if (lo != null) {
        flush();
        lo.close();
        this.lo = null;
      }
    } catch (SQLException se) {
      throw new IOException(se.toString());
    }
  }

  private LargeObject checkClosed() throws IOException {
    if (lo == null) {
      throw new IOException("BlobOutputStream is closed");
    }
    return lo;
  }
}
