/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import org.postgresql.core.PGStream;
import org.postgresql.core.PGStreamTestSupport;
import org.postgresql.core.ProtocolHardeningMode;
import org.postgresql.core.ProtocolViolationException;
import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The GSS authentication exchange completes within 64 round-trips, and reads each
 * AuthenticationGSSContinue within 8 to 8008 bytes and each ErrorResponse within 5 to 30000
 * bytes, the limits the startup authentication loop applies. A 65th
 * round-trip or a length outside its range fails the exchange and marks the connection broken.
 *
 * <p>No {@link ProtocolHardeningMode} lifts these limits, so the tests that reach a limit run in
 * every mode. The server messages come from a {@link FakeSocket}, and the context is a
 * {@link ScriptedGssContext}, so no Kerberos realm is involved.</p>
 */
class GssActionNegotiateTest {
  private static final int AUTH_REQ_GSS_CONTINUE = 8;

  private static PGStream stream(FakeSocket socket, ProtocolHardeningMode mode) {
    PGStream stream = PGStreamTestSupport.openStream(socket);
    PGStreamTestSupport.setProtocolHardeningMode(stream, mode);
    return stream;
  }

  private static GssAction action(PGStream stream) {
    return new GssAction(stream, null, "localhost", "user", "postgres", false, false, false);
  }

  /** Appends an AuthenticationGSSContinue that carries a token of {@code tokenLength} bytes. */
  private static Wire gssContinue(Wire wire, int tokenLength) {
    return wire.int1('R').int4(8 + tokenLength).int4(AUTH_REQ_GSS_CONTINUE).bytes(tokenLength);
  }

  /**
   * Returns an ErrorResponse with SQLState 28P01 whose length field is {@code length}, at least
   * 21.
   */
  private static byte[] errorResponse(int length) {
    // Body: 'S' "FATAL\0" (7) + 'C' "28P01\0" (7) + 'M' text "\0" + the final "\0".
    return new Wire()
        .int1('E').int4(length)
        .int1('S').raw("FATAL".getBytes(StandardCharsets.US_ASCII)).int1(0)
        .int1('C').raw("28P01".getBytes(StandardCharsets.US_ASCII)).int1(0)
        .int1('M').bytes(length - 21).int1(0)
        .int1(0)
        .toBytes();
  }

  // Round-trips

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anExchangeOf64RoundTripsIsEstablished(ProtocolHardeningMode mode) throws Exception {
    Wire server = new Wire();
    for (int i = 0; i < 63; i++) {
      gssContinue(server, 1);
    }
    FakeSocket socket = new FakeSocket(server.toBytes());
    PGStream stream = stream(socket, mode);
    ScriptedGssContext context = new ScriptedGssContext(64);

    Exception result = action(stream).negotiate(context);

    assertAll(
        () -> assertNull(result, "negotiate result"),
        () -> assertEquals(64, context.initSecContextCalls(), "initSecContext calls"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /**
   * Every AuthenticationGSSContinue carries an empty token, which is a valid continuation, so
   * only the round-trip limit ends the exchange.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anExchangeStillOpenAfter64RoundTripsIsRefusedAndBreaksTheConnection(
      ProtocolHardeningMode mode) throws Exception {
    Wire server = new Wire();
    for (int i = 0; i < 64; i++) {
      gssContinue(server, 0);
    }
    FakeSocket socket = new FakeSocket(server.toBytes());
    PGStream stream = stream(socket, mode);
    ScriptedGssContext context = new ScriptedGssContext(ScriptedGssContext.NEVER);

    Exception result = action(stream).negotiate(context);

    PSQLException e = assertInstanceOf(PSQLException.class, result, "negotiate result");
    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. GSS authentication did not complete within {0} round-trips.",
                "64"),
            e.getMessage()),
        () -> assertEquals(64, context.initSecContextCalls(), "initSecContext calls"),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }

  // AuthenticationGSSContinue

  @Test
  void aContinueWithNoTokenReachesTheContextAsAnEmptyToken() throws Exception {
    FakeSocket socket = new FakeSocket(gssContinue(new Wire(), 0).toBytes());
    PGStream stream = PGStreamTestSupport.openStream(socket);
    ScriptedGssContext context = new ScriptedGssContext(2);

    Exception result = action(stream).negotiate(context);

    assertAll(
        () -> assertNull(result, "negotiate result"),
        () -> assertEquals(2, context.initSecContextCalls(), "initSecContext calls"),
        () -> assertArrayEquals(new byte[0], context.inputTokens.get(1), "second input token"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aContinueOf8008BytesReachesTheContext(ProtocolHardeningMode mode) throws Exception {
    FakeSocket socket = new FakeSocket(gssContinue(new Wire(), 8000).toBytes());
    PGStream stream = stream(socket, mode);
    ScriptedGssContext context = new ScriptedGssContext(2);

    Exception result = action(stream).negotiate(context);

    assertAll(
        () -> assertNull(result, "negotiate result"),
        () -> assertArrayEquals(new Wire().bytes(8000).toBytes(), context.inputTokens.get(1),
            "second input token"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /**
   * The server sends the header alone, so an exchange that went on to read the token would fail
   * on end of stream with a different exception.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aContinueOf8009BytesIsRefused(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int1('R').int4(8009).toBytes());
    PGStream stream = stream(socket, mode);

    IOException e = assertThrowsExactly(ProtocolViolationException.class,
        () -> action(stream).negotiate(new ScriptedGssContext(ScriptedGssContext.NEVER)));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes applied before authentication. This limit cannot be relaxed.",
                "AuthenticationGSSContinue", "8009", "8008"),
            e.getMessage()),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aContinueShorterThan8BytesIsRefused(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int1('R').int4(7).int4(0).toBytes());
    PGStream stream = stream(socket, mode);

    IOException e = assertThrowsExactly(ProtocolViolationException.class,
        () -> action(stream).negotiate(new ScriptedGssContext(ScriptedGssContext.NEVER)));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "AuthenticationGSSContinue", "7", "8", String.valueOf(PGStream.MAX_MESSAGE_SIZE)),
            e.getMessage()),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }

  // The message boundary

  /**
   * The test reads one byte with {@code receiveChar} before the exchange starts, so the stream
   * sits one byte past the last boundary. An exchange that took the type byte with
   * {@code receiveChar} would read the AuthenticationGSSContinue after that byte and complete.
   */
  @Test
  void anExchangeReadsNoMessageTypeAwayFromAMessageBoundary() throws Exception {
    FakeSocket socket = new FakeSocket(gssContinue(new Wire().int1('x'), 0).toBytes());
    PGStream stream = PGStreamTestSupport.openStream(socket);
    stream.receiveChar();

    IOException e = assertThrowsExactly(ProtocolViolationException.class,
        () -> action(stream).negotiate(new ScriptedGssContext(2)));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. The stream is {0} bytes away from the end of the {1} message, so the next byte is not a message type. Read every backend message through readMessageLength or readFixedMessageLength, and close it with endMessage.",
                "1", "preceding"),
            e.getMessage()),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }

  // ErrorResponse

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anErrorResponseOf30000BytesIsReturned(ProtocolHardeningMode mode) throws Exception {
    FakeSocket socket = new FakeSocket(errorResponse(30000));
    PGStream stream = stream(socket, mode);

    Exception result = action(stream).negotiate(new ScriptedGssContext(ScriptedGssContext.NEVER));

    PSQLException e = assertInstanceOf(PSQLException.class, result, "negotiate result");
    assertAll(
        () -> assertEquals("28P01", e.getSQLState(), "SQLState"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /**
   * The server sends the header alone, so an exchange that went on to read the fields would fail
   * on end of stream with a different exception.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anErrorResponseOf30001BytesIsRefused(ProtocolHardeningMode mode) throws Exception {
    FakeSocket socket = new FakeSocket(new Wire().int1('E').int4(30001).toBytes());
    PGStream stream = stream(socket, mode);

    IOException e = assertThrowsExactly(ProtocolViolationException.class,
        () -> action(stream).negotiate(new ScriptedGssContext(ScriptedGssContext.NEVER)));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes applied before authentication. This limit cannot be relaxed.",
                "ErrorResponse", "30001", "30000"),
            e.getMessage()),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anErrorResponseShorterThan5BytesIsRefused(ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int1('E').int4(4).int1(0).toBytes());
    PGStream stream = stream(socket, mode);

    IOException e = assertThrowsExactly(ProtocolViolationException.class,
        () -> action(stream).negotiate(new ScriptedGssContext(ScriptedGssContext.NEVER)));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "ErrorResponse", "4", "5", String.valueOf(PGStream.MAX_MESSAGE_SIZE)),
            e.getMessage()),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }
}
