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
 * A faster version of {@link java.io.BufferedInputStream BufferedInputStream}. Does no
 * synchronisation and allows direct access to the used byte[] buffer.
 *
 * <p>The stream also counts the bytes consumed since construction, which {@link PGStream} reads to
 * record where a protocol message must end and to verify that it ended there. A method that resets
 * {@code index} has to add the consumed bytes it discards to {@code position} first, and a method
 * that reads or skips outside the buffer has to add those bytes too.</p>
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
   * Bytes consumed before {@code buffer[0]}, counted since construction. The logical position is
   * {@code position + index}, exposed by {@link #getPosition()}, so a read served out of the
   * buffer advances it through {@code index} alone and costs no bookkeeping. Never decreases;
   * a skipped byte counts as consumed.
   */
  private long position;

  /**
   * socket timeout has been requested
   */
  private boolean timeoutRequested;

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

  /**
   * {@inheritDoc}
   */
  @Override
  public int read() throws IOException {
    if (ensureBytes(1)) {
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
   * Reads more bytes into the buffer. The buffer may be replaced and the unread bytes moved, so a
   * caller must re-read {@link #getBuffer()} and {@link #getIndex()} afterwards.
   *
   * @param wanted number of free bytes to make room for before reading; the buffer is compacted or
   *               doubled at most once per call, so it can still have less room, and the read
   *               itself may return fewer
   * @param block false to give up when the wrapped stream has nothing ready or times out
   * @return false at end of stream, or when {@code block} is false and the wrapped stream had
   *         nothing ready; true otherwise, which does not promise that any bytes arrived
   * @throws IOException If reading of the wrapped stream failed.
   */
  private boolean readMore(int wanted, boolean block) throws IOException {
    if (endIndex == index) {
      position += index;
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
   * Moves the unread bytes to the beginning of the destination buffer, which may be the current
   * buffer itself. Also updates {@code position}, {@code index} and {@code endIndex} to match the
   * new buffer base. The caller installs {@code dest} as the buffer.
   *
   * @param dest The destination buffer.
   */
  private void moveBufferTo(byte[] dest) {
    int size = endIndex - index;
    System.arraycopy(buffer, index, dest, 0, size);
    position += index;
    index = 0;
    endIndex = size;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int read(byte[] to, int off, int len) throws IOException {
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
        return len;
      }
      System.arraycopy(buffer, index, to, off, avail);
      len -= avail;
      off += avail;
    }
    int read = avail;

    // The copy above did not advance index, so the bytes the buffer has served are the index
    // bytes earlier reads consumed plus the avail bytes just copied out. Their sum is endIndex.
    position += endIndex;
    index = 0;
    endIndex = 0;

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
      // These bytes never pass through the buffer, so nothing else adds them to position.
      position += r;
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
    int avail = endIndex - index;
    if (avail >= n) {
      // Cast to int is safe here since the number of available bytes within the buffer
      // always fits within int
      index += (int) n;
      return n;
    }
    n -= avail;
    // Skipping the avail bytes did not advance index, so the bytes the buffer has served are
    // the index bytes earlier reads consumed plus those avail bytes. Their sum is endIndex.
    position += endIndex;
    index = 0;
    endIndex = 0;
    long skipped = wrapped.skip(n);
    // These bytes never pass through the buffer, so nothing else adds them to position.
    position += skipped;
    return avail + skipped;
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
   * Returns the number of bytes read or skipped since construction.
   *
   * <p>A byte counts when a caller reads or skips it, not when the buffer reads it from the wrapped
   * stream. Inspecting a byte in place does not change the count, so {@link #peek()} and
   * {@link #scanCStringLength(int, int, String, int)} do not advance it.</p>
   *
   * @return bytes consumed so far, never decreasing
   */
  public long getPosition() {
    return position + index;
  }

  /**
   * Scans the length of the next null-terminated string without consuming it.
   *
   * <p>The NUL has to arrive within {@code messageBudget} or {@code fieldLimit} bytes, whichever is
   * smaller, so that a stream that has fallen out of sync cannot drive an unbounded scan. The
   * scanned bytes stay in the buffer for the caller to decode, so the smaller of the two also
   * limits how far the buffer grows before the scan fails.</p>
   *
   * <p>{@link #getPosition()} does not move. The caller decodes from {@link #getBuffer()} at
   * {@link #getIndex()} and then skips the returned length.</p>
   *
   * @param messageBudget inclusive maximum the message leaves for this string, including the
   *                      trailing NUL; must be positive
   * @param fieldLimit inclusive maximum for this one string, including the trailing NUL; must be
   *                   positive; the smaller of this and {@code messageBudget} applies
   * @param messageName protocol message name; used only in the error message
   * @param messageLength declared total length (including the 4 length bytes) of the protocol
   *                      message currently being parsed; used only in the error message
   * @return the length of the next null-terminated string (including the trailing NUL)
   * @throws IllegalArgumentException if {@code fieldLimit} is not positive; no byte is read
   * @throws EOFException if end of stream is reached before a NUL is found
   * @throws CStringLimitException if {@code fieldLimit} is the smaller maximum and the string
   *                               overruns it
   * @throws IOException if {@code messageBudget} is not positive, if the string overruns
   *                     {@code messageBudget}, or if reading fails
   */
  public int scanCStringLength(int messageBudget, int fieldLimit, String messageName,
      int messageLength) throws IOException {
    // A non-positive field limit is a defect in the calling driver code, so it is reported as
    // such before the budget, which comes from the backend, is checked.
    if (fieldLimit <= 0) {
      throw new IllegalArgumentException(GT.tr("C-string field limit {0} must be positive",
          String.valueOf(fieldLimit)));
    }
    if (messageBudget <= 0) {
      throw new IOException(GT.tr(
          "Protocol error. {0} message of {1} bytes has no room left for a C-string (remaining budget: {2} bytes).",
          messageName, String.valueOf(messageLength), String.valueOf(messageBudget)));
    }
    int maxBytes = Math.min(messageBudget, fieldLimit);
    int scanned = 0;
    while (true) {
      // readMore may move the unread bytes to the front of the buffer, so index can change.
      // The scanned bytes are unread and still sit at [index, index + scanned), so the scan
      // resumes at index + scanned.
      int pos = index + scanned;
      while (pos < endIndex) {
        scanned++;
        // The budget test comes before the NUL test because the returned length counts the NUL:
        // byte maxBytes + 1 is over the limit whether or not it is the terminator.
        if (scanned > maxBytes) {
          if (fieldLimit < messageBudget) {
            throw new CStringLimitException(GT.tr(
                "Protocol error. C-string in {0} message of {1} bytes exceeds the pgjdbc limit of {2} bytes on a single C-string.",
                messageName, String.valueOf(messageLength), String.valueOf(fieldLimit)));
          }
          throw new IOException(GT.tr(
              "Protocol error. C-string in {0} message of {1} bytes exceeds remaining budget of {2} bytes.",
              messageName, String.valueOf(messageLength), String.valueOf(messageBudget)));
        }
        if (buffer[pos++] == '\0') {
          return scanned;
        }
      }
      if (!readMore(STRING_SCAN_SPAN, true)) {
        throw new EOFException();
      }
    }
  }

  /**
   * Signals that a C-string overran the limit on one string rather than the bytes left in the
   * message that carries it. {@link PGStream} distinguishes the two by type rather than by the
   * exception text, because {@code -Dpgjdbc.protocolHardeningMode=disable} lifts this limit and not
   * the message budget. That mode lifts the limit only where a tracked message bounds the scan in
   * its place. PGStream replaces this exception with its own error, built from the message it
   * tracks: inside a tracked message the error names that mode as the remedy, and elsewhere it
   * names no message.
   */
  static class CStringLimitException extends IOException {
    private static final long serialVersionUID = 1L;

    CStringLimitException(String message) {
      super(message);
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
