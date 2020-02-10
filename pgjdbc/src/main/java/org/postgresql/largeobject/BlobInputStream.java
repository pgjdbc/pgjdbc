/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

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
  private LargeObject lo;

  /**
   * The absolute position.
   */
  private long apos;

  /**
   * Buffer used to improve performance.
   */
  private byte[] buffer;

  /**
   * Position within buffer.
   */
  private int bpos;

  /**
   * The buffer size.
   */
  private int bsize;

  /**
   * The mark position.
   */
  private long mpos = 0;

  /**
   * The limit.
   */
  private long limit = -1;

  /**
   * @param lo LargeObject to read from
   */
  public BlobInputStream(LargeObject lo) {
    this(lo, 1024);
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
    buffer = null;
    bpos = 0;
    apos = 0;
    this.bsize = bsize;
    this.limit = limit;
  }

  /**
   * The minimum required to implement input stream.
   */
  public int read() throws java.io.IOException {
    checkClosed();
    try {
      if (limit > 0 && apos >= limit) {
        return -1;
      }
      if (buffer == null || bpos >= buffer.length) {
        buffer = lo.read(bsize);
        bpos = 0;
      }

      // Handle EOF
      if (bpos >= buffer.length) {
        return -1;
      }

      int ret = (buffer[bpos] & 0x7F);
      if ((buffer[bpos] & 0x80) == 0x80) {
        ret |= 0x80;
      }

      bpos++;
      apos++;

      return ret;
    } catch (SQLException se) {
      throw new IOException(se.toString());
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
    mpos = apos;
  }

  /**
   * Repositions this stream to the position at the time the <code>mark</code> method was last
   * called on this input stream. NB: If mark is not called we move to the beginning.
   *
   * @see java.io.InputStream#mark(int)
   * @see java.io.IOException
   */
  public synchronized void reset() throws IOException {
    checkClosed();
    try {
      if (mpos <= Integer.MAX_VALUE) {
        lo.seek((int)mpos);
      } else {
        lo.seek64(mpos, LargeObject.SEEK_SET);
      }
      buffer = null;
      apos = mpos;
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

  private void checkClosed() throws IOException {
    if (lo == null) {
      throw new IOException("BlobOutputStream is closed");
    }
  }
}
