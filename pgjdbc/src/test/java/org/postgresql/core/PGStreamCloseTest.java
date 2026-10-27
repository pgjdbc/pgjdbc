/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.core.CloseRecordingSocketFactory.Output;
import org.postgresql.util.GT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.stream.Stream;

/**
 * {@link PGStream#close()} closes the output stream, the input stream, and the socket, each even
 * when an earlier one throws, and reports the first failure with the later ones suppressed.
 *
 * <p>On a broken stream or a closed socket the output stream is not closed, since closing it
 * flushes. A second {@code close()} does nothing. Whether the input stream is closed is observed through
 * {@link PGStream#receiveChar()} on a stream with bytes still buffered: it throws
 * {@code Stream is closed.} rather than returning a buffered byte.</p>
 */
class PGStreamCloseTest {
  /** Asserts that reading from {@code stream} throws because its input stream is closed. */
  static void assertInputClosed(PGStream stream) {
    IOException e = assertThrows(IOException.class, stream::receiveChar, "receiveChar()");
    // Tests run under user.language=TR, so the expected text comes from the message catalog
    assertEquals(GT.tr("Stream is closed."), e.getMessage(), "receiveChar()");
  }

  @Test
  void aCloseFlushesTheOutput() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();

    stream.close();

    assertEquals(1, sockets.flushes, "socket output flushes");
  }

  /** Pairs with {@link #aCloseFlushesTheOutput}; the only difference is the setBroken() call. */
  @Test
  void aCloseOfABrokenStreamDoesNotFlushTheOutput() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();
    stream.setBroken();

    stream.close();

    assertEquals(0, sockets.flushes, "socket output flushes");
  }

  /**
   * The JDK closes an {@code SSLSocket} itself when a write to it fails, and the stream is not
   * broken then. Pairs with {@link #aCloseFlushesTheOutput}; only the closed socket differs.
   */
  @Test
  void aCloseOverAClosedSocketDoesNotFlushTheOutput() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();
    sockets.socketClosed = true;

    stream.close();

    assertAll(
        () -> assertEquals(0, sockets.flushes, "socket output flushes"),
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses, "socket closes"));
  }

  /** {@link PGStream#setBroken()} closes the socket too, so the count starts after it. */
  @Test
  void aCloseOfABrokenStreamClosesTheInputAndTheSocket() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();
    stream.setBroken();
    int closesBefore = sockets.socketCloses;

    stream.close();

    assertAll(
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses - closesBefore,
            "socket closes by PGStream.close()"));
  }

  /** {@code FilterOutputStream.close} flushes, which is what fails on a dropped connection. */
  @Test
  void aFailingOutputCloseStillClosesTheInputAndTheSocket() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.FAILS, false);
    PGStream stream = sockets.primedStream();

    assertThrows(IOException.class, stream::close, "close()");

    assertAll(
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses, "socket closes"));
  }

  @Test
  void aFailingInputCloseStillClosesTheSocket() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();
    sockets.inputCloseFails = true;

    IOException e = assertThrows(IOException.class, stream::close, "close()");

    assertAll(
        () -> assertEquals("Input close failed", e.getMessage(), "thrown"),
        () -> assertEquals(1, sockets.socketCloses, "socket closes"));
  }

  @Test
  void anUncheckedOutputFailureStillClosesTheInputAndTheSocket() throws IOException {
    CloseRecordingSocketFactory sockets =
        new CloseRecordingSocketFactory(Output.FAILS_UNCHECKED, false);
    PGStream stream = sockets.primedStream();

    assertThrows(IllegalStateException.class, stream::close, "close()");

    assertAll(
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses, "socket closes"));
  }

  @Test
  void theFirstFailureIsThrownAndTheLaterOneSuppressed() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.FAILS, true);
    PGStream stream = sockets.primedStream();

    IOException e = assertThrows(IOException.class, stream::close, "close()");

    assertAll(
        () -> assertEquals("Broken pipe", e.getMessage(), "thrown"),
        () -> assertEquals(1, e.getSuppressed().length, "suppressed count"),
        () -> assertEquals("Socket close failed", e.getSuppressed()[0].getMessage(),
            "suppressed"));
  }

  @Test
  void aSecondCloseClosesTheSocketNoMore() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();

    stream.close();
    stream.close();

    assertEquals(1, sockets.socketCloses, "socket closes");
  }

  static Stream<Arguments> uncheckedAndIoFailures() {
    return Stream.of(
        Arguments.of(new IOException("Broken pipe")),
        Arguments.of(new IllegalStateException("Broken pipe")),
        // QueryExecutorBase.close() logs an IOException at FINEST, so a wrapped Error would vanish
        Arguments.of(new StackOverflowError()));
  }

  @ParameterizedTest
  @MethodSource("uncheckedAndIoFailures")
  void rethrowThrowsAnIoExceptionOrAnUncheckedThrowableAsItself(Throwable failure) {
    Throwable thrown = assertThrows(Throwable.class, () -> PGStream.rethrow(failure));

    assertSame(failure, thrown, "PGStream.rethrow(" + failure + ")");
  }

  @Test
  void rethrowWrapsAnotherCheckedExceptionInAnIoException() {
    Exception checked = new Exception("checked");

    IOException thrown = assertThrows(IOException.class, () -> PGStream.rethrow(checked));

    assertSame(checked, thrown.getCause(), "PGStream.rethrow(checked).getCause()");
  }

  /**
   * A stream that stores one exception and throws it on every call fails two closes with the same
   * instance, and {@link Throwable#addSuppressed} throws {@link IllegalArgumentException} for
   * {@code this}.
   */
  @Test
  void alsoFailedDoesNotSuppressAFailureUnderItself() {
    IOException failure = new IOException("Broken pipe");

    Throwable kept = PGStream.alsoFailed(failure, failure);

    assertAll(
        () -> assertSame(failure, kept, "PGStream.alsoFailed(failure, failure)"),
        () -> assertEquals(0, failure.getSuppressed().length, "suppressed count"));
  }
}
