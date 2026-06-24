/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

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

  @Test
  void testParseEscapedQuoteInValue() {
    // Value contains a \" escape sequence — the extractor must not stop at it.
    String json = "{\"status\":\"invalid\\\"token\"}";

    OAuthBearerAuthenticator.OAuthDiscoveryInfo info =
        OAuthBearerAuthenticator.OAuthDiscoveryInfo.parse(json);

    assertEquals("invalid\"token", info.getStatus());
  }

  @Test
  void testParseEscapedBackslashInValue() {
    String json = "{\"status\":\"bad\\\\status\"}";

    OAuthBearerAuthenticator.OAuthDiscoveryInfo info =
        OAuthBearerAuthenticator.OAuthDiscoveryInfo.parse(json);

    assertEquals("bad\\status", info.getStatus());
  }

  @Test
  void testInitialResponseFormat() {
    // RFC 7628 section 3.1: the initial client response must be
    // n,,\x01auth=Bearer <token>\x01\x01
    // We verify OAuthBearerAuthenticator produces this exact byte sequence.
    OAuthBearerAuthenticator auth = new OAuthBearerAuthenticator(null, "mytoken".toCharArray());

    byte[] expected;
    {
      byte[] gs2 = "n,,".getBytes(StandardCharsets.UTF_8);
      byte[] attrPrefix = new byte[]{0x01, 'a', 'u', 't', 'h', '=', 'B', 'e', 'a', 'r', 'e', 'r', ' '};
      byte[] token = "mytoken".getBytes(StandardCharsets.UTF_8);
      byte[] terminator = new byte[]{0x01, 0x01};
      expected = new byte[gs2.length + attrPrefix.length + token.length + terminator.length];
      System.arraycopy(gs2, 0, expected, 0, gs2.length);
      System.arraycopy(attrPrefix, 0, expected, gs2.length, attrPrefix.length);
      System.arraycopy(token, 0, expected, gs2.length + attrPrefix.length, token.length);
      System.arraycopy(terminator, 0, expected,
          gs2.length + attrPrefix.length + token.length, terminator.length);
    }

    // buildInitialResponse is package-private so we access it directly.
    byte[] actual = auth.buildInitialResponse();
    assertArrayEquals(expected, actual,
        "Initial response must be n,,\\x01auth=Bearer <token>\\x01\\x01 per RFC 7628");
    // Verify gs2 header
    assertTrue(actual[0] == 'n' && actual[1] == ',' && actual[2] == ',',
        "Must start with GS2 header n,,");
    // Verify first 0x01 separator
    assertEquals(0x01, actual[3] & 0xFF, "Byte 3 must be 0x01 separator");
    // Verify double terminator
    assertEquals(0x01, actual[actual.length - 2] & 0xFF, "Second-to-last byte must be 0x01");
    assertEquals(0x01, actual[actual.length - 1] & 0xFF, "Last byte must be 0x01");
  }
}
