/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class OAuthDiscoveryInfoTest {

  @Test
  void testParseFullResponse() {
    String json = "{\"status\":\"invalid_token\","
        + "\"openid-configuration\":\"https://idp.example.com/.well-known/openid-configuration\","
        + "\"scope\":\"openid postgres\"}";

    OAuthBearerAuthenticator.OAuthDiscoveryInfo info =
        OAuthBearerAuthenticator.OAuthDiscoveryInfo.parse(json);

    assertEquals("invalid_token", info.getStatus());
    assertEquals("https://idp.example.com/.well-known/openid-configuration",
        info.getDiscoveryUrl());
    assertEquals("openid postgres", info.getScope());
  }

  @Test
  void testParseMissingFields() {
    String json = "{\"status\":\"invalid_token\"}";

    OAuthBearerAuthenticator.OAuthDiscoveryInfo info =
        OAuthBearerAuthenticator.OAuthDiscoveryInfo.parse(json);

    assertEquals("invalid_token", info.getStatus());
    assertNull(info.getDiscoveryUrl());
    assertNull(info.getScope());
  }

  @Test
  void testParseEmptyJson() {
    String json = "{}";

    OAuthBearerAuthenticator.OAuthDiscoveryInfo info =
        OAuthBearerAuthenticator.OAuthDiscoveryInfo.parse(json);

    assertNull(info.getStatus());
    assertNull(info.getDiscoveryUrl());
    assertNull(info.getScope());
  }

  @Test
  void testParseWithWhitespace() {
    String json = "{ \"status\" : \"invalid_token\" , "
        + "\"openid-configuration\" : \"https://example.com/oidc\" }";

    OAuthBearerAuthenticator.OAuthDiscoveryInfo info =
        OAuthBearerAuthenticator.OAuthDiscoveryInfo.parse(json);

    assertEquals("invalid_token", info.getStatus());
    assertEquals("https://example.com/oidc", info.getDiscoveryUrl());
  }
}
