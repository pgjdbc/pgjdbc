/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is an implementation of an InputStream from a large object.
 */
public class BlobInputStream extends InputStream {
  private static final Logger LOGGER = Logger.getLogger(BlobInputStream.class.getName());
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
  private long absolutePosition = -1;

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
  private long markPosition = -1;

  /**
   * The limit.
   */
  private long limit;

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

    // Limit and position must not be accessed until initialized by getLo()
    this.limit = limit;
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
   * Closes this input stream and releases any system resources associated with the stream.
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
   * Marks the current position in this input stream. A subsequent call to the <code>reset</code>
   * method repositions this stream at the last marked position so that subsequent reads re-read the
   * same bytes.
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
      try {
        getLo();
        markPosition = absolutePosition;
      } catch (IOException e) {
        LOGGER.log(Level.SEVERE, "Failed to set mark position", e);
      }
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
      buffer = null;
      try {
        if (markPosition <= Integer.MAX_VALUE) {
          lo.seek((int) markPosition);
        } else {
          lo.seek64(markPosition, LargeObject.SEEK_SET);
        }
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

  /**
   * Skips over and discards {@code n} bytes of data from this input stream.
   *
   * <p>Due to the "sparse" implementation of Large Objects, this class allows skipping
   * past the "end" of the stream. Subsequent reads will continue to return {@code -1}.
   *
   * @param n the number of bytes to be skipped.
   * @return the actual number of bytes skipped which might be zero.
   * @throws IOException  if an underlying driver error occurs.
   *     In particular, this will throw if attempting to skip beyond the maximum length
   *     of a large object, which by default is 4,398,046,509,056 bytes.
   * @see java.io.InputStream#skip(long)
   * @see <a href="https://www.postgresql.org/docs/14/lo-implementation.html">
   *   Large Objects: Implementation Features</a>
   */
  @Override
  public long skip(long n) throws IOException {
    if (n <= 0) {
      // The spec does allow for skipping backwards, but let's not.
      return 0;
    }

    try (ResourceLock ignore = lock.obtain()) {
      LargeObject lo = getLo();
      long loId = lo.getLongOID();

      long targetPosition = absolutePosition + n;
      if (targetPosition > limit || targetPosition < 0) {
        targetPosition = limit;
      }
      long currentPosition = absolutePosition;
      long skipped = targetPosition - currentPosition;

      if (buffer != null && buffer.length - bufferPosition > skipped) {
        bufferPosition += (int) skipped;
      } else {
        buffer = null;
        try {
          if (targetPosition <= Integer.MAX_VALUE) {
            lo.seek((int) targetPosition, LargeObject.SEEK_SET);
          } else {
            lo.seek64(targetPosition, LargeObject.SEEK_SET);
          }
        } catch (SQLException e) {
          throw new IOException(
              GT.tr("Can not skip stream for large object {0} by {1} (currently @{2})",
                  loId, n, currentPosition),
              e);
        }
      }
      absolutePosition = targetPosition;
      return skipped;
    }
  }

  private LargeObject getLo() throws IOException {
    if (this.lo == null) {
      throw new IOException("BlobOutputStream is closed");
    }
    LargeObject lo = castNonNull(this.lo);
    assert lock.isLocked();

    if (absolutePosition < 0) {
      // Defer initialization until here so it can throw a checked exception
      try {
        // initialise current position for mark/reset
        this.absolutePosition = lo.tell64();
      } catch (SQLException e1) {
        try {
          // the tell64 function does not exist before PostgreSQL 9.3
          this.absolutePosition = lo.tell();
        } catch (SQLException e2) {
          IOException e3 = new IOException("Failed to initialize BlobInputStream position", e1);
          e3.addSuppressed(e2);
          throw e3;
        }
      }

      limit = limit == -1 ? Long.MAX_VALUE : limit + absolutePosition;
      markPosition = absolutePosition;
    }

    return lo;
  }
}
