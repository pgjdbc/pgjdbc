/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;

import org.checkerframework.checker.index.qual.Positive;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.sql.SQLException;

/**
 * This implements a basic output stream that writes to a LargeObject.
 */
public class BlobOutputStream extends OutputStream {
  static final int DEFAULT_MAX_BUFFER_SIZE = 512 * 1024;

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
  private final @Positive int maxBufferSize;

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
    this(lo, DEFAULT_MAX_BUFFER_SIZE);
  }

  /**
   * Create an OutputStream to a large object.
   *
   * @param lo LargeObject
   * @param bufferSize The size of the buffer for single-byte writes
   */
  public BlobOutputStream(LargeObject lo, int bufferSize) {
    this.lo = lo;
    // Avoid "0" buffer size, and ensure the bufferSize will always be a power of two
    this.maxBufferSize = Integer.highestOneBit(Math.max(bufferSize, 1));
  }

  /**
   * Grows an internal buffer to ensure the extra bytes fit in the buffer.
   * @param extraBytes the number of extra bytes that should fit in the buffer
   * @return new buffer
   */
  private byte[] growBuffer(int extraBytes) {
    byte[] buf = this.buf;
    if (buf != null && (buf.length == maxBufferSize || buf.length - bufferPosition >= extraBytes)) {
      // Buffer is already large enough
      return buf;
    }
    // We use power-of-two buffers, so they align nicely with PostgreSQL's LargeObject slicing
    // By default PostgreSQL slices the data in 2KiB chunks
    int newSize = Math.min(maxBufferSize, Integer.highestOneBit(bufferPosition + extraBytes) * 2);
    byte[] newBuffer = new byte[newSize];
    if (buf != null && bufferPosition != 0) {
      // There was some data in the old buffer, copy it over
      System.arraycopy(buf, 0, newBuffer, 0, bufferPosition);
    }
    this.buf = newBuffer;
    return newBuffer;
  }

  public void write(int b) throws java.io.IOException {
    long loId = 0;
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = checkClosed();
      loId = lo.getLongOID();
      byte[] buf = growBuffer(16);
      if (bufferPosition >= buf.length) {
        lo.write(buf);
        bufferPosition = 0;
      }
      buf[bufferPosition++] = (byte) b;
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not write data to large object {0}, requested write length: {1}",
              loId, 1),
          e);
    }
  }

  public void write(byte[] b, int off, int len) throws java.io.IOException {
    long loId = 0;
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = checkClosed();
      loId = lo.getLongOID();
      byte[] buf = this.buf;
      int totalData = bufferPosition + len;
      // We have two parts of the data (it goes sequentially):
      // 1) Data in buf at positions [0, bufferPosition)
      // 2) Data in b at positions [off, off + len)
      // If the new data fits into the buffer, we just copy it there.
      // Otherwise, it might sound nice idea to just write them to the database, unfortunately,
      // it is not optimal, as PostgreSQL chunks LargeObjects into 2KiB rows.
      // That is why we would like to avoid writing a part of 2KiB chunk, and then issue overwrite
      // causing DB to load and update the row.
      //
      // In fact, LOBLKSIZE is BLCKSZ/4, so users might have different values, so we use
      // 8KiB write alignment for larger buffer sizes just in case.
      //
      //  | buf[0] ... buf[bufferPosition] | b[off] ... b[off + len] |
      //  |<----------------- totalData ---------------------------->|
      // If the total data does not align with 2048, we might have some remainder that we will
      // copy to the beginning of the buffer and write later.
      // The remainder can fall into either b (e.g. if the requested len is big enough):
      //
      //  | buf[0] ... buf[bufferPosition] | b[off] ........ b[off + len] |
      //  |<----------------- totalData --------------------------------->|
      //  |<-------writeFromBuf----------->|<-writeFromB->|<--tailLength->|
      //
      // or
      // buf (e.g. if the requested write len is small yet it does not fit into the max buffer size):
      //  | buf[0] .................... buf[bufferPosition] | b[off] .. b[off + len] |
      //  |<----------------- totalData -------------------------------------------->|
      //  |<-------writeFromBuf---------------->|<--------tailLength---------------->|
      // "writeFromB" will be zero in that case

      // We want aligned writes, so the write requests chunk nicely into large object rows
      int tailLength =
          maxBufferSize >= 8192 ? totalData % 8192 : (
              maxBufferSize >= 2048 ? totalData % 2048 : 0
          );

      if (totalData >= maxBufferSize) {
        // The resulting data won't fit into the buffer, so we flush the data to the database
        int writeFromBuffer = Math.min(bufferPosition, totalData - tailLength);
        int writeFromB = Math.max(0, totalData - writeFromBuffer - tailLength);
        if (buf == null || bufferPosition <= 0) {
          // The buffer is empty, so we can write the data directly
          lo.write(b, off, writeFromB);
        } else {
          if (writeFromB == 0) {
            lo.write(buf, 0, writeFromBuffer);
          } else {
            lo.write(
                ByteStreamWriter.of(
                    ByteBuffer.wrap(buf, 0, writeFromBuffer),
                    ByteBuffer.wrap(b, off, writeFromB)));
          }
          // There might be some data left in the buffer since we keep the tail
          if (writeFromBuffer >= bufferPosition) {
            // The buffer was fully written to the database
            bufferPosition = 0;
          } else {
            // Copy the rest to the beginning
            System.arraycopy(buf, writeFromBuffer, buf, 0, bufferPosition - writeFromBuffer);
            bufferPosition -= writeFromBuffer;
          }
        }
        len -= writeFromB;
        off += writeFromB;
      }
      if (len > 0) {
        buf = growBuffer(len);
        System.arraycopy(b, off, buf, bufferPosition, len);
        bufferPosition += len;
      }
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not write data to large object {0}, requested write length: {1}",
              loId, len),
          e);
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
    long loId = 0;
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = checkClosed();
      loId = lo.getLongOID();
      byte[] buf = this.buf;
      if (buf != null && bufferPosition > 0) {
        lo.write(buf, 0, bufferPosition);
      }
      bufferPosition = 0;
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not flush large object {0}",
              loId),
          e);
    }
  }

  public void close() throws IOException {
    long loId = 0;
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = this.lo;
      if (lo != null) {
        loId = lo.getLongOID();
        flush();
        lo.close();
        this.lo = null;
      }
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not close large object {0}",
              loId),
          e);
    }
  }

  private LargeObject checkClosed() throws IOException {
    if (lo == null) {
      throw new IOException("BlobOutputStream is closed");
    }
    return lo;
  }
}
