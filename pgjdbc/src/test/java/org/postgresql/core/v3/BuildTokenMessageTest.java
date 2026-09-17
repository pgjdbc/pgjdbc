/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

class BuildTokenMessageTest {
  @Test
  void validToken() throws PSQLException {
    byte[] message = OAuthAuthenticator.buildTokenMessage("abc123-._~+/=".toCharArray());
    assertEquals("n,,\u0001auth=Bearer abc123-._~+/=\u0001\u0001",
        new String(message, StandardCharsets.UTF_8));
  }

  @Test
  void nullToken() throws PSQLException {
    assertArrayEquals("n,,\u0001auth=\u0001\u0001".getBytes(StandardCharsets.UTF_8),
        OAuthAuthenticator.buildTokenMessage(null));
  }

  @Test
  void emptyToken() throws PSQLException {
    assertArrayEquals("n,,\u0001auth=\u0001\u0001".getBytes(StandardCharsets.UTF_8),
        OAuthAuthenticator.buildTokenMessage("".toCharArray()));
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "has space",
      "tab\there",
      "newline\nhere",
      "soh\u0001here",
      "comma,here",
      "at@sign",
      "quote\"here",
      "back\\slash",
  })
  void invalidToken(String token) {
    PSQLException ex = assertThrows(PSQLException.class,
        () -> OAuthAuthenticator.buildTokenMessage(token.toCharArray()));
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }
}
