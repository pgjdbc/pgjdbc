/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

class BuildTokenMessageTest {
  static Stream<Arguments> validTokens() {
    return Stream.of(
        argumentSet("token using every permitted character",
            "abc123-._~+/=", "n,,\u0001auth=Bearer abc123-._~+/=\u0001\u0001"),
        argumentSet("token of a single character",
            "a", "n,,\u0001auth=Bearer a\u0001\u0001"),
        argumentSet("token with two padding characters",
            "abc==", "n,,\u0001auth=Bearer abc==\u0001\u0001"));
  }

  @ParameterizedTest
  @MethodSource("validTokens")
  void validToken(String token, String expected) throws PSQLException {
    byte[] message = OAuthAuthenticator.buildTokenMessage(token.toCharArray());
    assertEquals(expected, new String(message, StandardCharsets.UTF_8));
  }

  @Test
  void nullToken() throws PSQLException {
    byte[] message = OAuthAuthenticator.buildTokenMessage(null);
    assertEquals("n,,\u0001auth=\u0001\u0001", new String(message, StandardCharsets.UTF_8));
  }

  @Test
  void emptyToken() throws PSQLException {
    byte[] message = OAuthAuthenticator.buildTokenMessage("".toCharArray());
    assertEquals("n,,\u0001auth=\u0001\u0001", new String(message, StandardCharsets.UTF_8));
  }

  static Stream<Arguments> invalidTokens() {
    return Stream.of(
        argumentSet("token containing a space", "has space"),
        argumentSet("token containing a tab", "tab\there"),
        argumentSet("token containing a newline", "newline\nhere"),
        argumentSet("token containing SOH", "soh\u0001here"),
        argumentSet("token containing a comma", "comma,here"),
        argumentSet("token containing an at sign", "at@sign"),
        argumentSet("token containing a double quote", "quote\"here"),
        argumentSet("token containing a backslash", "back\\slash"),
        argumentSet("padding with no preceding character", "="),
        argumentSet("padding character in the middle", "abc=def"),
        argumentSet("token containing a Latin-1 character", "töken"),
        argumentSet("token containing a character above U+00FF", "Ābc"));
  }

  @ParameterizedTest
  @MethodSource("invalidTokens")
  void invalidToken(String token) {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> OAuthAuthenticator.buildTokenMessage(token.toCharArray()));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }
}
