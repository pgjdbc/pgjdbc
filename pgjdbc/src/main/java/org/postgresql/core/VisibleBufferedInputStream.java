/*
 * Copyright (c) 2006, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.ByteConverter;
import org.postgresql.util.GT;

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
   * The largest the buffer will grow. Only control messages and strings are buffered; bulk data
   * is read straight into its destination. This is therefore also the ceiling on any message body
   * read through {@code PGStream.receiveString}, which buffers it whole.
   *
   * <p>This is a driver policy limit, not one the protocol imposes. It bounds what a single
   * message can make the driver allocate before the message is understood. An ErrorResponse or
   * NoticeResponse longer than this is truncated to this size and the remainder of its body is
   * drained, so exceeding it costs a shortened message rather than the connection.</p>
   */
  static final int MAX_BUFFER_SIZE = 32 * 1024 * 1024;

  private static final Runnable NO_OP = new Runnable() {
    @Override
    public void run() {
    }
  };

  /**
   * The wrapped input stream.
   */
  private final InputStream wrapped;

  /**
   * The buffer.
   */
  private byte[] buffer;

  /**
   * Size the buffer started at and returns to once an outsized message has been drained.
   */
  private final int initialSize;

  /**
   * Current read position in the buffer.
   */
  private int index;

  /**
   * How far is the buffer filled with valid data.
   */
  private int endIndex;

  /**
   * Bytes handed out since this stream was created. It only grows, so a caller can record where
   * a message ends and compare later.
   */
  private long position;

  /**
   * socket timeout has been requested
   */
  private boolean timeoutRequested;

  /** Run when a read is refused, so the owner can mark itself broken. */
  private final Runnable onProtocolViolation;

  /**
   * Creates a new buffer around the given stream.
   *
   * @param in The stream to buffer.
   * @param bufferSize The initial size of the buffer.
   */
  public VisibleBufferedInputStream(InputStream in, int bufferSize) {
    this(in, bufferSize, NO_OP);
  }

  /**
   * Creates a new buffer around the given stream.
   *
   * @param in The stream to buffer.
   * @param bufferSize The initial size of the buffer.
   * @param onProtocolViolation run when a read is refused, so the owner can mark itself broken.
   */
  public VisibleBufferedInputStream(InputStream in, int bufferSize, Runnable onProtocolViolation) {
    wrapped = in;
    initialSize = bufferSize < MINIMUM_READ ? MINIMUM_READ : bufferSize;
    buffer = new byte[initialSize];
    this.onProtocolViolation = onProtocolViolation;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int read() throws IOException {
    if (ensureBytes(1)) {
      position++;
      return buffer[index++] & 0xFF;
    }
    return -1;
  }

  /**
   * Reads an int2 value from the underlying stream as an unsigned integer (0..65535).
   * @return int2 in the range of 0..65535
   * @throws IOException if an I/ O error occurs.
   */
  public int readInt2() throws IOException {
    if (ensureBytes(2)) {
      int res = ByteConverter.int2(buffer, index) & 0xffff;
      index += 2;
      position += 2;
      return res;
    }
    throw new EOFException("End of stream reached while trying to read integer2");
  }

  /**
   * Reads an int4 value from the underlying stream.
   * @return int4 value from the underlying stream
   * @throws IOException if an I/ O error occurs.
   */
  public int readInt4() throws IOException {
    if (ensureBytes(4)) {
      int res = ByteConverter.int4(buffer, index);
      index += 4;
      position += 4;
      return res;
    }
    throw new EOFException("End of stream reached while trying to read integer4");
  }

  /**
   * Reads a byte from the buffer without advancing the index pointer.
   *
   * @return byte from the buffer without advancing the index pointer
   * @throws IOException if something wrong happens
   */
  public int peek() throws IOException {
    if (ensureBytes(1)) {
      return buffer[index] & 0xFF;
    }
    return -1;
  }

  /**
   * Reads byte from the buffer without any checks. This method never reads from the underlying
   * stream. Before calling this method the {@link #ensureBytes} method must have been called.
   *
   * @return The next byte from the buffer.
   * @throws ArrayIndexOutOfBoundsException If ensureBytes was not called to make sure the buffer
   *         contains the byte.
   */
  public byte readRaw() {
    position++;
    return buffer[index++];
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
      growBuffer(wanted);
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
   * Makes room for {@code wanted} more bytes, compacting if that is enough and growing if not.
   *
   * @param wanted how many more bytes have to fit
   * @throws IOException if the request does not fit in {@link #MAX_BUFFER_SIZE}
   */
  private void growBuffer(int wanted) throws IOException {
    if (wanted < 0) {
      onProtocolViolation.run();
      throw new IOException(GT.tr("Cannot read a negative number of bytes: {0}.",
          String.valueOf(wanted)));
    }
    // The arithmetic is done in long because wanted comes from the peer.
    long required = (long) endIndex - index + wanted;
    if (required > MAX_BUFFER_SIZE) {
      onProtocolViolation.run();
      throw new IOException(GT.tr(
          "Backend asked for {0} bytes of buffer, the maximum is {1} bytes.",
          String.valueOf(required), String.valueOf(MAX_BUFFER_SIZE)));
    }
    // Compact only when that leaves room for a worthwhile read, or a nearly full buffer would
    // compact on every call and each socket read would be a few bytes. At the maximum there is
    // nothing to grow into, so compacting is all that is left.
    if (required + MINIMUM_READ <= buffer.length || buffer.length >= MAX_BUFFER_SIZE) {
      compact();
      return;
    }
    // Double for small reads, plus slack so a read can pull in more than this request.
    long size = Math.min(Math.max(buffer.length * 2L, required + MINIMUM_READ), MAX_BUFFER_SIZE);
    byte[] buf = new byte[(int) size];
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
   * Returns the buffer to its initial size the moment it is fully drained, so a buffer grown for
   * one large message does not stay that size for the rest of the connection, including while it
   * sits idle in a pool. Called from every path that consumes bytes, so nothing is copied.
   */
  private void shrinkIfDrained() {
    if (index == endIndex && buffer.length > initialSize) {
      buffer = new byte[initialSize];
      index = 0;
      endIndex = 0;
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int read(byte[] to, int off, int len) throws IOException {
    int read = readInternal(to, off, len);
    if (read > 0) {
      position += read;
    }
    return read;
  }

  private int readInternal(byte[] to, int off, int len) throws IOException {
    if ((off | len | (off + len) | (to.length - (off + len))) < 0) {
      throw new IndexOutOfBoundsException();
    } else if (len == 0) {
      return 0;
    }

    // if the read would go to wrapped stream, but would result
    // in a small read then try read to the buffer instead
    int avail = endIndex - index;
    if (len - avail < MINIMUM_READ) {
      ensureBytes(len);
      avail = endIndex - index;
    }

    // first copy from buffer
    if (avail > 0) {
      if (len <= avail) {
        System.arraycopy(buffer, index, to, off, len);
        index += len;
        shrinkIfDrained();
        return len;
      }
      System.arraycopy(buffer, index, to, off, avail);
      len -= avail;
      off += avail;
    }
    int read = avail;

    // good place to reset index because the buffer is fully drained
    index = 0;
    endIndex = 0;
    shrinkIfDrained();

    // then directly from wrapped stream
    do {
      int r;
      try {
        r = wrapped.read(to, off, len);
      } catch (SocketTimeoutException e) {
        if (read == 0 && timeoutRequested) {
          throw e;
        }
        return read;
      }
      if (r <= 0) {
        return read == 0 ? r : read;
      }
      read += r;
      off += r;
      len -= r;
    } while (len > 0);

    return read;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public long skip(long n) throws IOException {
    long skipped = skipInternal(n);
    position += skipped;
    return skipped;
  }

  private long skipInternal(long n) throws IOException {
    int avail = endIndex - index;
    if (avail >= n) {
      // Cast to int is safe here since the number of available bytes within the buffer
      // always fits within int
      index += (int) n;
      shrinkIfDrained();
      return n;
    }
    n -= avail;
    index = 0;
    endIndex = 0;
    shrinkIfDrained();
    return avail + wrapped.skip(n);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int available() throws IOException {
    int avail = endIndex - index;
    return avail > 0 ? avail : wrapped.available();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void close() throws IOException {
    wrapped.close();
  }

  /**
   * Returns direct handle to the used buffer. Use the {@link #ensureBytes} to prefill required
   * bytes the buffer and {@link #getIndex} to fetch the current position of the buffer.
   *
   * @return The underlying buffer.
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

  /**
   * Returns how many bytes this stream has handed out in total.
   *
   * @return the number of bytes consumed since the stream was created
   */
  public long getPosition() {
    return position;
  }

  /**
   * Scans the length of the next null terminated string (C-style string) from the stream.
   *
   * @return The length of the next null terminated string.
   * @throws IOException If reading of stream fails.
   * @throws EOFException If the stream did not contain any null terminators.
   */
  public int scanCStringLength() throws IOException {
    return scanCStringLength(Integer.MAX_VALUE);
  }

  /**
   * Scans the length of the next null terminated string (C-style string) from the stream, looking
   * no further than the given number of bytes.
   *
   * @param maxLength the most bytes the string may occupy, including its terminator.
   * @return The length of the next null terminated string.
   * @throws IOException If reading of stream fails, or no terminator is within maxLength.
   * @throws EOFException If the stream did not contain any null terminators.
   */
  public int scanCStringLength(int maxLength) throws IOException {
    int scanned = 0;
    while (true) {
      // Resume where the last pass stopped. readMore may compact, which moves index. Rescanning
      // from the start is quadratic in a length the peer chooses.
      int pos = index + scanned;
      while (pos < endIndex) {
        scanned++;
        if (buffer[pos++] == '\0') {
          return scanned;
        }
        if (scanned >= maxLength) {
          onProtocolViolation.run();
          throw new IOException(GT.tr("No string terminator within {0} bytes.",
              String.valueOf(maxLength)));
        }
      }
      if (!readMore(STRING_SCAN_SPAN, true)) {
        throw new EOFException();
      }
    }
  }

  public void setTimeoutRequested(boolean timeoutRequested) {
    this.timeoutRequested = timeoutRequested;
  }

  /**
   * Returns the underlying stream.
   * @return the underlying stream
   */
  public InputStream getWrapped() {
    return wrapped;
  }
}
