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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.PGStream;
import org.postgresql.core.PGStreamTestSupport;
import org.postgresql.core.ProtocolHardeningMode;
import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;

/**
 * The GSS encryption handshake accepts a server token of 0 to 65532 bytes and completes within
 * 64 round-trips; a token length outside that range, or a 65th round-trip, fails the handshake
 * and marks the connection broken.
 *
 * <p>No {@link ProtocolHardeningMode} lifts either limit, so the tests that reach a limit run
 * in every mode. The server tokens come from a {@link FakeSocket}, and the context is a
 * {@link ScriptedGssContext}, so no Kerberos realm is involved.</p>
 */
class GssEncActionNegotiateTest {
  private static final int MAX_TOKEN_LENGTH = 65532;

  private static PGStream stream(FakeSocket socket, ProtocolHardeningMode mode) {
    PGStream stream = PGStreamTestSupport.openStream(socket);
    PGStreamTestSupport.setProtocolHardeningMode(stream, mode);
    return stream;
  }

  private static GssEncAction action(PGStream stream) {
    return new GssEncAction(stream, null, "localhost", "user", "postgres", false, false, false);
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aHandshakeOf64RoundTripsIsEstablished(ProtocolHardeningMode mode) throws Exception {
    Wire server = new Wire();
    for (int i = 0; i < 63; i++) {
      server.int4(1).int1('t');
    }
    FakeSocket socket = new FakeSocket(server.toBytes());
    PGStream stream = stream(socket, mode);
    ScriptedGssContext context = new ScriptedGssContext(64);

    Exception result = action(stream).negotiate(context);

    assertAll(
        () -> assertNull(result, "negotiate result"),
        () -> assertEquals(64, context.initSecContextCalls(), "initSecContext calls"),
        () -> assertTrue(stream.isGssEncrypted(), "isGssEncrypted()"),
        () -> assertFalse(stream.isClosed(), "isClosed()"));
  }

  /**
   * The server answers every token with a zero-length one, which is a valid continuation, so
   * only the round-trip limit ends the handshake.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aHandshakeStillOpenAfter64RoundTripsIsRefusedAndBreaksTheConnection(
      ProtocolHardeningMode mode) throws Exception {
    Wire server = new Wire();
    for (int i = 0; i < 64; i++) {
      server.int4(0);
    }
    FakeSocket socket = new FakeSocket(server.toBytes());
    PGStream stream = stream(socket, mode);
    ScriptedGssContext context = new ScriptedGssContext(ScriptedGssContext.NEVER);

    Exception result = action(stream).negotiate(context);

    PSQLException e = assertInstanceOf(PSQLException.class, result, "negotiate result");
    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. GSS encryption handshake did not complete within {0} round-trips.",
                "64"),
            e.getMessage()),
        () -> assertEquals(64, context.initSecContextCalls(), "initSecContext calls"),
        () -> assertFalse(stream.isGssEncrypted(), "isGssEncrypted()"),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }

  @Test
  void aZeroLengthTokenReachesTheContextAsAnEmptyToken() throws Exception {
    FakeSocket socket = new FakeSocket(new Wire().int4(0).toBytes());
    PGStream stream = PGStreamTestSupport.openStream(socket);
    ScriptedGssContext context = new ScriptedGssContext(2);

    Exception result = action(stream).negotiate(context);

    assertAll(
        () -> assertNull(result, "negotiate result"),
        () -> assertEquals(2, context.initSecContextCalls(), "initSecContext calls"),
        () -> assertArrayEquals(new byte[0], context.inputTokens.get(1), "second input token"),
        () -> assertTrue(stream.isGssEncrypted(), "isGssEncrypted()"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aTokenOf65532BytesReachesTheContext(ProtocolHardeningMode mode) throws Exception {
    FakeSocket socket = new FakeSocket(
        new Wire().int4(MAX_TOKEN_LENGTH).bytes(MAX_TOKEN_LENGTH).toBytes());
    PGStream stream = stream(socket, mode);
    ScriptedGssContext context = new ScriptedGssContext(2);

    Exception result = action(stream).negotiate(context);

    assertAll(
        () -> assertNull(result, "negotiate result"),
        () -> assertArrayEquals(new Wire().bytes(MAX_TOKEN_LENGTH).toBytes(),
            context.inputTokens.get(1), "second input token"),
        () -> assertTrue(stream.isGssEncrypted(), "isGssEncrypted()"));
  }

  /**
   * The server sends the length alone. A handshake that went on to read the body would fail on
   * end of stream with a different exception.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aTokenLengthOf65533IsRefusedBeforeTheBodyIsRead(ProtocolHardeningMode mode) {
    assertTokenLengthRefused(MAX_TOKEN_LENGTH + 1, mode);
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aNegativeTokenLengthIsRefused(ProtocolHardeningMode mode) {
    assertTokenLengthRefused(-1, mode);
  }

  private static void assertTokenLengthRefused(int length, ProtocolHardeningMode mode) {
    FakeSocket socket = new FakeSocket(new Wire().int4(length).toBytes());
    PGStream stream = stream(socket, mode);
    ScriptedGssContext context = new ScriptedGssContext(ScriptedGssContext.NEVER);

    IOException e = assertThrowsExactly(IOException.class,
        () -> action(stream).negotiate(context));

    assertAll(
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "GSSEncryptionHandshakeToken", String.valueOf(length), "0", "65532"),
            e.getMessage()),
        () -> assertEquals(1, context.initSecContextCalls(), "initSecContext calls"),
        () -> PGStreamTestSupport.assertBroken(stream, socket));
  }
}
