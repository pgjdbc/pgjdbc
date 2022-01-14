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
   * Buffer used to improve performance.
   */
  private byte[] buffer;

  /**
   * Absolute position of the very first element in {@link #buffer}.
   */
  private long bufferStartPosition;

  /**
   * Number of ready bytes in {@link #buffer}.
   */
  private int bufferPosition;

  /**
   * The mark position (absolute).
   */
  private int markPosition = -1;

  private int marklimit = 0;
  /**
   * The limit.
   */
  private final long limit;

  private int count = 0;

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

  private void fill() throws IOException, SQLException {
    // check EOF
    if (markPosition < 0) {
      bufferPosition = 0;
    } else if (bufferPosition >= buffer.length) { /* no mark: throw away the buffer */
      /* no room left in buffer */
      if (markPosition > 0) {
        /* can throw away early part of the buffer */
        int sz = bufferPosition - markPosition;
        System.arraycopy(buffer, markPosition, buffer, 0, sz);
        bufferPosition = sz;
        markPosition = 0;
      } else if (buffer.length >= marklimit) {
        markPosition = -1;   /* buffer got too big, invalidate mark */
        bufferPosition = 0;        /* drop buffer contents */
      } else {            /* grow buffer */
        int nsz = buffer.length * 2;
        if (nsz > marklimit) {
          nsz = marklimit;
        }
        byte[] nbuf = new byte[nsz];
        System.arraycopy(buffer, 0, nbuf, 0, markPosition);
        buffer = nbuf;
      }
    }
    count = bufferPosition;
    int n = read1(buffer, bufferPosition, buffer.length - bufferPosition);
    if (n > 0) {
      count = n + bufferPosition;
    }
  }

  private int read1(byte []b, int pos, int len) throws IOException {
    int bytesRead = -1;
    LargeObject lo = getLo();
    try {
      byte[] tmp = lo.read(len);

      if (tmp != null) {
        // only copy what we read
        System.arraycopy(tmp, 0, b, pos, tmp.length);
        bytesRead = Math.min(tmp.length, len);
      }
    } catch (SQLException e) {
      throw new IOException(e.getCause());
    }
    return bytesRead;
  }

  private int read2( byte []b, int off, int len ) throws IOException, SQLException {
    int avail = count - bufferPosition;
    if (avail <= 0) {
      /*
        If the requested length is at least as large as the buffer, and
        if there is no mark/reset activity, do not bother to copy the
        bytes into the local buffer.  In this way buffered streams will
        cascade harmlessly.
      */
      if (len >= buffer.length && markPosition < 0) {
        return read1(b, off, len);
      }
      fill();
      avail = count - bufferPosition;
      if (avail <= 0) {
        return -1;
      }
    }
    int cnt = (avail < len) ? avail : len;
    System.arraycopy(buffer, bufferPosition, b, off, cnt);
    bufferPosition += cnt;
    return cnt;
  }

  /**
   * The minimum required to implement input stream.
   */
  public int read() throws java.io.IOException {
    try {
      if (bufferPosition >= count) {
        fill();
        if (bufferPosition >= count) {
          return -1;
        }
      }
      return buffer[bufferPosition++] & 0xff;
    } catch (SQLException se) {
      throw new IOException(se.toString());
    }
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    getLo();
    if ((off | len | (off + len) | (b.length - (off + len))) < 0) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }
    try {
      int n = 0;
      for (; ; ) {
        int nread = read2(b, off + n, len - n);
        if (nread <= 0) {
          return (n == 0) ? nread : n;
        }
        n += nread;
        if (n >= len) {
          return n;
        }
        /*
          TODO
          if not closed but no bytes available, return

          InputStream input = in;
          if (input != null && input.available() <= 0)
          return n;

        */
      }
    } catch ( SQLException e) {
      throw new IOException(e.getCause());
    }
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
    marklimit = readlimit;
    markPosition = bufferPosition;
  }

  /**
   * Repositions this stream to the position at the time the <code>mark</code> method was last
   * called on this input stream. NB: If mark is not called we move to the beginning.
   *
   * @see java.io.InputStream#mark(int)
   * @see java.io.IOException
   */
  public synchronized void reset() throws IOException {

    // check if closed
    getLo();
    if (markPosition < 0) {
      throw new IOException("Resetting to invalid mark");
    }
    bufferPosition = markPosition;
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
