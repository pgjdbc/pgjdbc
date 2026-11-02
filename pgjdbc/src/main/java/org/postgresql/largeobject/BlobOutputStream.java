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
   * Write alignment, in bytes, for a stream whose {@link #maxBufferSize} is at least this large.
   * Large objects are stored in rows of {@code LOBLKSIZE}, which is {@code BLCKSZ / 4}. This is the
   * {@code LOBLKSIZE} of the largest {@code BLCKSZ}, 32 KiB, so it is a multiple of every
   * {@code LOBLKSIZE}.
   */
  private static final int LARGE_ROW_ALIGNMENT = 8192;

  /**
   * Write alignment, in bytes, for a buffer of at least this size and smaller than
   * {@link #LARGE_ROW_ALIGNMENT}: the default {@code LOBLKSIZE}. A smaller buffer is not aligned.
   */
  private static final int ROW_ALIGNMENT = 2048;

  /**
   * Largest number of bytes one {@code lowrite} call sends, 64 KiB below 1 GiB ({@value} bytes).
   * PostgreSQL 14 and later close the connection on a function call message longer than
   * {@code PQ_LARGE_MESSAGE_LIMIT}, which is 1 GiB minus two bytes, and earlier versions fail the
   * call with {@code out of memory}. The 64 KiB leave room for the rest of the message.
   */
  static final int MAX_PAYLOAD = (1 << 30) - 64 * 1024;

  /**
   * Largest {@link #maxBufferSize}, 512 MiB ({@value} bytes). The buffer is sent in the same
   * {@code lowrite} as a slice of the caller's array, and this is the largest power of two that
   * leaves room for such a slice within {@link #MAX_PAYLOAD}.
   */
  static final int MAX_BUFFER_SIZE = 1 << 29;

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
   * Size, in bytes, the buffer grows to at most: a power of two no larger than
   * {@link #MAX_BUFFER_SIZE}, {@value #DEFAULT_MAX_BUFFER_SIZE} by default.
   */
  final @Positive int maxBufferSize;

  /**
   * Largest number of bytes from the caller's array that one {@code writeSlice} call takes. The
   * public constructors set it to {@link #maxSlice(int)} of {@link #maxBufferSize}.
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
   * @param bufferSize The largest size, in bytes, the write buffer grows to. The stream rounds it
   *        down to a power of two and limits it to 512 MiB, the largest buffer that one
   *        {@code lowrite} can send together with part of a caller's array. A value below 1 is
   *        taken as 1.
   */
  public BlobOutputStream(LargeObject lo, int bufferSize) {
    this(lo, bufferSize, maxSlice(bufferSizeOf(bufferSize)));
  }

  /**
   * Creates a stream that splits a write into parts of at most {@code maxSlice} bytes, so a test
   * can exercise the splitting with a small array.
   *
   * @param lo LargeObject
   * @param bufferSize as in {@link #BlobOutputStream(LargeObject, int)}
   * @param maxSlice largest number of bytes from a caller's array that one {@code writeSlice}
   *        call takes
   * @throws IllegalArgumentException if {@code maxSlice} is less than 1 or greater than what
   *         {@link #maxSlice(int)} returns for the buffer size
   */
  BlobOutputStream(LargeObject lo, int bufferSize, @Positive int maxSlice) {
    this.lo = lo;
    this.maxBufferSize = bufferSizeOf(bufferSize);
    // write() needs a positive slice to make progress, and a slice above maxSlice(maxBufferSize)
    // can exceed MAX_PAYLOAD, so a test may only lower the bound.
    if (maxSlice <= 0 || maxSlice > maxSlice(maxBufferSize)) {
      throw new IllegalArgumentException(
          "maxSlice must be in [1, " + maxSlice(maxBufferSize) + "], got " + maxSlice);
    }
    this.maxSlice = maxSlice;
  }

  /**
   * Returns the buffer size a stream uses for the requested one: a power of two from 1 to
   * {@link #MAX_BUFFER_SIZE}.
   */
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
    int newSize = Math.min(maxBufferSize, Integer.highestOneBit(bufferPosition + extraBytes) * 2);
    byte[] newBuffer = new byte[newSize];
    if (buf != null && bufferPosition != 0) {
      // There was some data in the old buffer, copy it over
      System.arraycopy(buf, 0, newBuffer, 0, bufferPosition);
    }
    this.buf = newBuffer;
    return newBuffer;
  }

  /**
   * Returns the largest number of bytes from the caller's array that one {@code writeSlice} call
   * may take, so that one {@code lowrite} stays within {@link #MAX_PAYLOAD} even when a full buffer
   * goes out with it.
   *
   * @param maxBufferSize largest size the buffer grows to, at most {@link #MAX_BUFFER_SIZE}
   * @return slice size, positive
   */
  static int maxSlice(@Positive int maxBufferSize) {
    return MAX_PAYLOAD - maxBufferSize;
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
    long loId = 0;
    int requestedLength = len;
    // maxSlice keeps each lowrite within MAX_PAYLOAD. The lock covers the whole loop, so a write or
    // a close from another thread cannot land between two slices of this call.
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = checkClosed();
      loId = lo.getLongOID();
      do {
        int slice = Math.min(len, maxSlice);
        writeSlice(lo, b, off, slice);
        off += slice;
        len -= slice;
      } while (len > 0);
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not write data to large object {0}, requested write length: {1}",
              loId, requestedLength),
          e);
    }
  }

  /**
   * Writes {@code len} bytes, at most {@link #maxSlice}, through the buffer. The caller holds
   * {@link #lock}.
   */
  private void writeSlice(LargeObject lo, byte[] b, int off, int len) throws SQLException {
    byte[] buf = this.buf;
    // len is at most maxSlice, so totalData stays within MAX_PAYLOAD and cannot overflow
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
        maxBufferSize >= LARGE_ROW_ALIGNMENT ? totalData % LARGE_ROW_ALIGNMENT : (
            maxBufferSize >= ROW_ALIGNMENT ? totalData % ROW_ALIGNMENT : 0
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
