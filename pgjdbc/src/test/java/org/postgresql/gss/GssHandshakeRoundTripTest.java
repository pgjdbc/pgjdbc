/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.test.util.PgWire.concat;
import static org.postgresql.test.util.PgWire.int4;
import static org.postgresql.test.util.PgWire.message;

import org.postgresql.core.PGStream;
import org.postgresql.test.gss.MockGSSContext;
import org.postgresql.test.util.InMemorySocketFactory;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.ietf.jgss.MessageProp;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Fails when either GSS token exchange runs past its round-trip cap, or stops one exchange
 * early. Neither exchange ends on its own: a zero-length token is a legal continuation, so a
 * server that keeps sending them keeps the client in the loop, and it does so before any
 * credentials have been exchanged.
 *
 * <p>The context is faked rather than negotiated, since a real one needs a Kerberos realm.
 * That is what {@code negotiate} is separated from {@code run} for.</p>
 */
class GssHandshakeRoundTripTest {

  private static final int MAX_HANDSHAKE_ITERATIONS = 64;

  private final MessageProp messageProp = new MessageProp(0, true);

  /**
   * A context that answers every token with an empty one, and reports itself established
   * only after {@code establishAfter} exchanges.
   */
  private static class ScriptedGSSContext extends MockGSSContext {
    private final int establishAfter;
    int initCalls;

    ScriptedGSSContext(MessageProp messageProp, int establishAfter) {
      super(42, messageProp);
      this.establishAfter = establishAfter;
    }

    @Override
    public byte[] initSecContext(byte[] inputBuf, int offset, int len) {
      initCalls++;
      return new byte[0];
    }

    @Override
    public boolean isEstablished() {
      return initCalls >= establishAfter;
    }
  }

  private static ScriptedGSSContext neverEstablishes(MessageProp messageProp) {
    return new ScriptedGSSContext(messageProp, Integer.MAX_VALUE);
  }

  private static PGStream streamReading(byte[] script) throws IOException {
    return new PGStream(
        new InMemorySocketFactory(script), new HostSpec("localhost", 1), 0, 8192);
  }

  /** Repeats {@code reply} more times than the cap allows, so only the cap can end the loop. */
  private static byte[] repeat(byte[] reply, int times) {
    byte[] script = new byte[0];
    for (int i = 0; i < times; i++) {
      script = concat(script, reply);
    }
    return script;
  }

  /**
   * AuthenticationGSSContinue: the 4-byte self-inclusive length, the request type, then the
   * token. Type 8 with nothing after it is the empty continuation.
   */
  private static byte[] gssContinue() {
    return message('R', int4(8));
  }

  private static void assertRoundTripCapReached(Exception thrown, PGStream pgStream,
      ScriptedGSSContext secContext) {
    PSQLException failure = assertInstanceOf(PSQLException.class, thrown);
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), failure.getSQLState());
    assertTrue(failure.getMessage().contains(String.valueOf(MAX_HANDSHAKE_ITERATIONS)),
        "the failure should quote the cap it enforces: " + failure.getMessage());
    assertEquals(MAX_HANDSHAKE_ITERATIONS, secContext.initCalls,
        "the client should offer exactly as many tokens as the cap allows");
    assertTrue(pgStream.isClosed(),
        "a handshake that ran out of round-trips must not leave a usable stream");
  }

  private static GssEncAction encAction(PGStream pgStream) {
    return new GssEncAction(pgStream, null, "localhost", "test", "postgres", false, true, false);
  }

  private static GssAction authAction(PGStream pgStream) {
    return new GssAction(pgStream, null, "localhost", "test", "postgres", false, true, false);
  }

  @Test
  void encryptionHandshakeStopsAfterTheRoundTripCap() throws Exception {
    // The encryption handshake token is length-prefixed and unframed: four bytes of length,
    // then that many bytes. A length of zero is a valid empty token.
    PGStream pgStream = streamReading(repeat(int4(0), MAX_HANDSHAKE_ITERATIONS + 4));
    ScriptedGSSContext secContext = neverEstablishes(messageProp);

    Exception thrown = encAction(pgStream).negotiate(secContext);

    assertNotNull(thrown, "an unbounded handshake must be reported, not accepted");
    assertRoundTripCapReached(thrown, pgStream, secContext);
  }

  @Test
  void authenticationHandshakeStopsAfterTheRoundTripCap() throws Exception {
    // The same exchange one layer up, inside a single iteration of the authentication loop
    // in ConnectionFactoryImpl -- which is why that loop's own cap does not bound this one.
    PGStream pgStream = streamReading(repeat(gssContinue(), MAX_HANDSHAKE_ITERATIONS + 4));
    ScriptedGSSContext secContext = neverEstablishes(messageProp);

    Exception thrown = authAction(pgStream).negotiate(secContext);

    assertNotNull(thrown, "an unbounded handshake must be reported, not accepted");
    assertRoundTripCapReached(thrown, pgStream, secContext);
  }

  @Test
  void authenticationHandshakeCompletesWithinTheCap() throws Exception {
    // The control: a context established on the second exchange leaves the loop on its own.
    // Without it, a cap that fired one round-trip too early would pass the case above.
    PGStream pgStream = streamReading(gssContinue());
    ScriptedGSSContext secContext = new ScriptedGSSContext(messageProp, 2);

    assertNull(authAction(pgStream).negotiate(secContext),
        "an established context should end the exchange without a failure");
    assertEquals(2, secContext.initCalls);
    assertFalse(pgStream.isClosed(), "a completed handshake must leave the stream usable");
  }
}
