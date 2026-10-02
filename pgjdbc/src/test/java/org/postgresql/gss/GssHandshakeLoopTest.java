/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.gss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.CannedSocketFactory;
import org.postgresql.core.PGStream;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.ietf.jgss.GSSContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * A zero length GSS token is a valid continuation, so a server that answers every token with
 * another never ends the handshake unless the client does. A real GSSContext cannot be
 * built without a Kerberos realm, so these tests pass a stub context straight to the
 * package-private negotiate method.
 */
@Isolated("Uses Locale.setDefault")
class GssHandshakeLoopTest {

  private static final int MAX_ROUNDS = PGStream.MAX_AUTH_ROUND_TRIPS;

  // The assertions match on message text, which GT.tr translates once these strings are
  // localized.
  private static Locale defaultLocale;

  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
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

  /** AuthenticationGSSContinue carrying a zero length token, repeated. */
  private static byte[] continueMessages(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      out.write('R');
      out.write(new byte[]{0, 0, 0, 8, 0, 0, 0, 8}, 0, 8);
    }
    return out.toByteArray();
  }

  /** Raw length-prefixed zero length tokens, which is how the encryption handshake is framed. */
  private static byte[] rawTokens(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      out.write(new byte[]{0, 0, 0, 0}, 0, 4);
    }
    return out.toByteArray();
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stopsTheAuthenticationHandshakeAtTheRoundLimit() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    PGStream stream = streamOf(continueMessages(MAX_ROUNDS + 10), factory);
    GssAction action = new GssAction(stream, null, "localhost", "test", "postgres", false, false,
        false);

    Exception e = action.negotiate(neverEstablishedContext());

    assertNotNull(e, "the loop must end with an error rather than run on");
    assertTrue(e.getMessage().contains("round trips"), e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), ((PSQLException) e).getSQLState());
    assertTrue(stream.isBroken());
    // Each round sends a GSSResponse, the type byte and length followed by a one byte token.
    assertEquals(MAX_ROUNDS * 6, factory[0].getWritten().length,
        "the driver must send exactly the number of tokens the limit allows");
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stopsTheEncryptionHandshakeAtTheRoundLimit() throws Exception {
    CannedSocketFactory[] factory = new CannedSocketFactory[1];
    PGStream stream = streamOf(rawTokens(MAX_ROUNDS + 10), factory);
    GssEncAction action = new GssEncAction(stream, null, "localhost", "test", "postgres", false,
        false, false);

    Exception e = action.negotiate(neverEstablishedContext());

    assertNotNull(e, "the loop must end with an error rather than run on");
    assertTrue(e.getMessage().contains("round trips"), e.getMessage());
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), ((PSQLException) e).getSQLState());
    assertTrue(stream.isBroken());
    // Each round sends a four byte length followed by a one byte token.
    assertEquals(MAX_ROUNDS * 5, factory[0].getWritten().length,
        "the driver must send exactly the number of tokens the limit allows");
  }

  /** The encryption handshake reads a raw length, so its limit is checked there. */
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

    assertTrue(e.getMessage().contains("GSS token"), e.getMessage());
    assertTrue(stream.isBroken());
  }
}
