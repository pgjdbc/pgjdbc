/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.postgresql.core.PGStreamTestSupport.assertBroken;
import static org.postgresql.core.PGStreamTestSupport.openStream;

import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * {@link PGStream#receiveMessageType()} reads a type byte only at the position where
 * {@link PGStream#endMessage()} closed the last message, or where
 * {@link PGStream#markMessageBoundary()} or {@link PGStream#changeSocket} recorded a boundary.
 * Anywhere else it throws and marks the stream broken, in every {@link ProtocolHardeningMode}.
 *
 * <p>The error names the cause: a body left partly unread, a message read to its end or past it
 * without {@code endMessage}, or a position away from the last boundary with no message open.
 * {@link PGStream#receiveChar()} reads a byte without the check. Every stream here reads from a
 * byte array through a fake socket, so no server is involved.</p>
 */
class PGStreamMessageBoundaryTest {
  // Message ids copied verbatim from PGStream, so each assertion pins which of the three errors
  // was thrown while surviving a translation refresh.
  private static final String BODY_UNREAD =
      "Protocol error. Reading the {0} message stopped with {1} bytes of its body unread, so the connection is no longer positioned on a message boundary.";
  private static final String NOT_CLOSED =
      "Protocol error. The {0} message was read without a closing endMessage call, which is a pgjdbc defect.";
  private static final String AWAY_FROM_BOUNDARY =
      "Protocol error. The stream is {0} bytes away from the end of the {1} message, so the next byte is not a message type. Read every backend message through readMessageLength or readFixedMessageLength, and close it with endMessage.";

  /** A ParameterStatus of 12 bytes, 8 of them body, followed by a ReadyForQuery. */
  private static Wire parameterStatusThenReadyForQuery() {
    return new Wire().int1('S').int4(12).bytes(8).int1('Z').int4(5).int1('I');
  }

  /** Reads one whole message through the bounded API and returns its type. */
  private static int readClosedMessage(PGStream stream, String name) throws IOException {
    int type = stream.receiveMessageType();
    int length = stream.readMessageLength(name, 4, 100);
    stream.receive(length - 4);
    stream.endMessage();
    return type;
  }

  @Test
  void messagesEachClosedWithEndMessageAreReadOneAfterAnother() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire()
        .int1('S').int4(12).bytes(8)
        .int1('n').int4(4)
        .int1('Z').int4(5).int1('I')
        .toBytes()));

    int first = readClosedMessage(stream, "ParameterStatus");
    int second = readClosedMessage(stream, "NoData");
    int third = readClosedMessage(stream, "ReadyForQuery");

    assertAll(
        () -> assertEquals('S', (char) first, "ParameterStatus type"),
        () -> assertEquals('n', (char) second, "NoData type"),
        () -> assertEquals('Z', (char) third, "ReadyForQuery type"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  static Stream<Arguments> bodiesLeftPartlyUnreadInEveryMode() {
    return inEveryMode(Stream.of(
        argumentSet("no body byte read", 0, "8"),
        argumentSet("7 of 8 body bytes read", 7, "1")));
  }

  @ParameterizedTest
  @MethodSource("bodiesLeftPartlyUnreadInEveryMode")
  void aTypeReadWithTheBodyPartlyUnreadBreaksTheStream(int bytesRead, String unread,
      ProtocolHardeningMode mode) throws IOException {
    FakeSocket socket = new FakeSocket(parameterStatusThenReadyForQuery().toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.receiveMessageType();
    stream.readMessageLength("ParameterStatus", 4, 100);
    stream.receive(bytesRead);

    IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveMessageType);

    assertAll(
        () -> assertEquals(GT.tr(BODY_UNREAD, "ParameterStatus", unread), e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  static Stream<Arguments> bodiesReadToTheEndOrPastItInEveryMode() {
    return inEveryMode(Stream.of(
        argumentSet("the whole body read", 8),
        argumentSet("one byte past the body read", 9)));
  }

  @ParameterizedTest
  @MethodSource("bodiesReadToTheEndOrPastItInEveryMode")
  void aTypeReadWithTheMessageStillOpenBreaksTheStream(int bytesRead, ProtocolHardeningMode mode)
      throws IOException {
    FakeSocket socket = new FakeSocket(parameterStatusThenReadyForQuery().toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.receiveMessageType();
    stream.readMessageLength("ParameterStatus", 4, 100);
    stream.receive(bytesRead);

    IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveMessageType);

    assertAll(
        () -> assertEquals(GT.tr(NOT_CLOSED, "ParameterStatus"), e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /** The two bytes are read with {@code receive}, outside any message. */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aTypeReadPastTheEndOfTheLastClosedMessageBreaksTheStream(ProtocolHardeningMode mode)
      throws IOException {
    FakeSocket socket = new FakeSocket(parameterStatusThenReadyForQuery().toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    readClosedMessage(stream, "ParameterStatus");
    stream.receive(2);

    IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveMessageType);

    assertAll(
        () -> assertEquals(GT.tr(AWAY_FROM_BOUNDARY, "2", "ParameterStatus"), e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /**
   * A length read with {@code readUntrackedLength} opens no message, so its 4 bytes and the 3
   * payload bytes after it leave the stream 7 bytes from the start, where no message has closed
   * yet.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aTypeReadAfterAnUntrackedPayloadWithNoBoundaryMarkedBreaksTheStream(
      ProtocolHardeningMode mode) throws IOException {
    FakeSocket socket = new FakeSocket(new Wire().int4(3).bytes(3).int1('Z').toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.readUntrackedLength("GSSEncryptionHandshakeToken", 0, 100);
    stream.receive(3);

    IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveMessageType);

    assertAll(
        () -> assertEquals(GT.tr(AWAY_FROM_BOUNDARY, "7", "preceding"), e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void endMessageWithNoMessageOpenRecordsNoBoundary() throws IOException {
    FakeSocket socket = new FakeSocket(parameterStatusThenReadyForQuery().toBytes());
    PGStream stream = openStream(socket);
    readClosedMessage(stream, "ParameterStatus");
    stream.receive(2);
    stream.endMessage();

    IOException e = assertThrowsExactly(ProtocolViolationException.class, stream::receiveMessageType);

    assertEquals(GT.tr(AWAY_FROM_BOUNDARY, "2", "ParameterStatus"), e.getMessage());
  }

  @Test
  void receiveCharReadsAByteAwayFromAMessageBoundary() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int1('a').int1('b').toBytes()));
    stream.receiveChar();

    assertAll(
        () -> assertEquals('b', (char) stream.receiveChar(), "second receiveChar()"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  @Test
  void markMessageBoundaryLetsTheNextTypeBeReadAtTheCurrentPosition() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int1('N').int1('Z').toBytes()));
    stream.receiveChar();

    stream.markMessageBoundary();

    assertAll(
        () -> assertEquals('Z', (char) stream.receiveMessageType(), "receiveMessageType()"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /**
   * The ParameterStatus closed on the old socket ends 13 bytes in, while the replacement input
   * stream counts its position from zero.
   */
  @Test
  void changeSocketRecordsABoundaryAtTheStartOfTheNewStream() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int1('S').int4(12).bytes(8).toBytes()));
    readClosedMessage(stream, "ParameterStatus");

    stream.changeSocket(new FakeSocket(new Wire().int1('Z').int4(5).int1('I').toBytes()));

    assertAll(
        () -> assertEquals('Z', (char) stream.receiveMessageType(), "receiveMessageType()"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /** Repeats each named case once per {@link ProtocolHardeningMode}, passing the mode last. */
  private static Stream<Arguments> inEveryMode(Stream<Arguments.ArgumentSet> cases) {
    return cases.flatMap(c -> Arrays.stream(ProtocolHardeningMode.values()).map(mode -> {
      Object[] args = Arrays.copyOf(c.get(), c.get().length + 1);
      args[args.length - 1] = mode;
      return argumentSet(c.getName() + ", " + mode, args);
    }));
  }
}
