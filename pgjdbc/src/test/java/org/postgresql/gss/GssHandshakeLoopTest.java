/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.CannedSocketFactory;
import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.ietf.jgss.GSSContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.TimeUnit;

/**
 * Answers every GSS token the driver sends with another zero length token. A zero length token is a
 * valid continuation, so the exchange itself never ends the handshake and the client is what has to
 * stop it: the authentication handshake and the encryption handshake both stop after
 * {@link PGStream#MAX_AUTH_ROUND_TRIPS} rounds with a protocol violation and a broken stream, and
 * {@link GssEncAction} also refuses a token whose declared length is over its limit.
 *
 * <p>Each script holds ten more messages than the limit allows, so it is the limit and not the end
 * of the script that ends the loop. A real GSSContext needs a Kerberos realm, so the tests pass a
 * stub context straight to the package-private negotiate method.</p>
 *
 * <p>Each assertion builds its expected text with {@link GT#tr}, the call the driver used, so the
 * tests hold in any locale.</p>
 */
class GssHandshakeLoopTest {

  private static final int MAX_ROUNDS = PGStream.MAX_AUTH_ROUND_TRIPS;

  /**
   * The largest token length {@link GssEncAction} accepts. PostgreSQL's PQ_GSS_AUTH_BUFFER_SIZE
   * of 64 kB counts the 4 length bytes, so the token maximum is four bytes smaller.
   */
  private static final int MAX_HANDSHAKE_TOKEN_SIZE = 64 * 1024 - 4;

  /** The refusal {@link GssEncAction} builds for a token length outside its range. */
  private static String tokenRefusalFor(int declaredLength) {
    return GT.tr("Backend declared a GSS token of {0} bytes, the maximum is {1}.",
        String.valueOf(declaredLength), String.valueOf(MAX_HANDSHAKE_TOKEN_SIZE));
  }

  /** A context that returns a one byte token and never reports itself established. */
  private static GSSContext neverEstablishedContext() {
    InvocationHandler handler = new InvocationHandler() {
      @Override
      public Object invoke(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
          case "initSecContext":
            return new byte[]{1};
          case "isEstablished":
            return Boolean.FALSE;
          default:
            return null;
        }
      }
    };
    return (GSSContext) Proxy.newProxyInstance(GssHandshakeLoopTest.class.getClassLoader(),
        new Class<?>[]{GSSContext.class}, handler);
  }

  private static PGStream streamOf(byte[] script, CannedSocketFactory[] out) throws IOException {
    CannedSocketFactory factory = new CannedSocketFactory(script);
    out[0] = factory;
    return new PGStream(factory, new HostSpec("localhost", 5432), 0, 8192);
  }

  /**
   * Returns {@code count} AuthenticationGSSContinue messages, each carrying the type byte 'R', a
   * declared length of 8 and the authentication code 8. The declared 8 bytes are the length and
   * the code, so no token follows.
   */
  private static byte[] continueMessages(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      out.write('R');
      out.write(new byte[]{0, 0, 0, 8, 0, 0, 0, 8}, 0, 8);
    }
    return out.toByteArray();
  }

  /**
   * Returns {@code count} zero length tokens, each carrying a 4 byte length and no payload. The
   * encryption handshake frames its tokens that way, with no message type byte in front.
   */
  private static byte[] rawTokens(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      out.write(new byte[]{0, 0, 0, 0}, 0, 4);
    }
    return out.toByteArray();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stopsTheAuthenticationHandshakeAtTheRoundCap() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    PGStream stream = streamOf(continueMessages(MAX_ROUNDS + 10), factory);
    GssAction action = new GssAction(stream, null, "localhost", "test", "postgres", false, false,
        false);

    Exception e = action.negotiate(neverEstablishedContext());

    assertNotNull(e, "negotiate must report an error once the round limit is reached");
    assertAll(
        () -> assertEquals(GT.tr("GSS authentication did not complete within {0} round trips.",
            MAX_ROUNDS), e.getMessage()),
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(),
            ((PSQLException) e).getSQLState(), "SQLState of the refusal"),
        () -> assertTrue(stream.isBroken(), "the stream must be marked broken"),
        // Each round sends a GSSResponse: 1 type byte + 4 length bytes + the 1 byte token.
        () -> assertEquals(MAX_ROUNDS * 6, factory[0].getWritten().length,
            "the driver must send one token per round and stop at the limit"));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stopsTheEncryptionHandshakeAtTheRoundCap() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    PGStream stream = streamOf(rawTokens(MAX_ROUNDS + 10), factory);
    GssEncAction action = new GssEncAction(stream, null, "localhost", "test", "postgres", false,
        false, false);

    Exception e = action.negotiate(neverEstablishedContext());

    assertNotNull(e, "negotiate must report an error once the round limit is reached");
    assertAll(
        () -> assertEquals(GT.tr("GSS encryption handshake did not complete within {0} round"
            + " trips.", MAX_ROUNDS), e.getMessage()),
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(),
            ((PSQLException) e).getSQLState(), "SQLState of the refusal"),
        () -> assertTrue(stream.isBroken(), "the stream must be marked broken"),
        // Each round sends 4 length bytes + the 1 byte token, with no message type in front.
        () -> assertEquals(MAX_ROUNDS * 5, factory[0].getWritten().length,
            "the driver must send one token per round and stop at the limit"));
  }

  /**
   * The four script bytes declare a token length of 65536, four above the maximum. The encryption
   * handshake reads that length raw, so its limit is checked there, before any token body is read.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnOversizedHandshakeToken() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    byte[] script = new byte[]{0, 1, 0, 0};
    PGStream stream = streamOf(script, factory);
    GssEncAction action = new GssEncAction(stream, null, "localhost", "test", "postgres", false,
        false, false);

    IOException e = assertThrows(IOException.class,
        () -> action.negotiate(neverEstablishedContext()));

    assertAll(
        () -> assertEquals(tokenRefusalFor(MAX_HANDSHAKE_TOKEN_SIZE + 4), e.getMessage()),
        () -> assertTrue(stream.isBroken(), "the stream must be marked broken"));
  }

  /**
   * The length is read as a signed int4, so the four script bytes can declare a negative length.
   * The same check refuses it, and the refusal quotes that negative length rather than only naming
   * the maximum.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsANegativeHandshakeTokenLength() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    byte[] script = new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    PGStream stream = streamOf(script, factory);
    GssEncAction action = new GssEncAction(stream, null, "localhost", "test", "postgres", false,
        false, false);

    IOException e = assertThrows(IOException.class,
        () -> action.negotiate(neverEstablishedContext()));

    assertAll(
        () -> assertEquals(tokenRefusalFor(-1), e.getMessage()),
        () -> assertTrue(stream.isBroken(), "the stream must be marked broken"));
  }
}
