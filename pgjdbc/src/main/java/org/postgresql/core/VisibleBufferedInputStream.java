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
   * Bytes consumed before {@code buffer[0]}, counted since construction. The logical position is
   * {@code position + index}, exposed by {@link #getPosition()}, so a read served out of the
   * buffer advances it through {@code index} alone and costs no bookkeeping. Bytes read or
   * skipped straight from the wrapped stream bypass the buffer and are added here directly.
   * Never decreases; a skipped byte counts as consumed.
   *
   * <p>{@link PGStream} reads the position to record where a protocol message must end and to
   * verify that it ended there, which is why the count has to hold across a buffer refill.</p>
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
   * Moves bytes from the buffer to the beginning of the destination buffer. Also updates
   * {@code position}, {@code index} and {@code endIndex} to match the new buffer base.
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
   * Returns the total number of bytes read or skipped from the logical stream since this
   * stream was constructed.
   *
   * @return total bytes consumed from the logical stream
   */
  public long getPosition() {
    return position + index;
  }

  /**
   * Scans the length of the next null-terminated string from the stream, rejecting a scan
   * that finds no NUL within {@code envelopeBudget} or {@code fieldCap} bytes, whichever is
   * smaller. This is used to prevent an unbounded scan (and unbounded buffer growth) on a
   * desynced stream.
   *
   * <p>The scan holds every byte it has looked at, because the caller decodes the string
   * straight out of the buffer, so the budget that trips first also caps how far the buffer
   * grows before the failure.</p>
   *
   * @param envelopeBudget inclusive maximum the message envelope leaves for this string,
   *                       including the trailing NUL
   * @param fieldCap inclusive maximum for this one string, including the trailing NUL;
   *                 enforced together with {@code envelopeBudget}, smaller first
   * @param packetName protocol message name; used only in the error message
   * @param messageLength declared total length (including the 4 length bytes) of the protocol
   *                      message currently being parsed; used only in the error message
   * @return the length of the next null-terminated string (including the trailing NUL)
   * @throws EOFException if the stream ends before a NUL is found
   * @throws IOException if no NUL is found within either budget, or if reading fails
   */
  public int scanCStringLength(int envelopeBudget, int fieldCap, String packetName,
      int messageLength) throws IOException {
    if (envelopeBudget <= 0) {
      throw new IOException(GT.tr(
          "Protocol error. Unexpected C-string in {0} message of {1} bytes (remaining budget: {2} bytes).",
          packetName, String.valueOf(messageLength), String.valueOf(envelopeBudget)));
    }
    int maxBytes = Math.min(envelopeBudget, fieldCap);
    int scanned = 0;
    while (true) {
      // After readMore() the buffer may have been compacted (index reset to 0) or extended
      // (index unchanged). Either way, the bytes already counted in `scanned` are now at
      // [index, index + scanned), so resume scanning from index + scanned to avoid
      // re-counting them and tripping the budget check on well-formed traffic.
      int pos = index + scanned;
      while (pos < endIndex) {
        scanned++;
        // Check the budget before the NUL test, not after: the returned length includes
        // the NUL, so a string whose terminator sits at maxBytes + 1 must be rejected even
        // though the scan found a NUL.
        if (scanned > maxBytes) {
          if (fieldCap < envelopeBudget) {
            throw new CStringCeilingException(GT.tr(
                "Protocol error. C-string in {0} message of {1} bytes exceeds the pgjdbc ceiling of {2} bytes on a single C-string.",
                packetName, String.valueOf(messageLength), String.valueOf(fieldCap)));
          }
          throw new IOException(GT.tr(
              "Protocol error. C-string in {0} message of {1} bytes exceeds remaining budget of {2} bytes.",
              packetName, String.valueOf(messageLength), String.valueOf(envelopeBudget)));
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
   * Signals that a C-string hit the per-field ceiling rather than its message envelope. Only
   * the ceiling answers to {@code -Dpgjdbc.protocolHardeningMode=disable}, so {@link PGStream}
   * names that remedy for this failure and not for a spent envelope, and it tells the two
   * apart by type rather than by matching on message text.
   */
  static class CStringCeilingException extends IOException {
    private static final long serialVersionUID = 1L;

    CStringCeilingException(String message) {
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
