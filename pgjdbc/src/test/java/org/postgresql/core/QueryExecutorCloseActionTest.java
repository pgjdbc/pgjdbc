/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.postgresql.core.PGStreamCloseTest.assertInputClosed;

import org.postgresql.core.CloseRecordingSocketFactory.Output;

import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * {@link QueryExecutorCloseAction#close()} sends the Terminate message and then closes the
 * {@link PGStream}, input stream and socket included, even when sending Terminate throws.
 *
 * <p>On a broken stream it writes nothing and still closes the stream. When both steps throw, the
 * Terminate failure is thrown and the close failure is suppressed.</p>
 */
class QueryExecutorCloseActionTest {
  /** A Terminate message: type {@code 'X'} and a length of 4, which counts the length field. */
  private static final byte[] TERMINATE = {'X', 0, 0, 0, 4};

  @Test
  void aCloseSendsTerminateAndClosesTheStream() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();

    new QueryExecutorCloseAction(stream).close();

    assertAll(
        () -> assertArrayEquals(TERMINATE, sockets.written.toByteArray(), "bytes written"),
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses, "socket closes"));
  }

  /** Pairs with {@link #aCloseSendsTerminateAndClosesTheStream}; only setBroken() differs. */
  @Test
  void aCloseOfABrokenStreamWritesNothing() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();
    stream.setBroken();

    new QueryExecutorCloseAction(stream).close();

    assertAll(
        () -> assertArrayEquals(new byte[0], sockets.written.toByteArray(), "bytes written"),
        () -> assertEquals(0, sockets.flushes, "socket output flushes"));
  }

  /** {@link PGStream#setBroken()} closes the socket too, so the count starts after it. */
  @Test
  void aCloseOfABrokenStreamClosesTheInputAndTheSocket() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.WORKS, false);
    PGStream stream = sockets.primedStream();
    stream.setBroken();
    int closesBefore = sockets.socketCloses;

    new QueryExecutorCloseAction(stream).close();

    assertAll(
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses - closesBefore,
            "socket closes by QueryExecutorCloseAction.close()"));
  }

  @Test
  void aFailingTerminateStillClosesTheInputAndTheSocket() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.FAILS, false);
    PGStream stream = sockets.primedStream();
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    assertThrows(IOException.class, action::close, "close()");

    assertAll(
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses, "socket closes"));
  }

  @Test
  void anUncheckedTerminateFailureStillClosesTheInputAndTheSocket() throws IOException {
    CloseRecordingSocketFactory sockets =
        new CloseRecordingSocketFactory(Output.FAILS_UNCHECKED, false);
    PGStream stream = sockets.primedStream();
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    assertThrows(IllegalStateException.class, action::close, "close()");

    assertAll(
        () -> assertInputClosed(stream),
        () -> assertEquals(1, sockets.socketCloses, "socket closes"));
  }

  /**
   * The flush of Terminate throws {@code Broken pipe}; the close that follows flushes the same
   * bytes again and throws {@code Socket closed}.
   */
  @Test
  void theTerminateFailureIsThrownAndTheCloseFailureSuppressed() throws IOException {
    CloseRecordingSocketFactory sockets = new CloseRecordingSocketFactory(Output.FAILS, false);
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(sockets.primedStream());

    IOException e = assertThrows(IOException.class, action::close, "close()");

    assertAll(
        () -> assertEquals("Broken pipe", e.getMessage(), "thrown"),
        () -> assertEquals(1, e.getSuppressed().length, "suppressed count"),
        () -> assertEquals("Socket closed", e.getSuppressed()[0].getMessage(), "suppressed"));
  }
}
