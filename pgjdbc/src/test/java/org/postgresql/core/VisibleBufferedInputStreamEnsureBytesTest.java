/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Fails when {@link VisibleBufferedInputStream} accepts a negative {@code ensureBytes} count.
 *
 * <p>A count below zero is refused with an {@link IOException} whose message names the count.
 * Nothing is read from the wrapped stream and the read position does not move. Zero is not
 * refused: {@code ensureBytes(0)} returns {@code true} and reads nothing. For a larger count the
 * method returns {@code true} once the buffer holds that many bytes, and {@code false} at end of
 * stream.</p>
 *
 * <p>Almost every negative count used to be accepted: the method returned {@code true} without
 * reading anything, the caller then decoded with that count, and
 * {@link PGStream#receiveString(int)} failed with {@link StringIndexOutOfBoundsException}. That
 * exception is unchecked, on a path where the driver turns an {@link IOException} into a
 * connection error. A count near {@link Integer#MIN_VALUE} overflowed the count arithmetic
 * instead, and the buffer then doubled once per pass until it raised
 * {@link OutOfMemoryError}.</p>
 */
class VisibleBufferedInputStreamEnsureBytesTest {
  private static final byte[] DATA = {1, 2, 3, 4, 5, 6, 7, 8};

  private static class NoReadStream extends InputStream {
    @Override
    public int read() {
      throw new AssertionError("a refused count must not reach the wrapped stream");
    }

    @Override
    public int read(byte[] b, int off, int len) {
      throw new AssertionError("a refused count must not reach the wrapped stream");
    }
  }

  private static VisibleBufferedInputStream stream(InputStream wrapped) {
    return new VisibleBufferedInputStream(wrapped, 1024);
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, -4, Integer.MIN_VALUE})
  void aNegativeCountIsRefused(int n) {
    VisibleBufferedInputStream in = stream(new NoReadStream());

    IOException e = assertThrows(IOException.class, () -> in.ensureBytes(n),
        "ensureBytes(" + n + ")");
    assertTrue(e.getMessage().contains(String.valueOf(n)),
        "the message names the count, was: " + e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, -4, Integer.MIN_VALUE})
  void aNegativeCountIsRefusedByTheNonBlockingForm(int n) {
    VisibleBufferedInputStream in = stream(new NoReadStream());

    assertThrows(IOException.class, () -> in.ensureBytes(n, false),
        "ensureBytes(" + n + ", false)");
  }

  /**
   * Covers the count that overflows the shortfall arithmetic. {@code ensureBytes} computes
   * {@code n - endIndex + index}, which overflows to a positive value only where the buffer
   * already holds bytes to subtract, so this test fills the buffer first; with the buffer drained
   * the expression is {@code n} itself.
   */
  @ParameterizedTest
  @ValueSource(ints = {-1, -4, Integer.MIN_VALUE})
  void aNegativeCountIsRefusedEvenWhenTheBufferIsFull(int n) throws IOException {
    VisibleBufferedInputStream in = stream(new ByteArrayInputStream(DATA));
    in.ensureBytes(DATA.length);

    assertThrows(IOException.class, () -> in.ensureBytes(n),
        "ensureBytes(" + n + ") with the whole payload buffered");
    assertEquals(DATA[0], in.read(), "the refused count left the read position alone");
  }

  /**
   * Covers the caller the guard was added for. {@code QueryExecutorImpl.receiveCommandStatus}
   * subtracts five from the length the backend declared and passes the result to
   * {@link PGStream#receiveString(int)}. Nothing bounds that length in production: this reader
   * carries a {@code TODO}, and where the readers around it use an {@code assert}, assertions are
   * off without {@code -ea}. {@code QueryExecutorImpl} catches an {@link IOException} from the
   * call and aborts the connection with {@code CONNECTION_FAILURE}. An unchecked exception escapes
   * that recovery, so this test pins the exception type.
   */
  @Test
  void aNegativeLengthFromTheWireIsAnIoException() throws IOException {
    try (PGStream stream = new PGStream(
        new PGStreamSkipTest.FixedSocketFactory(new ByteArrayInputStream(DATA)),
        new HostSpec("localhost", 5432), 0, 8192)) {
      assertThrows(IOException.class, () -> stream.receiveString(-1),
          "receiveString with a length the backend made negative");
    }
  }

  @Test
  void zeroIsAlwaysAvailable() throws IOException {
    assertTrue(stream(new NoReadStream()).ensureBytes(0), "ensureBytes(0) before any read");
    assertTrue(stream(new ByteArrayInputStream(new byte[0])).ensureBytes(0),
        "ensureBytes(0) on an empty stream");
  }

  @Test
  void aCountTheStreamCanMeetIsAnswered() throws IOException {
    VisibleBufferedInputStream in = stream(new ByteArrayInputStream(DATA));

    assertTrue(in.ensureBytes(DATA.length), "ensureBytes for the whole payload");
    assertEquals(DATA[0], in.read(), "read() must return the first buffered byte");
  }

  @Test
  void aCountTheStreamCannotMeetIsReportedAsTheEnd() throws IOException {
    VisibleBufferedInputStream in = stream(new ByteArrayInputStream(DATA));

    assertFalse(in.ensureBytes(DATA.length + 1), "ensureBytes past the end of the stream");
  }
}
