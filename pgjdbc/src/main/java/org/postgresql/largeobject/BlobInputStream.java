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
   * The buffer size.
   */
  private final int bufferSize;

  /**
   * The mark position.
   */
  private long markPosition = 0;

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
    bufferPosition = 0;
    absolutePosition = 0;
    this.bufferSize = bsize;
    this.limit = limit;
  }

  /**
   * The minimum required to implement input stream.
   */
  public int read() throws java.io.IOException {
    LargeObject lo = getLo();
    try {
      if (limit > 0 && absolutePosition >= limit) {
        return -1;
      }
      // read more in if necessary
      if (buffer == null || bufferPosition >= buffer.length) {
        buffer = lo.read(bufferSize);
        bufferPosition = 0;
      }

      // Handle EOF
      if ( buffer == null || bufferPosition >= buffer.length) {
        return -1;
      }

      int ret = (buffer[bufferPosition] & 0xFF);

      bufferPosition++;
      absolutePosition++;

      return ret;
    } catch (SQLException se) {
      throw new IOException(se.toString());
    }
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    int bytesCopied = 0;
    LargeObject lo = getLo();

    /* check to make sure we aren't at the limit
    *  funny to test for 0, but I guess someone could create a blob
    * with a limit of zero
    */
    if ( limit >= 0 && absolutePosition >= limit ) {
      return -1;
    }

    /* check to make sure we are not going to read past the limit */
    if ( limit >= 0 && len > limit - absolutePosition ) {
      len = (int)(limit - absolutePosition);
    }

    try {
      // have we read anything into the buffer
      if ( buffer != null ) {
        // now figure out how much data is in the buffer
        int bytesInBuffer = buffer.length - bufferPosition;
        // figure out how many bytes the user wants
        int bytesToCopy = len > bytesInBuffer ? bytesInBuffer : len;
        // copy them in
        System.arraycopy(buffer, bufferPosition, b, off, bytesToCopy);
        // move the buffer position
        bufferPosition += bytesToCopy;
        // position in the blob
        absolutePosition += bytesToCopy;
        // increment offset
        off += bytesToCopy;
        // decrement the length
        len -= bytesToCopy;
        bytesCopied = bytesToCopy;
      }

      if (len > 0 ) {
        bytesCopied += lo.read(b, off, len);
        buffer = null;
        bufferPosition = 0;
        absolutePosition += bytesCopied;
        /*
        if there is a limit on the size of the blob then we could have read to the limit
        so bytesCopied will be non-zero but we will have read nothing
         */
        if ( bytesCopied == 0 && (buffer == null) ) {
          return -1;
        }
      }
    } catch (SQLException ex ) {
      throw new IOException(ex.getCause());
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
        lo.seek((int)markPosition);
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
      throw new IOException("BlobOutputStream is closed");
    }
    return lo;
  }
}
