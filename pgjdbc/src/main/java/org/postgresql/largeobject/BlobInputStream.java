/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;

/**
 * This is an implementation of an InputStream from a large object.
 */
public class BlobInputStream extends InputStream {
  /**
   * The parent LargeObject.
   */
  private @Nullable LargeObject lo;

  /**
   * Buffer for {@link #read()} implementation, so we don't have specialization for one-byte reads.
   */
  private final byte[] buf1 = new byte[1];

  /**
   * Buffer used to improve performance.
   */
  private final byte[] buffer;

  /**
   * Absolute position of the very first element in {@link #buffer}.
   */
  private long bufferStartPosition;

  /**
   * Number of ready bytes in {@link #buffer}.
   */
  private int bytesInBuffer;

  /**
   * The mark position (absolute).
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
    this(lo, 16384);
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
    this.buffer = new byte[(int) Math.min(bsize, limit)];
    this.limit = limit;
  }

  /**
   * The minimum required to implement input stream.
   */
  public int read() throws java.io.IOException {
    return read(buf1) <= 0 ? -1 : buf1[0] & 0xff;
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    LargeObject lo = getLo();
    if (off < 0 || len < 0 || off + len < 0 || b.length < off + len) {
      throw new IndexOutOfBoundsException("buffer.length = " + b.length
          + ", offset = " + off + ", length = " + len);
    }

    long absolutePosition = lo.getFdPosition();
    // If limit is set
    if (limit >= 0) {
      long available = limit - absolutePosition;
      if (available <= 0) {
        return -1;
      }
      // Make sure we are not going to read past the limit
      len = Math.toIntExact(Math.min(len, available));
    }

    int bytesCopied = 0;
    // Relative position in the buffer of the requested data
    final long bufferPosition = absolutePosition - bufferStartPosition;
    if (0 <= bufferPosition && bufferPosition < bytesInBuffer) {
      // Buffer intersects with the requested range, so we copy the buffered data
      int buffPos = Math.toIntExact(bufferPosition);
      // Number of usable bytes in the buffer
      int bytesToCopy = Math.min(len, bytesInBuffer - buffPos);
      System.arraycopy(buffer, buffPos, b, off, bytesToCopy);
      // position in the blob
      absolutePosition += bytesToCopy;
      off += bytesToCopy;
      len -= bytesToCopy;
      bytesCopied = bytesToCopy;
    }

    // Need more data
    if (len > 0) {
      int bytesRead;
      try {
        // When input buffer is large enough, read into that buffer directly
        // It avoids double-buffering
        if (len > buffer.length) {
          bytesRead = lo.read(b, off, len);
          absolutePosition += bytesRead;
          bytesCopied += bytesRead;
        } else {
          bufferStartPosition = absolutePosition;
          bytesRead = lo.read(buffer, 0, buffer.length);
        }
//         bytesRead = lo.read(b, off, len);
      } catch (SQLException ex) {
        throw new IOException("Can't read " + lo, ex);
      }
//       bytesCopied += bytesRead;
//       absolutePosition += bytesRead;
      // If there is a limit on the size of the blob then we could have read to the limit
      // so bytesCopied will be non-zero, but we will have read nothing
      if (bytesCopied == 0) {
        return -1;
      }
    }
    return bytesCopied;
  }

  /**
   * <p>Closes this input stream and releases any system resources associated with the stream.</p>
   *
   * <p>The <code>close</code> method of <code>InputStream</code> does nothing.</p>
   *
   * @throws IOException if an I/O error occurs.
   */
  public void close() throws IOException {
    if (lo != null) {
      try {
        lo.close();
        lo = null;
      } catch (SQLException se) {
        throw new IOException(se.toString());
      }
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
  public synchronized void mark(int readlimit) {
    markPosition = absolutePosition;
  }

  /**
   * Repositions this stream to the position at the time the <code>mark</code> method was last
   * called on this input stream. NB: If mark is not called we move to the beginning.
   *
   * @see java.io.InputStream#mark(int)
   * @see java.io.IOException
   */
  public synchronized void reset() throws IOException {
    LargeObject lo = getLo();
    try {
      if (markPosition <= Integer.MAX_VALUE) {
        lo.seek((int) markPosition);
      } else {
        lo.seek64(markPosition, LargeObject.SEEK_SET);
      }
      buffer = null;
      absolutePosition = markPosition;
    } catch (SQLException se) {
      throw new IOException(se.toString());
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
  public boolean markSupported() {
    return true;
  }

  private LargeObject getLo() throws IOException {
    if (lo == null) {
      throw new IOException("BlobInputStream is closed");
    }
    return lo;
  }
}
