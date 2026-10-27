/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.util.ByteStreamWriter;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Sends a payload of a length fixed up front as a sequence of CopyData messages, none of them
 * longer than a given limit.
 *
 * <p>A message is opened when its first byte is written, so a payload of zero bytes sends no
 * message, and a payload that is a multiple of the limit ends without an empty message. Writing
 * more bytes than the length given to the constructor throws {@link IOException} before any of
 * the extra bytes is sent. Call {@link #finish()} once the payload is written: it pads with zeros
 * whatever the length promised and nobody wrote, so the last message header stays true.</p>
 *
 * <p>The stream does not flush and does not close the {@link PGStream}.</p>
 */
final class CopyDataOutputStream extends OutputStream {
  /**
   * Largest payload, in bytes, that the driver sends in one CopyData message: 64 KiB short of
   * 1 GiB ({@value #MAX_PAYLOAD_LENGTH} bytes). PostgreSQL 14 and later close the connection when
   * the length field of a CopyData message, which counts its own 4 bytes, exceeds
   * {@code MaxAllocSize - 1} (1073741822). PostgreSQL 13 and earlier fail the COPY with
   * {@code out of memory} when the payload reaches {@code MaxAllocSize} (1073741823 bytes).
   */
  static final int MAX_PAYLOAD_LENGTH = (1 << 30) - 64 * 1024;

  private final PGStream pgStream;
  private final int length;
  private final int maxPayloadLength;
  /** Bytes the open message still expects. */
  private int leftInMessage;
  /** Bytes promised by the constructor that no message header has covered yet. */
  private int leftToOpen;

  /**
   * Creates a stream that sends {@code length} bytes to {@code pgStream}.
   *
   * @param pgStream the connection to write the messages to
   * @param length number of payload bytes the caller will write and {@link #finish()} will pad to
   * @param maxPayloadLength largest payload of one message, in bytes
   * @throws IllegalArgumentException if {@code length} is negative or {@code maxPayloadLength} is
   *     not positive
   */
  CopyDataOutputStream(PGStream pgStream, int length, int maxPayloadLength) {
    if (length < 0) {
      throw new IllegalArgumentException("length must be non-negative, got " + length);
    }
    if (maxPayloadLength <= 0) {
      throw new IllegalArgumentException(
          "maxPayloadLength must be positive, got " + maxPayloadLength);
    }
    this.pgStream = pgStream;
    this.length = length;
    this.maxPayloadLength = maxPayloadLength;
    this.leftToOpen = length;
  }

  /**
   * Returns how many of the promised bytes have not been written yet.
   *
   * @return the number of bytes {@link #finish()} would pad
   */
  int remaining() {
    return leftInMessage + leftToOpen;
  }

  @Override
  public void write(int b) throws IOException {
    verifyAllowed(1);
    openMessageIfNeeded();
    pgStream.sendChar(b);
    leftInMessage--;
  }

  @Override
  public void write(byte[] buf, int off, int len) throws IOException {
    if (off < 0 || len < 0 || len > buf.length - off) {
      throw new IndexOutOfBoundsException(
          "off=" + off + ", len=" + len + ", buf.length=" + buf.length);
    }
    verifyAllowed(len);
    while (len > 0) {
      openMessageIfNeeded();
      int chunk = Math.min(len, leftInMessage);
      pgStream.send(buf, off, chunk);
      leftInMessage -= chunk;
      off += chunk;
      len -= chunk;
    }
  }

  /**
   * Sends the promised number of bytes of {@code data} starting at {@code off}, padded with zeros
   * where the array ends first, as {@link PGStream#send(byte[], int, int)} does for a single
   * message.
   *
   * @param data the source of the payload
   * @param off index of the first byte to send
   * @throws IOException if an I/O error occurs
   * @throws IndexOutOfBoundsException if {@code off} is negative or past the end of {@code data}
   */
  void writeAndFinish(byte[] data, int off) throws IOException {
    write(data, off, Math.min(remaining(), data.length - off));
    finish();
  }

  /**
   * Sends the bytes {@code writer} writes and pads them to the promised length, as
   * {@link PGStream#send(ByteStreamWriter)} does for a single message.
   *
   * @param writer the source of the payload
   * @throws IOException if an I/O error occurs, if {@code writer} throws, or if it writes more than
   *     the promised length
   */
  void writeAndFinish(ByteStreamWriter writer) throws IOException {
    try {
      writer.writeTo(() -> this);
    } catch (IOException ioe) {
      throw ioe;
    } catch (Exception re) {
      throw new IOException("Error writing bytes to stream", re);
    }
    finish();
  }

  /**
   * Sends zeros for every promised byte that has not been written, opening as many messages as
   * that takes.
   *
   * @throws IOException if an I/O error occurs
   */
  void finish() throws IOException {
    while (remaining() > 0) {
      openMessageIfNeeded();
      pgStream.sendZeros(leftInMessage);
      leftInMessage = 0;
    }
  }

  private void verifyAllowed(int wanted) throws IOException {
    if (remaining() < wanted) {
      throw new IOException("Attempt to write more than the specified " + length + " bytes");
    }
  }

  private void openMessageIfNeeded() throws IOException {
    if (leftInMessage > 0) {
      return;
    }
    int size = Math.min(leftToOpen, maxPayloadLength);
    pgStream.sendChar(PgMessageType.COPY_DATA);
    pgStream.sendInteger4(size + 4);
    leftInMessage = size;
    leftToOpen -= size;
  }
}
