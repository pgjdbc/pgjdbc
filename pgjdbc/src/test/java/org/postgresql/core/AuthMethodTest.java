/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;

/**
 * An unset {@code requireAuth} allows every authentication method, and a
 * value that excludes every method refuses all of them. A value cannot mix
 * plain and {@code !} names, name a method twice, name an unknown method, or
 * be written without a method. Commas at the very end of a value are
 * ignored.
 */
class AuthMethodTest {

  @Test
  void anUnsetRequireAuthParsesToNull() throws PSQLException {
    assertNull(AuthMethod.parseRequireAuth(null), "parseRequireAuth(null)");
  }

  @Test
  void aPositiveListAllowsOnlyTheMethodsItNames() throws PSQLException {
    assertEquals(EnumSet.of(AuthMethod.SCRAM_SHA_256, AuthMethod.GSS),
        AuthMethod.parseRequireAuth("scram-sha-256,gss"));
  }

  @Test
  void anExclusionListAllowsEveryMethodItDoesNotName() throws PSQLException {
    EnumSet<AuthMethod> expected = EnumSet.allOf(AuthMethod.class);
    expected.remove(AuthMethod.PASSWORD);
    expected.remove(AuthMethod.MD5);

    assertEquals(expected, AuthMethod.parseRequireAuth("!password,!md5"));
  }

  /**
   * Excluding every method but one leaves exactly that one. Over all runs,
   * each method is checked both when it is the only one allowed and when it
   * is excluded. A method added to the enum later is checked too.
   */
  @ParameterizedTest(name = "every method but {0} excluded")
  @EnumSource(AuthMethod.class)
  void anExclusionListCanLeaveASingleMethod(AuthMethod kept)
      throws PSQLException {
    String allButKept =
        RequireAuthValues.excluding(EnumSet.complementOf(EnumSet.of(kept)));
    EnumSet<AuthMethod> allowed = AuthMethod.parseRequireAuth(allButKept);

    assertEquals(EnumSet.of(kept), allowed,
        "parseRequireAuth(\"" + allButKept + "\")");
    assertDoesNotThrow(() -> AuthMethod.checkAuth(allowed, kept));
    for (AuthMethod other : EnumSet.complementOf(EnumSet.of(kept))) {
      PSQLException e = assertThrows(PSQLException.class,
          () -> AuthMethod.checkAuth(allowed, other), "checkAuth for " + other);
      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(),
          "SQLState of the refusal of " + other);
    }
  }

  @Test
  void surroundingSpaceIsIgnoredInAPositiveList() throws PSQLException {
    assertEquals(EnumSet.of(AuthMethod.PASSWORD, AuthMethod.MD5),
        AuthMethod.parseRequireAuth(" password , md5 "));
  }

  @Test
  void surroundingSpaceIsIgnoredInAnExclusionList() throws PSQLException {
    assertEquals(
        EnumSet.complementOf(EnumSet.of(AuthMethod.PASSWORD, AuthMethod.MD5)),
        AuthMethod.parseRequireAuth(" !password , !md5 "));
  }

  /**
   * Excluding every method must parse to an empty set, not to null.
   * checkAuth allows every method for null, so with null the driver sends a
   * cleartext password to a server that asks for one.
   */
  @Test
  void excludingEveryMethodParsesToAnEmptySet() throws PSQLException {
    EnumSet<AuthMethod> allowed =
        AuthMethod.parseRequireAuth(RequireAuthValues.EVERY_METHOD_EXCLUDED);

    assertEquals(EnumSet.noneOf(AuthMethod.class), allowed,
        "parseRequireAuth(\"" + RequireAuthValues.EVERY_METHOD_EXCLUDED
            + "\")");
  }

  @ParameterizedTest(name = "{0} against an empty allowed set")
  @EnumSource(AuthMethod.class)
  void anEmptyAllowedSetRefusesEveryMethod(AuthMethod method) {
    PSQLException e = assertThrows(PSQLException.class,
        () -> AuthMethod.checkAuth(EnumSet.noneOf(AuthMethod.class), method));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(),
        "SQLState of the refusal");
  }

  @ParameterizedTest(name = "{0} against an unset requireAuth")
  @EnumSource(AuthMethod.class)
  void anUnsetRequireAuthAllowsEveryMethod(AuthMethod method) {
    assertDoesNotThrow(() -> AuthMethod.checkAuth(null, method));
  }

  @ParameterizedTest(name = "requireAuth={0}")
  @ValueSource(strings = {"password,!md5", "!password,md5"})
  void positiveAndNegativeFormsCannotBeMixed(String requireAuth) {
    assertParseError(requireAuth);
  }

  @ParameterizedTest(name = "requireAuth={0}")
  @ValueSource(strings = {"password,password", "!md5,!md5"})
  void aMethodCannotBeNamedTwice(String requireAuth) {
    assertParseError(requireAuth);
  }

  @ParameterizedTest(name = "requireAuth={0}")
  @ValueSource(strings = {"sha-256", "trust"})
  void anUnknownMethodIsRefused(String requireAuth) {
    assertParseError(requireAuth);
  }

  /**
   * Commas at the very end of the value are ignored, and the methods before
   * them are enforced. Users may already have such a value in their
   * connection settings, and refusing it would break their connections.
   */
  @ParameterizedTest(name = "requireAuth={0}")
  @ValueSource(strings = {"password,", "password,,"})
  void commasAtTheEndAreIgnored(String requireAuth) throws PSQLException {
    assertEquals(EnumSet.of(AuthMethod.PASSWORD),
        AuthMethod.parseRequireAuth(requireAuth),
        "parseRequireAuth(\"" + requireAuth + "\")");
  }

  /**
   * A value without a method in it is refused as an invalid value. It names
   * neither methods to allow nor methods to exclude, so it is a mistake, not
   * a request to refuse every method.
   */
  @ParameterizedTest(name = "requireAuth={0}")
  @ValueSource(strings = {"!", ",", ",,"})
  void aValueWithoutAMethodIsRefused(String requireAuth) {
    assertParseError(requireAuth);
  }

  private static void assertParseError(String requireAuth) {
    PSQLException e = assertThrows(PSQLException.class,
        () -> AuthMethod.parseRequireAuth(requireAuth));
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(),
        "SQLState of the parse error");
  }
}
