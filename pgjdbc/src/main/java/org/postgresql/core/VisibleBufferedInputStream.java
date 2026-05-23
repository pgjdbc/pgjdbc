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
   * Logical stream position of the byte at {@code buffer[0]}, in bytes consumed since
   * construction. The current logical position is {@code position + index} (exposed via
   * {@link #getPosition()}), so in-buffer reads on the hot path advance the position
   * implicitly via {@code index} and require no bookkeeping. The field is touched only
   * when the buffer is shifted, drained, or bypassed: {@link #moveBufferTo(byte[])}
   * (the compact/double path), the buffer-drain reset in
   * {@link #readMore(int, boolean)}, {@link #read(byte[], int, int)}, and
   * {@link #skip(long)}. The last two also bump {@code position} for bytes they read
   * or skip directly from the wrapped stream, bypassing the buffer. Exposed so callers
   * (notably {@code PGStream}) can compute envelope endpoints once per protocol message
   * and verify exact consumption without instrumenting every receive site.
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
   * Reads more bytes into the buffer.
   *
   * @param wanted How much should be at least read.
   * @return True if at least some bytes were read.
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
   * Moves bytes from the buffer to the beginning of the destination buffer. Also sets the index and
   * endIndex variables.
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

    // The buffer is fully drained: we copied `avail` bytes out without bumping `index`,
    // so the buffer is logically consumed up to endIndex. position += endIndex captures
    // both the previously-skipped index bytes and the avail bytes just copied.
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
      // Bytes copied directly from the wrapped stream bypass the buffer, so they are not
      // accounted for by the position += endIndex reset above; track them explicitly.
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
    // The buffer is fully consumed (the `avail` bytes are skipped logically, not copied),
    // so the new logical base is position + endIndex.
    position += endIndex;
    index = 0;
    endIndex = 0;
    long skipped = wrapped.skip(n);
    // Bytes skipped directly on the wrapped stream bypass the buffer; account for them.
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
   * Returns the total number of bytes consumed from the logical stream since this
   * {@code VisibleBufferedInputStream} was constructed. Hot-path reads that draw from the
   * buffer don't bump any counter; the value is computed as the position of the buffer
   * base plus the in-buffer index.
   *
   * @return total bytes consumed from the logical stream
   */
  public long getPosition() {
    return position + index;
  }

  /**
   * Scans the length of the next null-terminated string from the stream, rejecting a scan
   * that would consume more than {@code maxBytes} bytes without finding a NUL. This is used
   * to prevent an unbounded scan (and unbounded buffer growth) on a desynced stream.
   *
   * <p>{@code packetName} and {@code messageLength} are used only to enrich the
   * {@link IOException} thrown on a budget violation, so an operator triaging a desync can
   * see which protocol message and which declared envelope size was being parsed without
   * needing to attach a debugger.</p>
   *
   * @param maxBytes inclusive maximum number of bytes the scan is allowed to consume,
   *                 including the trailing NUL
   * @param packetName protocol message name surfaced in the error
   * @param messageLength declared total length (including the 4 length bytes) of the protocol
   *                      message currently being parsed, surfaced in the error
   * @return the length of the next null-terminated string (including the trailing NUL)
   * @throws IOException if no NUL is found within {@code maxBytes}, or if reading fails
   */
  public int scanCStringLength(int maxBytes, String packetName, int messageLength)
      throws IOException {
    if (maxBytes <= 0) {
      throw new IOException(GT.tr(
          "Protocol error. Unexpected C-string in {0} message of {1} bytes (remaining budget: {2} bytes).",
          packetName, messageLength, maxBytes));
    }
    int scanned = 0;
    while (true) {
      // After readMore() the buffer may have been compacted (index reset to 0) or extended
      // (index unchanged). Either way, the bytes already counted in `scanned` are now at
      // [index, index + scanned), so resume scanning from index + scanned to avoid
      // re-counting them and tripping the budget check on well-formed traffic.
      int pos = index + scanned;
      while (pos < endIndex) {
        scanned++;
        if (buffer[pos++] == '\0') {
          return scanned;
        }
        if (scanned > maxBytes) {
          throw new IOException(GT.tr(
              "Protocol error. C-string in {0} message of {1} bytes exceeds remaining budget of {2} bytes.",
              packetName, messageLength, maxBytes));
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
