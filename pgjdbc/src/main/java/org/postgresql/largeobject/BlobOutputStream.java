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
   * Alignment used by buffers large enough to afford it. {@code LOBLKSIZE} is {@code BLCKSZ / 4},
   * so installations that raise {@code BLCKSZ} store large objects in rows larger than 2KiB.
   */
  private static final int LARGE_ROW_ALIGNMENT = 8192;

  /**
   * Alignment used by smaller buffers, matching the default {@code LOBLKSIZE}.
   */
  private static final int ROW_ALIGNMENT = 2048;

  /**
   * Largest payload this stream puts into one {@code lowrite}. The call passes the bytes as a
   * {@code bytea}, and a varlena cannot exceed {@code MaxAllocSize}, so a larger payload is
   * refused by the server however well formed the message around it is. The headroom covers the
   * function call frame, and the value is a multiple of {@link #LARGE_ROW_ALIGNMENT} so that
   * splitting a large write does not cost the row alignment the write plan aims for.
   */
  static final int MAX_PAYLOAD = (0x3fffffff - 64 * 1024) & -LARGE_ROW_ALIGNMENT;

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
  final @Positive int maxBufferSize;

  /**
   * Largest buffer this stream will hold. The buffer travels as part of one {@code bytea}, so a
   * buffer that does not leave {@link #LARGE_ROW_ALIGNMENT} bytes below {@link #MAX_PAYLOAD} would
   * leave no slice for the caller's bytes to travel with it, and one past {@code MAX_PAYLOAD}
   * could not even be flushed on its own. This value clears that by a wide margin.
   */
  static final int MAX_BUFFER_SIZE = 1 << 29;

  /**
   * Largest slice of a caller's array planned in one go, see {@link #maxSlice(int)}.
   */
  final @Positive int maxSlice;

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
   * @param bufferSize The size of the buffer for single-byte writes. The stream rounds it down
   *        to a power of two, and caps it at {@link #MAX_BUFFER_SIZE}, which is the largest buffer
   *        one write can carry to the server.
   */
  public BlobOutputStream(LargeObject lo, int bufferSize) {
    this(lo, bufferSize, maxSlice(bufferSizeOf(bufferSize)));
  }

  /**
   * Visible for testing, so that the slicing of a large write can be exercised without an array
   * large enough to need it.
   *
   * @param lo LargeObject
   * @param bufferSize The size of the buffer for single-byte writes
   * @param maxSlice Largest slice of a caller's array to plan in one go
   */
  BlobOutputStream(LargeObject lo, int bufferSize, @Positive int maxSlice) {
    this.lo = lo;
    this.maxBufferSize = bufferSizeOf(bufferSize);
    // A zero slice would make write() loop without consuming anything, and a slice above the
    // derived bound would put back the payload this class exists to keep out. The seam is for
    // exercising the loop on small inputs, so it may only lower the bound.
    if (maxSlice <= 0 || maxSlice > maxSlice(maxBufferSize)) {
      throw new IllegalArgumentException(
          "maxSlice must be in [1, " + maxSlice(maxBufferSize) + "], got " + maxSlice);
    }
    this.maxSlice = maxSlice;
  }

  static @Positive int bufferSizeOf(int bufferSize) {
    // Avoid "0" buffer size, and ensure the bufferSize will always be a power of two
    return Math.min(MAX_BUFFER_SIZE, Integer.highestOneBit(Math.max(bufferSize, 1)));
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
    int newSize =
        (int) Math.min(maxBufferSize, (long) Integer.highestOneBit(bufferPosition + extraBytes) * 2);
    byte[] newBuffer = new byte[newSize];
    if (buf != null && bufferPosition != 0) {
      // There was some data in the old buffer, copy it over
      System.arraycopy(buf, 0, newBuffer, 0, bufferPosition);
    }
    this.buf = newBuffer;
    return newBuffer;
  }

  /**
   * Number of trailing bytes to hold back so that the write ends on a large object row boundary.
   *
   * <p>PostgreSQL stores a large object in rows of {@code LOBLKSIZE} bytes, which is
   * {@code BLCKSZ / 4}, so 2KiB by default. A write that ends mid-row makes the server read that
   * row back and update it. Ending on a boundary avoids that, at the cost of carrying the
   * remainder over to the next write. Installations may raise {@code BLCKSZ}, so buffers large
   * enough to afford it align to 8KiB instead.</p>
   *
   * @param totalData bytes available to write, buffered plus incoming
   * @param maxBufferSize largest buffer this stream may hold, which caps what it can carry over
   * @return bytes to leave unwritten, always smaller than the alignment and so within int range
   */
  static int tailLength(long totalData, @Positive int maxBufferSize) {
    if (maxBufferSize >= LARGE_ROW_ALIGNMENT) {
      return (int) (totalData % LARGE_ROW_ALIGNMENT);
    }
    if (maxBufferSize >= ROW_ALIGNMENT) {
      return (int) (totalData % ROW_ALIGNMENT);
    }
    // The buffer cannot carry a remainder worth keeping, so write everything
    return 0;
  }

  /**
   * Bytes to take from the internal buffer for this write. The buffer comes first because its
   * bytes precede the incoming ones in the large object.
   *
   * @param totalData bytes available to write, buffered plus incoming
   * @param bufferPosition bytes currently held in the internal buffer
   * @param tailLength bytes to hold back for alignment, from {@link #tailLength}
   * @return bytes to take from the buffer, never more than it holds
   */
  static int writeFromBuffer(long totalData, int bufferPosition, int tailLength) {
    return (int) Math.min(bufferPosition, totalData - tailLength);
  }

  /**
   * Bytes to take from the caller's array for this write, after the buffer has contributed its
   * share. Zero when the buffer alone covers everything that is not held back.
   *
   * @param totalData bytes available to write, buffered plus incoming
   * @param writeFromBuffer bytes taken from the buffer, from {@link #writeFromBuffer}
   * @param tailLength bytes to hold back for alignment, from {@link #tailLength}
   * @return bytes to take from the caller's array
   */
  static int writeFromB(long totalData, int writeFromBuffer, int tailLength) {
    return (int) Math.max(0, totalData - writeFromBuffer - tailLength);
  }

  /**
   * Largest slice of the caller's array the stream will plan in one go, given what the buffer may
   * add to it. Keeps the payload of a single {@code lowrite} within {@link #MAX_PAYLOAD}.
   *
   * @param maxBufferSize largest buffer this stream may hold
   * @return slice size, a positive multiple of {@link #LARGE_ROW_ALIGNMENT}
   */
  static int maxSlice(@Positive int maxBufferSize) {
    return (MAX_PAYLOAD - maxBufferSize) & -LARGE_ROW_ALIGNMENT;
  }

  @Override
  public void write(int b) throws IOException {
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

  @Override
  public void write(byte[] b, int off, int len) throws IOException {
    // One lowrite carries the bytes as one bytea, which the server refuses beyond MaxAllocSize,
    // so a write larger than that has to go out as several. The slice leaves room for whatever the
    // buffer adds to it, and it is a multiple of the alignment, so splitting costs nothing.
    // The lock is held across the whole loop, not per slice, so that one write call stays one
    // critical section: another thread must not land a write, or a close, between two slices of it.
    try (ResourceLock ignore = lock.obtain()) {
      do {
        int slice = Math.min(len, maxSlice);
        writeSlice(b, off, slice);
        off += slice;
        len -= slice;
      } while (len > 0);
    }
  }

  /**
   * Writes one slice. The caller holds the lock for the whole write, so this does not take it
   * again: taking it here instead would make each slice its own critical section and let another
   * thread write, or close the stream, between two slices of one call.
   */
  private void writeSlice(byte[] b, int off, int len) throws IOException {
    long loId = 0;
    try {
      LargeObject lo = checkClosed();
      loId = lo.getLongOID();
      byte[] buf = this.buf;
      // The total is computed in long: bufferPosition + len wraps for a len near
      // Integer.MAX_VALUE, and a negative total skips the flush below and then asks growBuffer for
      // a buffer of a negative size. The caller slices len so that the total stays within
      // MAX_PAYLOAD, which is what lets every value derived from it fit back into int.
      long totalData = (long) bufferPosition + len;
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
      int tailLength = tailLength(totalData, maxBufferSize);

      if (totalData >= maxBufferSize) {
        // The resulting data won't fit into the buffer, so we flush the data to the database
        int writeFromBuffer = writeFromBuffer(totalData, bufferPosition, tailLength);
        int writeFromB = writeFromB(totalData, writeFromBuffer, tailLength);
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
