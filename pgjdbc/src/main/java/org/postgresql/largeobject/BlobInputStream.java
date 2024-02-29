/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;

/**
 * This is an implementation of an InputStream from a large object.
 */
public class BlobInputStream extends InputStream {
  static final int DEFAULT_MAX_BUFFER_SIZE = 512 * 1024;
  static final int INITIAL_BUFFER_SIZE = 64 * 1024;

  /**
   * The parent LargeObject.
   */
  private @Nullable LargeObject lo;
  private final ResourceLock lock = new ResourceLock();

  /**
   * The absolute position.
   */
  private long absolutePosition;

  /**
   * Buffer used to improve performance.
   */
  private byte @Nullable [] buffer;

  /**
   * Position within buffer.
   */
  private int bufferPosition;

  /**
   * The amount of bytes to read on the next read.
   * Currently, we nullify {@link #buffer}, so we can't use {@code buffer.length}.
   */
  private int lastBufferSize;

  /**
   * The buffer size.
   */
  private final int maxBufferSize;

  /**
   * The mark position.
   */
  private long markPosition;

  /**
   * The limit.
   */
  private final long limit;

  /**
   * @param lo LargeObject to read from
   */
  public BlobInputStream(LargeObject lo) {
    this(lo, DEFAULT_MAX_BUFFER_SIZE);
  }

  /**
   * @param lo LargeObject to read from
   * @param bsize buffer size
   */

  public BlobInputStream(LargeObject lo, int bsize) {
    this(lo, bsize, -1);
  }

  /**
   * @param lo LargeObject to read from
   * @param bsize buffer size
   * @param limit max number of bytes to read
   */
  public BlobInputStream(LargeObject lo, int bsize, long limit) {
    this.lo = lo;
    this.maxBufferSize = bsize;
    // The very first read multiplies the last buffer size by two, so we divide by two to get
    // the first read to be exactly the initial buffer size
    this.lastBufferSize = INITIAL_BUFFER_SIZE / 2;

    try {
      // initialise current position for mark/reset
      this.absolutePosition = lo.tell64();
    } catch (SQLException e1) {
      try {
        // the tell64 function does not exist before PostgreSQL 9.3
        this.absolutePosition = lo.tell();
      } catch (SQLException e2) {
        RuntimeException e3 = new RuntimeException("Failed to create BlobInputStream", e1);
        e3.addSuppressed(e2);
        throw e3;
      }
    }

    // Treat -1 as no limit for backward compatibility
    this.limit = limit == -1 ? Long.MAX_VALUE : limit + this.absolutePosition;
    this.markPosition = this.absolutePosition;
  }

  /**
   * The minimum required to implement input stream.
   */
  @Override
  public int read() throws IOException {
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = getLo();
      if (absolutePosition >= limit) {
        buffer = null;
        bufferPosition = 0;
        return -1;
      }
      // read more in if necessary
      if (buffer == null || bufferPosition >= buffer.length) {
        // Don't hold the buffer while waiting for DB to respond
        // Note: lo.read(...) does not support "fetching the response into the user-provided buffer"
        // See https://github.com/pgjdbc/pgjdbc/issues/3043
        int nextBufferSize = getNextBufferSize(1);
        buffer = lo.read(nextBufferSize);
        bufferPosition = 0;

        if (buffer.length == 0) {
          // The lob does not produce any more data, so we are at the end of the stream
          return -1;
        }
      }

      int ret = buffer[bufferPosition] & 0xFF;

      bufferPosition++;
      absolutePosition++;
      if (bufferPosition >= buffer.length) {
        // TODO: support buffer reuse in mark/reset
        buffer = null;
        bufferPosition = 0;
      }

      return ret;
    } catch (SQLException e) {
      long loId = lo == null ? -1 : lo.getLongOID();
      throw new IOException(
          GT.tr("Can not read data from large object {0}, position: {1}, buffer size: {2}",
              loId, absolutePosition, lastBufferSize),
          e);
    }
  }

  /**
   * Computes the next buffer size to use for reading data from the large object.
   * The idea is to avoid allocating too much memory, especially if the user will use just a few
   * bytes of the data.
   * @param len estimated read request
   * @return next buffer size or {@link #maxBufferSize} if the buffer should not be increased
   */
  private int getNextBufferSize(int len) {
    int nextBufferSize = Math.min(maxBufferSize, this.lastBufferSize * 2);
    if (len > nextBufferSize) {
      nextBufferSize = Math.min(maxBufferSize, Integer.highestOneBit(len * 2));
    }
    this.lastBufferSize = nextBufferSize;
    return nextBufferSize;
  }

  @Override
  public int read(byte[] dest, int off, int len) throws IOException {
    if (len == 0) {
      return 0;
    }
    try (ResourceLock ignore = lock.obtain()) {
      int bytesCopied = 0;
      LargeObject lo = getLo();

      // Check to make sure we aren't at the limit.
      if (absolutePosition >= limit) {
        return -1;
      }

      // Check to make sure we are not going to read past the limit
      len = Math.min(len, (int) Math.min(limit - absolutePosition, Integer.MAX_VALUE));

      // have we read anything into the buffer
      if (buffer != null) {
        // now figure out how much data is in the buffer
        int bytesInBuffer = buffer.length - bufferPosition;
        // figure out how many bytes the user wants
        int bytesToCopy = Math.min(len, bytesInBuffer);
        // copy them in
        System.arraycopy(buffer, bufferPosition, dest, off, bytesToCopy);
        // move the buffer position
        bufferPosition += bytesToCopy;
        if (bufferPosition >= buffer.length) {
          // TODO: support buffer reuse in mark/reset
          buffer = null;
          bufferPosition = 0;
        }
        // position in the blob
        absolutePosition += bytesToCopy;
        // increment offset
        off += bytesToCopy;
        // decrement the length
        len -= bytesToCopy;
        bytesCopied = bytesToCopy;
      }

      if (len > 0) {
        int nextBufferSize = getNextBufferSize(len);
        // We are going to read data past the existing buffer, so we release the memory
        // before making a DB call
        buffer = null;
        bufferPosition = 0;
        int bytesRead;
        try {
          if (len >= nextBufferSize) {
            // Read directly into the user's buffer
            bytesRead = lo.read(dest, off, len);
          } else {
            // Refill the buffer and copy from it
            buffer = lo.read(nextBufferSize);
            // Note that actual number of bytes read may be less than requested
            bytesRead = Math.min(len, buffer.length);
            System.arraycopy(buffer, 0, dest, off, bytesRead);
            // If we at the end of the stream, and we just copied the last bytes,
            // we can release the buffer
            if (bytesRead == buffer.length) {
              // TODO: if we want to reuse the buffer in mark/reset we should not release the
              //  buffer here
              buffer = null;
              bufferPosition = 0;
            } else {
              bufferPosition = bytesRead;
            }
          }
        } catch (SQLException ex) {
          throw new IOException(
              GT.tr("Can not read data from large object {0}, position: {1}, buffer size: {2}",
                  lo.getLongOID(), absolutePosition, len),
              ex);
        }
        bytesCopied += bytesRead;
        absolutePosition += bytesRead;
      }
      return bytesCopied == 0 ? -1 : bytesCopied;
    }
  }

  /**
   * <p>Closes this input stream and releases any system resources associated with the stream.</p>
   *
   * <p>The <code>close</code> method of <code>InputStream</code> does nothing.</p>
   *
   * @throws IOException if an I/O error occurs.
   */
  @Override
  public void close() throws IOException {
    long loId = 0;
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = this.lo;
      if (lo != null) {
        loId = lo.getLongOID();
        lo.close();
      }
      this.lo = null;
    } catch (SQLException e) {
      throw new IOException(
          GT.tr("Can not close large object {0}",
              loId),
          e);
    }
  }

  /**
   * <p>Marks the current position in this input stream. A subsequent call to the <code>reset</code>
   * method repositions this stream at the last marked position so that subsequent reads re-read the
   * same bytes.</p>
   *
   * <p>The <code>readlimit</code> arguments tells this input stream to allow that many bytes to be
   * read before the mark position gets invalidated.</p>
   *
   * <p>The general contract of <code>mark</code> is that, if the method <code>markSupported</code>
   * returns <code>true</code>, the stream somehow remembers all the bytes read after the call to
   * <code>mark</code> and stands ready to supply those same bytes again if and whenever the method
   * <code>reset</code> is called. However, the stream is not required to remember any data at all
   * if more than <code>readlimit</code> bytes are read from the stream before <code>reset</code> is
   * called.</p>
   *
   * <p>Marking a closed stream should not have any effect on the stream.</p>
   *
   * @param readlimit the maximum limit of bytes that can be read before the mark position becomes
   *        invalid.
   * @see java.io.InputStream#reset()
   */
  @Override
  public void mark(int readlimit) {
    try (ResourceLock ignore = lock.obtain()) {
      markPosition = absolutePosition;
    }
  }

  /**
   * Repositions this stream to the position at the time the <code>mark</code> method was last
   * called on this input stream. NB: If mark is not called we move to the beginning.
   *
   * @see java.io.InputStream#mark(int)
   * @see java.io.IOException
   */
  @Override
  public void reset() throws IOException {
    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = getLo();
      long loId = lo.getLongOID();
      try {
        if (markPosition <= Integer.MAX_VALUE) {
          lo.seek((int) markPosition);
        } else {
          lo.seek64(markPosition, LargeObject.SEEK_SET);
        }
        buffer = null;
        absolutePosition = markPosition;
      } catch (SQLException e) {
        throw new IOException(
            GT.tr("Can not reset stream for large object {0} to position {1}",
                loId, markPosition),
            e);
      }
    }
  }

  /**
   * Tests if this input stream supports the <code>mark</code> and <code>reset</code> methods. The
   * <code>markSupported</code> method of <code>InputStream</code> returns <code>false</code>.
   *
   * @return <code>true</code> if this true type supports the mark and reset method;
   *         <code>false</code> otherwise.
   * @see java.io.InputStream#mark(int)
   * @see java.io.InputStream#reset()
   */
  @Override
  public boolean markSupported() {
    return true;
  }

  private LargeObject getLo() throws IOException {
    if (lo == null) {
      throw new IOException("BlobOutputStream is closed");
    }
    return lo;
  }
}
