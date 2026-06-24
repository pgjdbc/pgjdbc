/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.AuthMethod;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

class OAuthBearerAuthenticatorTest {

  @Test
  void testAuthMethodFromStringOAuth() throws PSQLException {
    assertEquals(AuthMethod.OAUTH, AuthMethod.fromString("oauth"));
  }

  @Test
  void testParseRequireAuthWithOAuth() throws PSQLException {
    EnumSet<AuthMethod> methods = AuthMethod.parseRequireAuth("oauth");
    assertNotNull(methods);
    assertTrue(methods.contains(AuthMethod.OAUTH));
    assertEquals(1, methods.size());
  }

  @Test
  void testParseRequireAuthWithOAuthAndScram() throws PSQLException {
    EnumSet<AuthMethod> methods = AuthMethod.parseRequireAuth("oauth,scram-sha-256");
    assertNotNull(methods);
    assertTrue(methods.contains(AuthMethod.OAUTH));
    assertTrue(methods.contains(AuthMethod.SCRAM_SHA_256));
    assertEquals(2, methods.size());
  }

  @Test
  void testParseRequireAuthRejectOAuth() throws PSQLException {
    EnumSet<AuthMethod> methods = AuthMethod.parseRequireAuth("!oauth");
    assertNotNull(methods);
    assertTrue(!methods.contains(AuthMethod.OAUTH));
  }

  @Test
  void testParseRequireAuthWithoutOauthDoesNotIncludeIt() throws PSQLException {
    EnumSet<AuthMethod> methods = AuthMethod.parseRequireAuth("scram-sha-256");
    assertNotNull(methods);
    assertTrue(!methods.contains(AuthMethod.OAUTH),
        "oauth should not be present when only scram-sha-256 is specified");
    assertEquals(1, methods.size());
  }
}
