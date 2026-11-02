/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.util.GT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * A closed {@link VisibleBufferedInputStream} throws {@link IOException} from every method that
 * reads, even with bytes still in its buffer, and leaves the buffer array and the read position
 * as they were.
 *
 * <p>The buffered bytes belong to a connection that is gone. {@code readRaw} does not check, and
 * returns the bytes an earlier {@code ensureBytes} made available, so it is not in
 * {@link Reader}. A new method that reads belongs in {@link Reader}.</p>
 */
class VisibleBufferedInputStreamCloseTest {
  /** Bytes the wrapped stream serves; the zero byte keeps {@code scanCStringLength} in the buffer. */
  private static final byte[] DATA = {1, 2, 0, 4, 5, 6, 7, 8};

  /** Every public method that reads, except {@code readRaw}. */
  enum Reader {
    READ,
    /** Reads fewer bytes than are buffered, so it goes through {@code ensureBytes}. */
    READ_ARRAY,
    /**
     * Reads at least 1024 bytes more than are buffered, so it copies the buffer and then reads the
     * wrapped stream without going through {@code ensureBytes}.
     */
    READ_ARRAY_LARGE,
    PEEK,
    READ_INT2,
    READ_INT4,
    ENSURE_BYTES,
    /** Requests no bytes, so it never reaches the wrapped stream. */
    ENSURE_BYTES_ZERO,
    ENSURE_BYTES_NON_BLOCKING,
    /** Finds the zero byte in the buffer, so it never reaches the wrapped stream. */
    SCAN_C_STRING_LENGTH,
    SKIP,
    AVAILABLE;

    void read(VisibleBufferedInputStream in) throws IOException {
      switch (this) {
        case READ:
          in.read();
          break;
        case READ_ARRAY:
          in.read(new byte[4], 0, 4);
          break;
        case READ_ARRAY_LARGE:
          in.read(new byte[2048], 0, 2048);
          break;
        case PEEK:
          in.peek();
          break;
        case READ_INT2:
          in.readInt2();
          break;
        case READ_INT4:
          in.readInt4();
          break;
        case ENSURE_BYTES:
          in.ensureBytes(1);
          break;
        case ENSURE_BYTES_ZERO:
          in.ensureBytes(0);
          break;
        case ENSURE_BYTES_NON_BLOCKING:
          in.ensureBytes(1, false);
          break;
        case SCAN_C_STRING_LENGTH:
          in.scanCStringLength();
          break;
        case SKIP:
          in.skip(1);
          break;
        case AVAILABLE:
          in.available();
          break;
        default:
          throw new AssertionError("Reader." + this + " calls no method");
      }
    }
  }

  /** Counts calls to {@link #close()}. */
  private static final class CloseCounting extends ByteArrayInputStream {
    int closes;

    CloseCounting() {
      super(DATA);
    }

    @Override
    public void close() {
      closes++;
    }
  }

  /** Wraps {@code wrapped} and buffers all of {@link #DATA}, so the buffer is full at the close. */
  private static VisibleBufferedInputStream primed(CloseCounting wrapped) throws IOException {
    VisibleBufferedInputStream in = new VisibleBufferedInputStream(wrapped, 1024);
    in.ensureBytes(DATA.length);
    return in;
  }

  @ParameterizedTest
  @EnumSource(Reader.class)
  void aReadAfterCloseIsRefused(Reader reader) throws IOException {
    VisibleBufferedInputStream in = primed(new CloseCounting());
    in.close();

    IOException e = assertThrows(IOException.class, () -> reader.read(in), reader.name());
    // Tests run under user.language=TR, so the expected text comes from the message catalog
    assertEquals(GT.tr("Stream is closed."), e.getMessage(), reader.name());
  }

  /** The same calls as {@link #aReadAfterCloseIsRefused} succeed on the same stream left open. */
  @ParameterizedTest
  @EnumSource(Reader.class)
  void aReadBeforeCloseSucceeds(Reader reader) throws IOException {
    VisibleBufferedInputStream in = primed(new CloseCounting());

    reader.read(in);
  }

  /**
   * {@code PGStream} calls {@link VisibleBufferedInputStream#ensureBytes(int)} and then reads
   * {@link VisibleBufferedInputStream#getBuffer()} at {@link VisibleBufferedInputStream#getIndex()},
   * and {@code Connection.close()} may run on another thread in between.
   */
  @Test
  void aCloseLeavesTheArrayAndTheIndexUnchanged() throws IOException {
    VisibleBufferedInputStream in = primed(new CloseCounting());
    in.read();
    in.read();
    byte[] array = in.getBuffer();

    in.close();

    assertAll(
        () -> assertSame(array, in.getBuffer(), "getBuffer()"),
        () -> assertEquals(2, in.getIndex(), "getIndex()"));
  }

  @Test
  void closingClosesTheWrappedStream() throws IOException {
    CloseCounting wrapped = new CloseCounting();
    VisibleBufferedInputStream in = primed(wrapped);

    in.close();

    assertEquals(1, wrapped.closes, "wrapped.close() calls");
  }

  @Test
  void closingTwiceClosesTheWrappedStreamOnce() throws IOException {
    CloseCounting wrapped = new CloseCounting();
    VisibleBufferedInputStream in = primed(wrapped);

    in.close();
    in.close();

    assertEquals(1, wrapped.closes, "wrapped.close() calls");
  }
}
