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
 *
 * <p>The stream buffers, so the large object trails what the caller has written until the stream
 * flushes. Seeking the large object under an open stream therefore needs a {@link #flush()} first:
 * without one the buffered bytes land at the new position rather than the one they were written
 * for, and the stream ends its writes on the rows of the offset it last saw.</p>
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
   * Large object row size this stream ends its writes on, or {@code 0} when its buffer is too
   * small to carry a remainder to the next write and alignment is not worth pursuing.
   *
   * <p>{@code LOBLKSIZE} is {@code BLCKSZ / 4}, so 2KiB by default. Installations may raise
   * {@code BLCKSZ}, so buffers large enough to afford it aim at 8KiB instead, which covers the
   * largest {@code LOBLKSIZE} a server can have.</p>
   */
  private final int alignment;

  /**
   * Offset within the large object that {@code buf[0]} will be written to, or {@code -1} when the
   * stream does not know it.
   *
   * <p>The stream needs it to end its writes on a large object row boundary, and it is not always
   * zero: {@link java.sql.Blob#setBinaryStream(long)} seeks before handing the stream out. Every
   * write the stream issues moves it by what was sent, so it stays correct without asking again.
   * A {@link #flush()} drops it instead of moving it: a flush sends an amount only the caller
   * knows and is the point a caller may seek from, so what follows one is no longer predictable
   * from here.</p>
   *
   * <p>Not private so that a test can assert it directly. It has to: the buffer size is always a
   * multiple of the alignment, so an offset left behind by exactly one buffer is congruent to the
   * right one, and no assertion about where a write lands can tell the two apart.</p>
   */
  long writePosition = -1;

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
    this.alignment = maxBufferSize >= 8192 ? 8192 : (maxBufferSize >= 2048 ? 2048 : 0);
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

  @Override
  public void write(int b) throws IOException {
    long loId = 0;
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = checkClosed();
      loId = lo.getLongOID();
      byte[] buf = growBuffer(16);
      if (bufferPosition >= buf.length) {
        // Hold back what would land past the last row boundary, exactly as the array path does.
        // Writing the whole buffer would only preserve an alignment the stream already had, and a
        // stream handed out by Blob.setBinaryStream(long) does not start with one.
        long writePosition = alignment == 0 ? 0 : writePosition(lo);
        int tailLength =
            alignment == 0 ? 0 : (int) ((writePosition + bufferPosition) % alignment);
        lo.write(buf, 0, bufferPosition - tailLength);
        if (alignment != 0) {
          this.writePosition = writePosition + bufferPosition - tailLength;
        }
        System.arraycopy(buf, bufferPosition - tailLength, buf, 0, tailLength);
        bufferPosition = tailLength;
      }
      buf[bufferPosition++] = (byte) b;
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not write data to large object {0}, requested write length: {1}",
              loId, 1),
          e);
    }
  }

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
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

      if (totalData >= maxBufferSize) {
        // We want aligned writes, so the write requests chunk nicely into large object rows.
        // The alignment is on the offset within the large object, not on the count of bytes this
        // stream happens to have written, so the remainder is taken from where the data lands.
        // A stream with no alignment to aim at never asks the server where that is.
        long writePosition = alignment == 0 ? 0 : writePosition(lo);
        int tailLength = alignment == 0 ? 0 : (int) ((writePosition + totalData) % alignment);

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
        if (alignment != 0) {
          this.writePosition = writePosition + writeFromBuffer + writeFromB;
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
   * Offset within the large object that the next flushed byte lands on.
   *
   * <p>Asked of the server only when the stream does not already know it, which is once per
   * stream and once more after each {@link #flush()}. A stream that never fills its buffer has
   * nothing to align and never asks at all.</p>
   *
   * @param lo the large object being written to
   * @return the offset {@code buf[0]} will be written to
   * @throws SQLException if a database-access error occurs
   */
  private long writePosition(LargeObject lo) throws SQLException {
    long writePosition = this.writePosition;
    if (writePosition < 0) {
      writePosition = lo.supports64BitOffsets() ? lo.tell64() : lo.tell();
      this.writePosition = writePosition;
    }
    return writePosition;
  }

  /**
   * Flushes this output stream and forces any buffered output bytes to be written out. The general
   * contract of <code>flush</code> is that calling it is an indication that, if any bytes
   * previously written have been buffered by the implementation of the output stream, such bytes
   * should immediately be written to their intended destination.
   *
   * @throws IOException if an I/O error occurs.
   */
  @Override
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
      // The offset was anchored to the bytes just sent, and they are gone. Nothing anchors it now:
      // the server has moved by an amount only this flush knew, and once the buffer refills the
      // stream would carry a value that was never true of the new bytes.
      writePosition = -1;
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not flush large object {0}",
              loId),
          e);
    }
  }

  @Override
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
