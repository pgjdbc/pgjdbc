/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.core.PGStreamTestSupport.openStream;

import org.postgresql.test.util.FakeSocket;

import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * {@link QueryExecutorCloseAction#close()} sends a Terminate message and closes the socket of a
 * healthy stream, and releases the socket of a broken stream without sending any byte still
 * buffered for it.
 *
 * <p>A request written into a {@link PGStream} stays in its send buffer until a flush, and
 * {@link PGStream#close()} flushes that buffer. The tests leave part of a request unflushed and
 * read what reached the socket output.</p>
 */
class QueryExecutorCloseActionTest {
  private static final byte[] TERMINATE = {'X', 0, 0, 0, 4};

  /**
   * Leaves the header of a Query message in the send buffer, as a request interrupted mid-write
   * would.
   */
  private static void writeUnflushedRequestHeader(PGStream stream) throws IOException {
    stream.sendChar('Q');
    stream.sendInteger4(100);
  }

  /**
   * Checks the setup the broken-stream tests rely on: the stream reports itself closed while its
   * socket is still open, because the close inside markBroken failed.
   */
  private static void assertBrokenWithOpenSocket(PGStream stream, FakeSocket socket) {
    assertAll(
        () -> assertTrue(stream.isClosed(), "stream isClosed() after markBroken"),
        () -> assertFalse(socket.isClosed(), "socket closed after markBroken"));
  }

  @Test
  void aBrokenStreamWhoseSocketCloseFailedIsReleasedWithoutSendingTheUnflushedRequest()
      throws IOException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    writeUnflushedRequestHeader(stream);
    socket.failingCloses = 1;
    stream.markBroken(new IOException("broken by the test"));
    assertBrokenWithOpenSocket(stream, socket);
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    action.close();

    assertAll(
        () -> assertTrue(socket.isClosed(), "socket closed"),
        () -> assertArrayEquals(new byte[0], socket.written(), "bytes sent to the socket"));
  }

  /**
   * The socket {@link PGStream#markBroken(Throwable)} closed is not closed a second time.
   */
  @Test
  void aBrokenStreamWhoseSocketIsClosedSendsNothingAndLeavesTheSocketAlone() throws IOException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    writeUnflushedRequestHeader(stream);
    stream.markBroken(new IOException("broken by the test"));
    assertTrue(socket.isClosed(), "socket closed by markBroken");
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    action.close();

    assertAll(
        () -> assertEquals(1, socket.closeCalls, "socket close() calls"),
        () -> assertArrayEquals(new byte[0], socket.written(), "bytes sent to the socket"));
  }

  @Test
  void aSocketCloseFailureOnABrokenStreamIsThrown() {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    socket.failingCloses = 2;
    stream.markBroken(new IOException("broken by the test"));
    assertBrokenWithOpenSocket(stream, socket);
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    IOException e = assertThrows(IOException.class, action::close);

    assertEquals(FakeSocket.CLOSE_FAILURE, e.getMessage(), "close() exception message");
  }

  /**
   * The socket stays open here, so a Terminate message left in the send buffer would reach it on
   * the flush.
   */
  @Test
  void sendCloseMessageWritesNothingToABrokenStream() throws IOException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    socket.failingCloses = 1;
    stream.markBroken(new IOException("broken by the test"));
    assertBrokenWithOpenSocket(stream, socket);
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    action.sendCloseMessage(stream);
    stream.flush();

    assertArrayEquals(new byte[0], socket.written(), "bytes sent to the socket");
  }

  @Test
  void aHealthyStreamSendsTerminateAndClosesTheSocket() throws IOException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);

    action.close();

    assertAll(
        () -> assertArrayEquals(TERMINATE, socket.written(), "bytes sent to the socket"),
        () -> assertEquals(1, socket.closeCalls, "socket close() calls"));
  }

  @Test
  void aSecondCloseSendsNothingAndLeavesTheSocketAlone() throws IOException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    QueryExecutorCloseAction action = new QueryExecutorCloseAction(stream);
    action.close();

    action.close();

    assertAll(
        () -> assertArrayEquals(TERMINATE, socket.written(), "bytes sent to the socket"),
        () -> assertEquals(1, socket.closeCalls, "socket close() calls"));
  }
}
