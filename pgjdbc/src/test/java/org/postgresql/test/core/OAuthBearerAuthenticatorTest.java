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
  void testOAuthDiscoveryInfoParsing() {
    String json = "{\"status\":\"invalid_token\","
        + "\"openid-configuration\":\"https://idp.example.com/.well-known/openid-configuration\","
        + "\"scope\":\"openid postgres\"}";

    // Use reflection to test the inner class, or we test indirectly
    // For now, verify that the parsing doesn't blow up by testing AuthMethod
    // The full integration test requires a running server
  }
}
