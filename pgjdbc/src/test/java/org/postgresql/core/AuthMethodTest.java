/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Pins the three places an authentication method has to be named: the enum, the parser that
 * reads {@code requireAuth}, and the choices {@code requireAuth} advertises. A constant added to
 * {@link AuthMethod} that is missed in either of the other two fails here.
 */
class AuthMethodTest {

  /** The name {@code requireAuth} uses for a method, as {@code SCRAM_SHA_256} is scram-sha-256. */
  private static String requireAuthName(AuthMethod method) {
    return method.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  @ParameterizedTest
  @EnumSource(AuthMethod.class)
  void everyMethodIsParsedFromItsRequireAuthName(AuthMethod method) throws Exception {
    assertEquals(method, AuthMethod.fromString(requireAuthName(method)));
  }

  @ParameterizedTest
  @EnumSource(AuthMethod.class)
  void everyMethodIsOfferedByRequireAuthInBothForms(AuthMethod method) {
    List<String> choices = Arrays.asList(PGProperty.REQUIRE_AUTH.getChoices());
    String name = requireAuthName(method);

    assertTrue(choices.contains(name), name + " is missing from the requireAuth choices " + choices);
    assertTrue(choices.contains("!" + name),
        "!" + name + " is missing from the requireAuth choices " + choices);
  }

  @Test
  void requireAuthOffersNothingBeyondTheMethods() {
    List<String> choices = Arrays.asList(PGProperty.REQUIRE_AUTH.getChoices());

    assertEquals(2 * AuthMethod.values().length, choices.size(),
        "requireAuth should offer each method and its negation, and nothing else: " + choices);
  }

  @Test
  void anUnknownMethodIsRejected() {
    PSQLException ex = assertThrows(PSQLException.class, () -> AuthMethod.fromString("oauth2"));

    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), ex.getSQLState());
  }
}
