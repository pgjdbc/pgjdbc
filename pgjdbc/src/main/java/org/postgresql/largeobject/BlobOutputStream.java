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
 * Output stream that writes to a {@link LargeObject} through a buffer.
 *
 * <p>A caller that seeks, reads, or writes the large object directly while the stream is open
 * must call {@link #flush()} first. Bytes still buffered at that point are written at the new
 * position, and a stream that has already read the large object offset keeps aligning its writes
 * to the old position.</p>
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
   * Step, in bytes, of the large object offsets the stream ends its writes at, or {@code 0} when
   * the stream does not align.
   *
   * <p>The server stores a large object in rows of {@code LOBLKSIZE} bytes, which is
   * {@code BLCKSZ / 4}: 2KiB by default and 8KiB at the largest {@code BLCKSZ}, and 8KiB is a
   * multiple of every row size. The remainder the stream holds back is shorter than the alignment
   * and has to fit in the buffer, so a buffer of at least 8KiB aligns on 8KiB, a buffer of at least
   * 2KiB aligns on 2KiB, and a smaller one does not align.</p>
   */
  private final int alignment;

  /**
   * Large object offset that {@code buf[0]} is written to, or {@code -1} when the stream has to
   * read it from the server before its next aligned write.
   *
   * <p>The large object may be at any offset when the stream is created
   * ({@link java.sql.Blob#setBinaryStream(long)} seeks before it returns one), so the stream reads
   * the offset before its first aligned write. Each aligned write then advances it by the bytes
   * sent. {@link #flush()} resets it to {@code -1}, because a caller may seek the large object
   * after a flush.</p>
   */
  private long writePosition = -1;

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
   * @param bufferSize the largest buffer size, rounded down to a power of two. A buffer of at
   *     least 2048 bytes also makes the stream end its writes on large object row boundaries
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
        // Hold back the bytes past the last multiple of alignment, as write(byte[], int, int)
        // does. buf[0] may sit off a row boundary, at the start or after a flush, and writing
        // whole buffers would keep every write there.
        long startOffset = alignment == 0 ? 0 : currentWritePosition(lo);
        int tailLength =
            alignment == 0 ? 0 : (int) ((startOffset + bufferPosition) % alignment);
        lo.write(buf, 0, bufferPosition - tailLength);
        if (alignment != 0) {
          writePosition = startOffset + bufferPosition - tailLength;
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
      // Otherwise we write them, but end the write on a multiple of alignment: a write that ends
      // inside a large object row makes the server read that row back and update it.
      //
      //  | buf[0] ... buf[bufferPosition] | b[off] ... b[off + len] |
      //  |<----------------- totalData ---------------------------->|
      // If the large object offset of buf[0] plus totalData is not a multiple of alignment, we
      // copy the remainder to the beginning of the buffer and write it later.
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
        // buf[0] need not sit on a row boundary: the stream may start anywhere, and a flush sends
        // whatever the buffer held. The remainder is taken from the large object offset.
        long startOffset = alignment == 0 ? 0 : currentWritePosition(lo);
        int tailLength = alignment == 0 ? 0 : (int) ((startOffset + totalData) % alignment);

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
          writePosition = startOffset + writeFromBuffer + writeFromB;
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
   * Returns {@link #writePosition}, reading it from the server when it is {@code -1}.
   *
   * @param lo the large object being written to
   * @return the large object offset that {@code buf[0]} is written to
   * @throws SQLException if the server cannot report the offset
   */
  private long currentWritePosition(LargeObject lo) throws SQLException {
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
