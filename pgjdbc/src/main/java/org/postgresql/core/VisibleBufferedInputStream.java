/*
 * Copyright (c) 2006, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/**
 * A faster version of BufferedInputStream. Does no synchronisation and allows direct access to the
 * used byte[] buffer.
 *
 * @author Mikko Tiihonen
 */
public class VisibleBufferedInputStream extends InputStream {

  /**
   * If a direct read to byte array is called that would require a smaller read from the wrapped
   * stream that MINIMUM_READ then first fill the buffer and serve the bytes from there. Larger
   * reads are directly done to the provided byte array.
   */
  private static final int MINIMUM_READ = 1024;

  /**
   * In how large spans is the C string zero-byte scanned.
   */
  private static final int STRING_SCAN_SPAN = 1024;

  /**
   * The wrapped input stream.
   */
  private final InputStream wrapped;

  /**
   * The buffer.
   */
  private byte[] buffer;

  /**
   * Current read position in the buffer.
   */
  private int index;

  /**
   * How far is the buffer filled with valid data.
   */
  private int endIndex;

  /**
   * socket timeout has been requested
   */
  private boolean timeoutRequested = false;

  /**
   * Creates a new buffer around the given stream.
   *
   * @param in The stream to buffer.
   * @param bufferSize The initial size of the buffer.
   */
  public VisibleBufferedInputStream(InputStream in, int bufferSize) {
    wrapped = in;
    buffer = new byte[bufferSize < MINIMUM_READ ? MINIMUM_READ : bufferSize];
  }

  private int bytesRead() {
    synchronized ( buffer ) {
      return endIndex - index;
    }
  }

  /**
   * {@inheritDoc}
   */
  public int read() throws IOException {
    if ( bytesRead() > 0 ) {
      synchronized ( buffer ) {
        return buffer[index++] & 0xFF;
      }
    }
    return -1;
  }

  public int readDirect() throws IOException {
    return wrapped.read();
  }
  /**
   * Reads a byte from the buffer without advancing the index pointer.
   *
   * @return byte from the buffer without advancing the index pointer
   * @throws IOException if something wrong happens
   */
  public int peek() throws IOException {
    if ( bytesRead() > 0 ) {
      return buffer[index] & 0xFF;
    }
    return -1;
  }

  /**
   * Ensures that the buffer contains at least n bytes. This method invalidates the buffer and index
   * fields.
   *
   * @param n The amount of bytes to ensure exists in buffer
   * @return true if required bytes are available and false if EOF
   * @throws IOException If reading of the wrapped stream failed.
   */
  public boolean ensureBytes(int n) throws IOException {
    return ensureBytes(n, true);
  }

  public boolean readBackground() throws IOException {
    int roomLeft = roomAvailable();

    if ( roomLeft > 0  ) {
      try {
        int actuallyRead = wrapped.read(buffer, endIndex, roomLeft);
        synchronized (buffer) {
          endIndex += actuallyRead;
        }
        return true;
      } catch ( SocketTimeoutException ste ) {
        // TODO do something with this later
      }
    }
    return false;
  }
  /**
   * Ensures that the buffer contains at least n bytes. This method invalidates the buffer and index
   * fields.
   *
   * @param n The amount of bytes to ensure exists in buffer
   * @param block whether or not to block the IO
   * @return true if required bytes are available and false if EOF or the parameter block was false and socket timeout occurred.
   * @throws IOException If reading of the wrapped stream failed.
   */
  public boolean ensureBytes(int n, boolean block) throws IOException {
    int required = n - endIndex + index;
    while (required > 0) {
      if (!readMore(required, block)) {
        return false;
      }
      required = n - endIndex + index;
    }
    return true;
  }

  /**
   * Reads more bytes into the buffer.
   *
   * @param wanted How much should be at least read.
   * @return True if at least some bytes were read.
   * @throws IOException If reading of the wrapped stream failed.
   */
  private boolean readMore(int wanted, boolean block) throws IOException {
    if (endIndex == index) {
      index = 0;
      endIndex = 0;
    }
    int canFit = buffer.length - endIndex;
    if (canFit < wanted) {
      // would the wanted bytes fit if we compacted the buffer
      // and still leave some slack
      if (index + canFit > wanted + MINIMUM_READ) {
        compact();
      } else {
        doubleBuffer();
      }
      canFit = buffer.length - endIndex;
    }
    int read = 0;
    try {
      read = wrapped.read(buffer, endIndex, canFit);
      if (!block && read == 0) {
        return false;
      }
    } catch (SocketTimeoutException e) {
      if (!block) {
        return false;
      }
      if (timeoutRequested) {
        throw e;
      }
    }
    if (read < 0) {
      return false;
    }
    endIndex += read;
    return true;
  }

  /**
   * Doubles the size of the buffer.
   */
  private void doubleBuffer() {
    byte[] buf = new byte[buffer.length * 2];
    moveBufferTo(buf);
    buffer = buf;
  }

  /**
   * Compacts the unread bytes of the buffer to the beginning of the buffer.
   */
  private void compact() {
    moveBufferTo(buffer);
  }

  /**
   * Moves bytes from the buffer to the beginning of the destination buffer. Also sets the index and
   * endIndex variables.
   *
   * @param dest The destination buffer.
   */
  private void moveBufferTo(byte[] dest) {
    int size = endIndex - index;
    System.arraycopy(buffer, index, dest, 0, size);
    index = 0;
    endIndex = size;
  }

  /**
   * {@inheritDoc}
   */
  public int read(byte[] to, int off, int len) throws IOException {
    if ((off | len | (off + len) | (to.length - (off + len))) < 0) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }
    int read = 0;
    while ( len > 0 ) {
      // if the read would go to wrapped stream, but would result
      // in a small read then try read to the buffer instead
      int avail = bytesRead();
      if ( avail > 0  ) {
        // first copy from buffer
        synchronized (buffer) {

          int bytesToRead = avail < len ? avail:len;
          System.arraycopy(buffer, index, to, off, bytesToRead);

          len -= bytesToRead;
          off += bytesToRead;
          read += bytesToRead;
          index += bytesToRead;
        }
      } else {
        try {
          Thread.sleep(10);
        } catch ( InterruptedException ie ) {
          break;
        }
      }
    }
    return read;
  }

  /**
   * {@inheritDoc}
   */
  public long skip(long n) throws IOException {
    int avail = 0;
    avail = bytesRead();
    synchronized (buffer) {
      if (avail >= n) {
        index += n;
        return n;
      } else {
        index += avail;
        return avail;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  public int available() throws IOException {
    return bytesRead();
  }

  /**
   * {@inheritDoc}
   */
  public void close() throws IOException {
    wrapped.close();
  }

  /**
   * Returns direct handle to the used buffer. Use the {@link #ensureBytes} to prefill required
   * bytes the buffer and {@link #getIndex} to fetch the current position of the buffer.
   *
   * @return The underlaying buffer.
   */
  public byte[] getBuffer() {
    return buffer;
  }

  /**
   * Returns the current read position in the buffer.
   *
   * @return the current read position in the buffer.
   */
  public int getIndex() {
    return index;
  }

  public int getEndIndex() {
    return endIndex;
  }

  /**
   * Scans the length of the next null terminated string (C-style string) from the stream.
   *
   * @return The length of the next null terminated string.
   * @throws IOException If reading of stream fails.
   * @throws EOFException If the stream did not contain any null terminators.
   */
  public int scanCStringLength() throws IOException {
    int pos = index;
    while (true) {
      while (pos < endIndex) {
        if (buffer[pos++] == '\0') {
          return pos - index;
        }
      }
      if (!readMore(STRING_SCAN_SPAN, true)) {
        throw new EOFException();
      }
      pos = index;
    }
  }

  public int roomAvailable() {
    synchronized ( buffer ) {
      if ( index == endIndex ) {
        index = endIndex = 0;
      }
      return buffer.length - endIndex;
    }
  }

  public void setTimeoutRequested(boolean timeoutRequested) {
    this.timeoutRequested = timeoutRequested;
  }

  /**
   *
   * @return the wrapped stream
   */
  public InputStream getWrapped() {
    return wrapped;
  }
}
