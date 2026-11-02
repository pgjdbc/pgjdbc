/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

/**
 * Covers the {@code requireAuth} check the driver makes on AuthenticationOk, where what is left
 * to rule out is a server that lets the client in without a finished authentication exchange.
 */
class CheckAuthCompletedTest {

  /** requireAuth is met by an authentication exchange the client completed, whatever it was. */
  @ParameterizedTest
  @EnumSource(AuthMethod.class)
  void aCompletedExchangeMeetsRequireAuth(AuthMethod method) throws Exception {
    EnumSet<AuthMethod> allowed = EnumSet.of(method);

    AuthMethod.checkAuthCompleted(allowed, true, true, false);
    AuthMethod.checkAuthCompleted(allowed, true, true, true);
  }

  /**
   * The server authenticating the connection without asking the client for anything is what
   * requireAuth is there to rule out, unless none is among the methods it allows.
   */
  @ParameterizedTest
  @EnumSource(AuthMethod.class)
  void onlyNoneMeetsRequireAuthWithoutAnExchange(AuthMethod method) {
    EnumSet<AuthMethod> allowed = EnumSet.of(method);

    if (method == AuthMethod.NONE) {
      assertDoesNotThrow(() -> AuthMethod.checkAuthCompleted(allowed, false, false, false));
      return;
    }

    PSQLException ex = assertThrows(PSQLException.class,
        () -> AuthMethod.checkAuthCompleted(allowed, false, false, false));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }

  /**
   * Establishing GSS encryption authenticates the client, so the server has no reason to send an
   * AuthenticationGSS request, and requireAuth=gss holds all the same.
   */
  @Test
  void gssEncryptionMeetsRequireAuthGssWithoutAnExchange() throws Exception {
    AuthMethod.checkAuthCompleted(EnumSet.of(AuthMethod.GSS), false, false, true);
  }

  /**
   * GSS encryption stands in for a GSS request only, not for whatever else was required. The
   * refusal names gss as what would allow the connection, not none, which would also accept
   * trust connections.
   */
  @Test
  void gssEncryptionDoesNotMeetRequireAuthForAnotherMethod() {
    EnumSet<AuthMethod> allowed = EnumSet.of(AuthMethod.SCRAM_SHA_256, AuthMethod.MD5);

    PSQLException ex = assertThrows(PSQLException.class,
        () -> AuthMethod.checkAuthCompleted(allowed, false, false, true));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertTrue(ex.getMessage().contains("GSS-encrypted connection"), ex.getMessage());
    assertTrue(ex.getMessage().contains("'gss'"), ex.getMessage());
    assertFalse(ex.getMessage().contains("none"), ex.getMessage());
  }

  /**
   * As in libpq, GSS encryption with gss allowed also accepts an exchange of another method that
   * the server started and did not finish, as with AuthenticationOk sent before SASLFinal.
   */
  @Test
  void gssEncryptionCoversAnIncompleteExchangeAsInLibpq() throws Exception {
    AuthMethod.checkAuthCompleted(
        EnumSet.of(AuthMethod.GSS, AuthMethod.SCRAM_SHA_256), true, false, true);
  }

  /**
   * A GSS-encrypted connection that then authenticated with, say, SCRAM meets a
   * requireAuth naming that method: the encryption does not have to be named as well.
   */
  @ParameterizedTest
  @EnumSource(value = AuthMethod.class, names = {"SCRAM_SHA_256", "PASSWORD", "MD5"})
  void anExchangeOverGssEncryptionDoesNotAlsoRequireGss(AuthMethod method) throws Exception {
    AuthMethod.checkAuthCompleted(EnumSet.of(method), true, true, true);
  }

  /**
   * The refusal for a server that requested nothing names {@code none} as what would allow the
   * connection. Its quotes reach the user only if they are doubled in the source, since GT.tr
   * runs every message through MessageFormat.
   */
  @Test
  void theRefusalWithoutARequestNamesNone() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> AuthMethod.checkAuthCompleted(
            EnumSet.of(AuthMethod.SCRAM_SHA_256), false, false, false));

    assertTrue(ex.getMessage().contains("without requesting authentication"), ex.getMessage());
    assertTrue(ex.getMessage().contains("'none'"), ex.getMessage());
  }

  /**
   * A server that sends AuthenticationOk before the exchange it requested is complete is refused
   * as well. The message says the exchange was not complete and does not suggest allowing none,
   * which would let a server skip the rest of any exchange and so weaken requireAuth.
   */
  @Test
  void anIncompleteExchangeIsRefusedWithoutSuggestingNone() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> AuthMethod.checkAuthCompleted(
            EnumSet.of(AuthMethod.SCRAM_SHA_256), true, false, false));

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertTrue(ex.getMessage().contains("before the authentication exchange was complete"),
        ex.getMessage());
    assertFalse(ex.getMessage().contains("none"), ex.getMessage());
  }

  /** With requireAuth unset there is nothing to check, however the connection was authenticated. */
  @Test
  void anUnsetRequireAuthAllowsEveryOutcome() throws Exception {
    AuthMethod.checkAuthCompleted(null, false, false, false);
    AuthMethod.checkAuthCompleted(null, false, false, true);
    AuthMethod.checkAuthCompleted(null, true, false, false);
    AuthMethod.checkAuthCompleted(null, true, true, false);
  }
}
