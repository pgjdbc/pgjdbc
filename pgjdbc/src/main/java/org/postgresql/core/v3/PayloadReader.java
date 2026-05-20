/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.Encoding;
import org.postgresql.core.Tuple;

import java.io.IOException;
import java.sql.SQLException;

/**
 * Reads protocol fields from a pre-buffered message payload byte array.
 *
 * <p>This provides the same read operations as {@link org.postgresql.core.PGStream} but
 * operates on an in-memory byte array rather than a network stream. Used when async
 * reading is active and the full message has already been read by the reader thread.</p>
 *
 * <p>The payload excludes the message type byte and the 4-byte length field — it starts
 * at the first byte after the length.</p>
 */
final class PayloadReader {
  private final byte[] data;
  private int pos;
  private final Encoding encoding;

  /**
   * Creates a PayloadReader over the given payload bytes.
   *
   * @param data the message payload (after type and length)
   * @param encoding the connection encoding for string decoding
   */
  PayloadReader(byte[] data, Encoding encoding) {
    this.data = data;
    this.pos = 0;
    this.encoding = encoding;
  }

  /**
   * Returns the total length of the payload (equivalent to the wire length field minus 4).
   *
   * @return payload length
   */
  int getLength() {
    return data.length;
  }

  /**
   * Returns remaining bytes available to read.
   *
   * @return bytes remaining
   */
  int remaining() {
    return data.length - pos;
  }

  /**
   * Reads a single byte as an unsigned value (0-255).
   *
   * @return the byte value
   * @throws IOException if no bytes remain
   */
  int receiveChar() throws IOException {
    if (pos >= data.length) {
      throw new IOException("PayloadReader: no bytes remaining");
    }
    return data[pos++] & 0xFF;
  }

  /**
   * Reads a 4-byte big-endian integer.
   *
   * @return the integer value
   * @throws IOException if fewer than 4 bytes remain
   */
  int receiveInteger4() throws IOException {
    if (pos + 4 > data.length) {
      throw new IOException("PayloadReader: need 4 bytes, have " + remaining());
    }
    int val = ((data[pos] & 0xFF) << 24)
        | ((data[pos + 1] & 0xFF) << 16)
        | ((data[pos + 2] & 0xFF) << 8)
        | (data[pos + 3] & 0xFF);
    pos += 4;
    return val;
  }

  /**
   * Reads a 2-byte big-endian unsigned integer (0-65535).
   *
   * @return the integer value
   * @throws IOException if fewer than 2 bytes remain
   */
  int receiveInteger2() throws IOException {
    if (pos + 2 > data.length) {
      throw new IOException("PayloadReader: need 2 bytes, have " + remaining());
    }
    int val = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
    pos += 2;
    return val;
  }

  /**
   * Reads a fixed number of bytes into a new array.
   *
   * @param len number of bytes to read
   * @return the bytes
   * @throws IOException if fewer than len bytes remain
   */
  byte[] receive(int len) throws IOException {
    if (pos + len > data.length) {
      throw new IOException("PayloadReader: need " + len + " bytes, have " + remaining());
    }
    byte[] result = new byte[len];
    System.arraycopy(data, pos, result, 0, len);
    pos += len;
    return result;
  }

  /**
   * Reads bytes into an existing buffer.
   *
   * @param buf destination buffer
   * @param off offset in buffer
   * @param len number of bytes to read
   * @throws IOException if fewer than len bytes remain
   */
  void receive(byte[] buf, int off, int len) throws IOException {
    if (pos + len > data.length) {
      throw new IOException("PayloadReader: need " + len + " bytes, have " + remaining());
    }
    System.arraycopy(data, pos, buf, off, len);
    pos += len;
  }

  /**
   * Skips a number of bytes.
   *
   * @param len number of bytes to skip
   * @throws IOException if fewer than len bytes remain
   */
  void skip(int len) throws IOException {
    if (pos + len > data.length) {
      throw new IOException("PayloadReader: cannot skip " + len + " bytes, have " + remaining());
    }
    pos += len;
  }

  /**
   * Reads a fixed-length string using the connection encoding.
   *
   * @param len number of bytes to decode
   * @return the decoded string
   * @throws IOException if an error occurs
   */
  String receiveString(int len) throws IOException {
    if (pos + len > data.length) {
      throw new IOException("PayloadReader: need " + len + " bytes for string, have " + remaining());
    }
    String result = encoding.decode(data, pos, len);
    pos += len;
    return result;
  }

  /**
   * Reads a null-terminated string.
   *
   * @return the decoded string (without the null terminator)
   * @throws IOException if no null terminator is found
   */
  String receiveString() throws IOException {
    int start = pos;
    while (pos < data.length && data[pos] != 0) {
      pos++;
    }
    if (pos >= data.length) {
      throw new IOException("PayloadReader: null terminator not found");
    }
    String result = encoding.decode(data, start, pos - start);
    pos++; // skip the null terminator
    return result;
  }

  /**
   * Reads a null-terminated string and returns a canonicalized version.
   *
   * @return the canonicalized string
   * @throws IOException if no null terminator is found
   */
  String receiveCanonicalString() throws IOException {
    int start = pos;
    while (pos < data.length && data[pos] != 0) {
      pos++;
    }
    if (pos >= data.length) {
      throw new IOException("PayloadReader: null terminator not found");
    }
    String result = encoding.decodeCanonicalized(data, start, pos - start);
    pos++; // skip the null terminator
    return result;
  }

  /**
   * Reads a null-terminated string and returns a canonicalized-if-present version.
   *
   * @return the canonicalized string
   * @throws IOException if no null terminator is found
   */
  String receiveCanonicalStringIfPresent() throws IOException {
    int start = pos;
    while (pos < data.length && data[pos] != 0) {
      pos++;
    }
    if (pos >= data.length) {
      throw new IOException("PayloadReader: null terminator not found");
    }
    String result = encoding.decodeCanonicalizedIfPresent(data, start, pos - start);
    pos++; // skip the null terminator
    return result;
  }

  /**
   * Returns the underlying data array for direct access (e.g., for error string decoding
   * that requires package-private constructors in org.postgresql.core).
   *
   * @return the payload byte array
   */
  byte[] getData() {
    return data;
  }

  /**
   * Returns the current read position.
   *
   * @return current offset
   */
  int getPos() {
    return pos;
  }

  /**
   * Advances the position by len bytes (for use after external reads from getData()).
   *
   * @param len bytes to advance
   * @throws IOException if not enough bytes remain
   */
  void advance(int len) throws IOException {
    if (pos + len > data.length) {
      throw new IOException("PayloadReader: cannot advance " + len + " bytes, have " + remaining());
    }
    pos += len;
  }

  /**
   * Reads a DataRow (TupleV3) from the payload. The payload starts after the length field,
   * so the first two bytes are the field count.
   *
   * @return the parsed Tuple
   * @throws IOException if an I/O error occurs
   * @throws OutOfMemoryError if allocation fails
   */
  Tuple receiveTupleV3() throws IOException, OutOfMemoryError, SQLException {
    int nf = receiveInteger2();
    byte[][] answer = new byte[nf][];

    OutOfMemoryError oom = null;
    for (int i = 0; i < nf; i++) {
      int size = receiveInteger4();
      if (size != -1) {
        try {
          answer[i] = new byte[size];
          receive(answer[i], 0, size);
        } catch (OutOfMemoryError oome) {
          oom = oome;
          skip(size);
        }
      }
    }

    if (oom != null) {
      throw oom;
    }

    return new Tuple(answer);
  }
}
