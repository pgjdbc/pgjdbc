/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.AuthMethod;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;

/**
 * Covers the {@code requireAuth} check the driver makes on AuthenticationOk, where what is left
 * to rule out is a server that authenticated the connection without asking the client for
 * anything.
 */
class CheckAuthenticationCompletedTest {

  /** requireAuth is met by an authentication exchange the client completed, whatever it was. */
  @ParameterizedTest
  @EnumSource(AuthMethod.class)
  void aCompletedExchangeMeetsRequireAuth(AuthMethod method) throws Exception {
    EnumSet<AuthMethod> allowed = EnumSet.of(method);

    ConnectionFactoryImpl.checkAuthenticationCompleted(allowed, true, false);
    ConnectionFactoryImpl.checkAuthenticationCompleted(allowed, true, true);
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
      assertDoesNotThrow(
          () -> ConnectionFactoryImpl.checkAuthenticationCompleted(allowed, false, false));
      return;
    }

    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.checkAuthenticationCompleted(allowed, false, false));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }

  /**
   * Establishing GSS encryption authenticates the client, so the server has no reason to send an
   * AuthenticationGSS request, and requireAuth=gss holds all the same.
   */
  @Test
  void gssEncryptionMeetsRequireAuthGssWithoutAnExchange() throws Exception {
    ConnectionFactoryImpl.checkAuthenticationCompleted(EnumSet.of(AuthMethod.GSS), false, true);
  }

  /** GSS encryption stands in for a GSS request only, not for whatever else was required. */
  @Test
  void gssEncryptionDoesNotMeetRequireAuthForAnotherMethod() {
    EnumSet<AuthMethod> allowed = EnumSet.of(AuthMethod.SCRAM_SHA_256, AuthMethod.OAUTH);

    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.checkAuthenticationCompleted(allowed, false, true));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }

  /**
   * A GSS-encrypted connection that then authenticated with OAUTHBEARER or SCRAM meets a
   * requireAuth naming that method: the encryption does not have to be named as well.
   */
  @ParameterizedTest
  @EnumSource(value = AuthMethod.class, names = {"OAUTH", "SCRAM_SHA_256", "PASSWORD", "MD5"})
  void anExchangeOverGssEncryptionDoesNotAlsoRequireGss(AuthMethod method) throws Exception {
    ConnectionFactoryImpl.checkAuthenticationCompleted(EnumSet.of(method), true, true);
  }

  /**
   * The refusal names {@code none} as what would allow the connection. Its quotes reach the user
   * only if they are doubled in the source, since GT.tr runs every message through MessageFormat.
   */
  @Test
  void theRefusalNamesNoneAsWhatWouldAllowTheConnection() {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> ConnectionFactoryImpl.checkAuthenticationCompleted(
            EnumSet.of(AuthMethod.SCRAM_SHA_256), false, false));

    assertTrue(ex.getMessage().contains("'none'"), ex.getMessage());
  }

  /** With requireAuth unset there is nothing to check, however the connection was authenticated. */
  @Test
  void anUnsetRequireAuthAllowsEveryOutcome() throws Exception {
    ConnectionFactoryImpl.checkAuthenticationCompleted(null, false, false);
    ConnectionFactoryImpl.checkAuthenticationCompleted(null, false, true);
    ConnectionFactoryImpl.checkAuthenticationCompleted(null, true, false);
  }
}
