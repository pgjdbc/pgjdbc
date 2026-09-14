/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Stream;

/**
 * A backend message length outside the range valid for its reader is rejected, and the stream is
 * then unusable: {@link PGStream#isClosed()} returns {@code true} and the socket is closed with
 * SO_LINGER set to zero.
 *
 * <p>An accepted length opens a message, and {@link PGStream#endMessage()} rejects that message
 * when its body was read short or long. The limits pgjdbc picks for server-generated text and for
 * RowDescription are checked after the length is read; only the text limit is switched off by
 * {@link ProtocolHardeningMode#DISABLE}. Every stream here reads from a byte array through a fake
 * socket, so no server is involved.</p>
 */
class PGStreamMessageLengthTest {

  @ParameterizedTest
  @ValueSource(ints = {8, 9, 263, 264})
  void aLengthWithinTheBoundsIsReturned(int length) throws IOException {
    FakeSocket socket = new FakeSocket(new Wire().int4(length).toBytes());
    PGStream stream = openStream(socket);

    assertEquals(length, stream.readMessageLength("BackendKeyData", 8, 264));
    assertFalse(stream.isClosed(), "isClosed() after an accepted length");
  }

  static Stream<Arguments> lengthsOutsideTheBoundsInEveryMode() {
    return Stream.of(7, 265, 0, -1, Integer.MIN_VALUE)
        .flatMap(length -> Arrays.stream(ProtocolHardeningMode.values())
            .map(mode -> arguments(length, mode)));
  }

  @ParameterizedTest
  @MethodSource("lengthsOutsideTheBoundsInEveryMode")
  void aLengthOutsideTheBoundsBreaksTheStream(int length, ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int4(length).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.readMessageLength("BackendKeyData", 8, 264));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "BackendKeyData", String.valueOf(length), "8", "264"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /**
   * The two-argument overload bounds the length by the backend's {@code MaxAllocSize},
   * 1073741823 bytes.
   */
  @Test
  void theLargestProtocolMessageLengthIsAccepted() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(1073741823).toBytes()));

    assertEquals(1073741823, stream.readMessageLength("CopyData", 4));
  }

  @Test
  void aLengthOneOverTheLargestProtocolMessageIsRejected() {
    FakeSocket socket = new FakeSocket(new Wire().int4(1073741824).toBytes());
    PGStream stream = openStream(socket);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.readMessageLength("CopyData", 4));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "CopyData", "1073741824", "4", "1073741823"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void aBodyReadToItsDeclaredLengthClosesTheMessage() throws IOException {
    FakeSocket socket = new FakeSocket(new Wire().int4(12).bytes(8).int1('Z').toBytes());
    PGStream stream = openStream(socket);

    stream.readMessageLength("ParameterStatus", 4, 100);
    stream.receive(8);
    stream.endMessage();

    assertAll(
        () -> assertEquals('Z', stream.receiveChar(), "byte after the message"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  @Test
  void aMessageWithAnEmptyBodyClosesWithoutReadingAnything() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(4).toBytes()));

    stream.readMessageLength("ParseComplete", 4, 4);
    stream.endMessage();

    assertFalse(stream.isClosed(), "isClosed()");
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aBodyReadShortIsRejected(ProtocolHardeningMode mode) throws IOException {
    FakeSocket socket = new FakeSocket(new Wire().int4(12).bytes(8).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    stream.readMessageLength("ParameterStatus", 4, 100);
    stream.receive(7);
    IOException e = assertThrowsExactly(IOException.class, stream::endMessage);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has {1} unread bytes.", "ParameterStatus", "1"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aBodyReadPastItsDeclaredLengthIsRejected(ProtocolHardeningMode mode) throws IOException {
    FakeSocket socket = new FakeSocket(new Wire().int4(12).bytes(9).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    stream.readMessageLength("ParameterStatus", 4, 100);
    stream.receive(9);
    IOException e = assertThrowsExactly(IOException.class, stream::endMessage);

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message was read {1} bytes past its declared length.",
                "ParameterStatus", "1"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void endMessageWithNoOpenMessageChecksNothing() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().bytes(4).toBytes()));

    stream.receive(4);
    stream.endMessage();

    assertFalse(stream.isClosed(), "isClosed()");
  }

  /**
   * A second {@code endMessage} after a successful one checks nothing, even when more bytes were
   * read in between.
   */
  @Test
  void endMessageClosesTheMessageItChecked() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(8).bytes(8).toBytes()));

    stream.readMessageLength("BackendKeyData", 4, 100);
    stream.receive(4);
    stream.endMessage();
    stream.receive(4);
    stream.endMessage();

    assertFalse(stream.isClosed(), "isClosed()");
  }

  /**
   * The second {@code endMessage} returns; it would throw again if the failed check had left the
   * message open.
   */
  @Test
  void endMessageClosesTheMessageEvenWhenTheCheckFails() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(8).bytes(4).toBytes()));

    stream.readMessageLength("BackendKeyData", 4, 100);
    assertThrowsExactly(IOException.class, stream::endMessage);

    stream.endMessage();
  }

  /**
   * The replacement input stream counts its position from zero, so a message opened on the old
   * stream is discarded, and {@code endMessage} afterwards checks nothing.
   */
  @Test
  void changeSocketDiscardsTheOpenMessage() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(100).bytes(96).toBytes()));

    stream.readMessageLength("ParameterStatus", 4, 100);
    stream.changeSocket(new FakeSocket(new byte[0]));
    stream.endMessage();

    assertFalse(stream.isClosed(), "isClosed() after changeSocket and endMessage");
  }

  @Test
  void anUntrackedLengthOpensNoMessage() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(8).bytes(8).toBytes()));

    assertEquals(8, stream.readUntrackedLength("GSS token", 0, 100));
    stream.endMessage();

    assertFalse(stream.isClosed(), "isClosed() after endMessage with the token body unread");
  }

  @Test
  void anUntrackedLengthDiscardsTheMessageOpenBeforeIt() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(12).int4(4).bytes(4).toBytes()));

    stream.readMessageLength("Outer", 4, 100);
    stream.readUntrackedLength("GSS token", 0, 100);
    stream.endMessage();

    assertFalse(stream.isClosed(), "isClosed() after endMessage with 4 bytes of Outer unread");
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 101})
  void anUntrackedLengthOutsideTheBoundsBreaksTheStream(int length) {
    FakeSocket socket = new FakeSocket(new Wire().int4(length).toBytes());
    PGStream stream = openStream(socket);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.readUntrackedLength("GSS token", 0, 100));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "GSS token", String.valueOf(length), "0", "100"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /**
   * One call to a length reader of {@link PGStream}, with the bounds the test passes.
   */
  @FunctionalInterface
  interface BoundedRead {
    int read(PGStream stream) throws IOException;
  }

  static Stream<Arguments> boundsOutsideTheReaderRules() {
    int max = PGStream.MAX_MESSAGE_SIZE;
    return Stream.of(
        arguments("readMessageLength(name, 3)", (BoundedRead) s -> s.readMessageLength("M", 3),
            3, max, 4),
        arguments("readMessageLength(name, 3, 100)",
            (BoundedRead) s -> s.readMessageLength("M", 3, 100), 3, 100, 4),
        arguments("readPreAuthMessageLength(name, 3, 100)",
            (BoundedRead) s -> s.readPreAuthMessageLength("M", 3, 100), 3, max, 4),
        arguments("readUntrackedLength(name, -1, 100)",
            (BoundedRead) s -> s.readUntrackedLength("M", -1, 100), -1, 100, 0),
        arguments("readMessageLength(name, 41, 40)",
            (BoundedRead) s -> s.readMessageLength("M", 41, 40), 41, 40, 4),
        arguments("readUntrackedLength(name, 41, 40)",
            (BoundedRead) s -> s.readUntrackedLength("M", 41, 40), 41, 40, 0),
        arguments("readMessageLength(name, 4, MAX_MESSAGE_SIZE + 1)",
            (BoundedRead) s -> s.readMessageLength("M", 4, max + 1), 4, max + 1, 4),
        arguments("readUntrackedLength(name, 0, MAX_MESSAGE_SIZE + 1)",
            (BoundedRead) s -> s.readUntrackedLength("M", 0, max + 1), 0, max + 1, 0));
  }

  /**
   * Bounds outside the rules of the reader are a defect in the calling driver code, not in the
   * backend, so the reader throws before it reads the length and leaves the stream usable. The
   * length on the wire, 40, lies within every pair of bounds a mutant could still accept.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("boundsOutsideTheReaderRules")
  void boundsOutsideTheReaderRulesAreRefusedBeforeAnyByteIsRead(String call, BoundedRead reader,
      int minLength, int maxLength, int lowestValid) throws IOException {
    FakeSocket socket = new FakeSocket(new Wire().int4(40).toBytes());
    PGStream stream = openStream(socket);

    IllegalArgumentException e = assertThrowsExactly(IllegalArgumentException.class,
        () -> reader.read(stream));
    assertAll(
        () -> assertEquals(
            GT.tr("{0} length bounds {1}..{2} must lie within {3}..{4}",
                "M", String.valueOf(minLength), String.valueOf(maxLength),
                String.valueOf(lowestValid), String.valueOf(PGStream.MAX_MESSAGE_SIZE)),
            e.getMessage()),
        () -> assertFalse(stream.isClosed(), "isClosed()"),
        () -> assertFalse(socket.closed, "socket closed"),
        () -> assertEquals(40, stream.receiveInteger4(), "length field, still unread"));
  }

  static Stream<Arguments> boundsOnTheEdgeOfTheReaderRules() {
    int max = PGStream.MAX_MESSAGE_SIZE;
    return Stream.of(
        arguments("readMessageLength(name, 4)", (BoundedRead) s -> s.readMessageLength("M", 4), 4),
        arguments("readPreAuthMessageLength(name, 4, 100)",
            (BoundedRead) s -> s.readPreAuthMessageLength("M", 4, 100), 4),
        arguments("readUntrackedLength(name, 0, 100)",
            (BoundedRead) s -> s.readUntrackedLength("M", 0, 100), 0),
        arguments("readMessageLength(name, 40, 40)",
            (BoundedRead) s -> s.readMessageLength("M", 40, 40), 40),
        arguments("readUntrackedLength(name, 40, 40)",
            (BoundedRead) s -> s.readUntrackedLength("M", 40, 40), 40),
        arguments("readMessageLength(name, 4, MAX_MESSAGE_SIZE)",
            (BoundedRead) s -> s.readMessageLength("M", 4, max), 4),
        arguments("readUntrackedLength(name, 0, MAX_MESSAGE_SIZE)",
            (BoundedRead) s -> s.readUntrackedLength("M", 0, max), 0));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("boundsOnTheEdgeOfTheReaderRules")
  void boundsOnTheEdgeOfTheReaderRulesAreAccepted(String call, BoundedRead reader, int length)
      throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(length).toBytes()));

    assertEquals(length, reader.read(stream), call);
  }

  /**
   * The message is proven open by {@code endMessage} rejecting the unread body.
   */
  @Test
  void aPreAuthLengthAtTheLimitOpensTheMessage() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(100).toBytes()));

    assertEquals(100, stream.readPreAuthMessageLength("AuthenticationRequest", 8, 100));
    IOException e = assertThrowsExactly(IOException.class, stream::endMessage);

    assertEquals(
        GT.tr("Protocol error. {0} message has {1} unread bytes.", "AuthenticationRequest", "96"),
        e.getMessage());
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aPreAuthLengthOverTheLimitIsRejectedAsNotRelaxable(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int4(101).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.readPreAuthMessageLength("AuthenticationRequest", 8, 100));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes applied before authentication. This limit cannot be relaxed.",
                "AuthenticationRequest", "101", "100"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void aPreAuthLengthBelowTheMinimumIsRejectedAsInvalid() {
    FakeSocket socket = new FakeSocket(new Wire().int4(7).toBytes());
    PGStream stream = openStream(socket);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.readPreAuthMessageLength("AuthenticationRequest", 8, 100));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "AuthenticationRequest", "7", "8", "1073741823"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void aFixedLengthThatMatchesOpensTheMessage() throws IOException {
    PGStream stream = openStream(new FakeSocket(new Wire().int4(8).toBytes()));

    stream.readFixedMessageLength("ReadyForQuery", 8);
    IOException e = assertThrowsExactly(IOException.class, stream::endMessage);

    assertEquals(
        GT.tr("Protocol error. {0} message has {1} unread bytes.", "ReadyForQuery", "4"),
        e.getMessage());
  }

  static Stream<Arguments> otherFixedLengthsInEveryMode() {
    return Stream.of(7, 9)
        .flatMap(length -> Arrays.stream(ProtocolHardeningMode.values())
            .map(mode -> arguments(length, mode)));
  }

  @ParameterizedTest
  @MethodSource("otherFixedLengthsInEveryMode")
  void aFixedLengthThatDiffersBreaksTheStream(int length, ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int4(length).toBytes());
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.readFixedMessageLength("ReadyForQuery", 8));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, expected {2}.",
                "ReadyForQuery", String.valueOf(length), "8"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void aFreshStreamIsNotClosed() {
    FakeSocket socket = new FakeSocket(new byte[0]);
    PGStream stream = openStream(socket);

    assertAll(
        () -> assertFalse(stream.isClosed(), "isClosed()"),
        () -> assertFalse(stream.isSocketClosed(), "isSocketClosed()"),
        () -> assertFalse(socket.lingerSet, "SO_LINGER set"));
  }

  @Test
  void markBrokenReturnsItsArgumentAndClosesTheSocketWithoutLinger() {
    FakeSocket socket = new FakeSocket(new byte[0]);
    PGStream stream = openStream(socket);
    IOException reason = new IOException("reason");

    assertSame(reason, stream.markBroken(reason));
    assertAll(
        () -> assertBroken(stream, socket),
        () -> assertTrue(stream.isSocketClosed(), "isSocketClosed()"));
  }

  @Test
  void markBrokenClosesTheSocketWhenSoLingerIsRefused() {
    FakeSocket socket = new FakeSocket(new byte[0]);
    socket.refuseSoLinger = true;
    PGStream stream = openStream(socket);

    stream.markBroken(new IOException("reason"));

    assertAll(
        () -> assertTrue(stream.isClosed(), "isClosed()"),
        () -> assertTrue(socket.closed, "socket closed"));
  }

  /**
   * The descriptor stays open when {@code close()} fails, so {@code isSocketClosed()} still
   * returns {@code false} for the regular close path to act on.
   */
  @Test
  void aStreamWhoseSocketFailsToCloseIsStillClosed() {
    FakeSocket socket = new FakeSocket(new byte[0]);
    socket.failingCloses = Integer.MAX_VALUE;
    PGStream stream = openStream(socket);

    stream.markBroken(new IOException("reason"));

    assertAll(
        () -> assertTrue(stream.isClosed(), "isClosed()"),
        () -> assertFalse(stream.isSocketClosed(), "isSocketClosed()"));
  }

  @Test
  void aServerTextMessageAtTheDefaultLimitIsAccepted() throws IOException {
    PGStream stream = openStream(new FakeSocket(new byte[0]));
    stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);

    stream.checkServerTextMessageSize("ErrorResponse", 64000000);

    assertAll(
        () -> assertEquals(64000000, stream.getMaxServerTextMessageSize()),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  @Test
  void aServerTextMessageOverTheDefaultLimitBreaksTheStream() {
    FakeSocket socket = new FakeSocket(new byte[0]);
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.checkServerTextMessageSize("ErrorResponse", 64000001));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes. Raise the {3} connection property if the backend legitimately sends more, or set -D{4}=disable to skip these limits altogether.",
                "ErrorResponse", "64000001", "64000000", "maxServerTextMessageSize",
                "pgjdbc.protocolHardeningMode"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void disableSwitchesTheServerTextMessageLimitOff() throws IOException {
    FakeSocket socket = new FakeSocket(new byte[0]);
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);

    stream.checkServerTextMessageSize("ErrorResponse", 64000001);

    assertAll(
        () -> assertFalse(stream.isClosed(), "isClosed()"),
        () -> assertFalse(socket.closed, "socket closed"));
  }

  @Test
  void aConfiguredServerTextMessageLimitReplacesTheDefault() throws Exception {
    PGStream stream = openStream(new FakeSocket(new byte[0]));
    stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    stream.setMaxServerTextMessageSize("1000");

    stream.checkServerTextMessageSize("NoticeResponse", 1000);
    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.checkServerTextMessageSize("NoticeResponse", 1001));

    assertAll(
        () -> assertEquals(1000, stream.getMaxServerTextMessageSize()),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes. Raise the {3} connection property if the backend legitimately sends more, or set -D{4}=disable to skip these limits altogether.",
                "NoticeResponse", "1001", "1000", "maxServerTextMessageSize",
                "pgjdbc.protocolHardeningMode"),
            e.getMessage()));
  }

  @Test
  void aNullServerTextMessageLimitRestoresTheDefault() throws Exception {
    PGStream stream = openStream(new FakeSocket(new byte[0]));
    stream.setMaxServerTextMessageSize("1000");

    stream.setMaxServerTextMessageSize(null);

    assertEquals(64000000, stream.getMaxServerTextMessageSize());
  }

  @Test
  void aRowDescriptionAtTheLimitIsAccepted() throws IOException {
    PGStream stream = openStream(new FakeSocket(new byte[0]));

    stream.checkRowDescriptionSize(8388608);

    assertFalse(stream.isClosed(), "isClosed()");
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aRowDescriptionOverTheLimitBreaksTheStream(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new byte[0]);
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class,
        () -> stream.checkRowDescriptionSize(8388609));
    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. RowDescription message has length {0}, which exceeds the pgjdbc limit of {1} bytes.",
                "8388609", "8388608"),
            e.getMessage()),
        () -> assertBroken(stream, socket));
  }
}
