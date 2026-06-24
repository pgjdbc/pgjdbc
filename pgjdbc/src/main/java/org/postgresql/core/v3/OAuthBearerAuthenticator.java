/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the OAUTHBEARER SASL authentication exchange (RFC 7628) with the
 * PostgreSQL server.
 */
final class OAuthBearerAuthenticator {
  private static final Logger LOGGER = Logger.getLogger(OAuthBearerAuthenticator.class.getName());

  static final String MECHANISM = "OAUTHBEARER";

  private final PGStream pgStream;
  // char[] rather than String so the token can be zeroed after use.
  private final char[] token;

  OAuthBearerAuthenticator(PGStream pgStream, char[] token) {
    this.pgStream = pgStream;
    this.token = token;
  }

  /**
   * Sends the SASLInitialResponse message with the OAUTHBEARER mechanism and
   * the bearer token formatted per RFC 7628 section 3.1:
   * {@code n,,\x01auth=Bearer <token>\x01\x01}
   */
  void handleAuthenticationSASL() throws IOException {
    byte[] mechanism = MECHANISM.getBytes(StandardCharsets.UTF_8);
    byte[] initialResponse = buildInitialResponse();

    LOGGER.log(Level.FINEST, " FE=> SASLInitialResponse(OAUTHBEARER)");

    pgStream.sendChar(PgMessageType.SASL_INITIAL_RESPONSE);
    pgStream.sendInteger4(
        Integer.BYTES + mechanism.length + 1 + Integer.BYTES + initialResponse.length);
    pgStream.send(mechanism);
    pgStream.sendChar(0);
    pgStream.sendInteger4(initialResponse.length);
    pgStream.send(initialResponse);
    pgStream.flush();

    // Zero the token immediately after sending — it is no longer needed.
    Arrays.fill(token, '\0');
    Arrays.fill(initialResponse, (byte) 0);
  }

  /**
   * Handles AUTH_REQ_SASL_CONTINUE, which for OAUTHBEARER indicates authentication
   * failure. The server sends a JSON error body with optional discovery metadata.
   * Per RFC 7628 section 3.2 the client must respond with a single 0x01 byte
   * to acknowledge the failure message before the server closes the exchange.
   */
  void handleAuthenticationSASLContinue(int length) throws IOException, PSQLException {
    String json = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLContinue(OAuth error: {0})", json);

    OAuthDiscoveryInfo discovery = OAuthDiscoveryInfo.parse(json);

    // RFC 7628 section 3.2: send a single 0x01 byte to acknowledge the error.
    pgStream.sendChar(PgMessageType.SASL_RESPONSE);
    pgStream.sendInteger4(Integer.BYTES + 1);
    pgStream.sendChar(1);
    pgStream.flush();

    String status = discovery.getStatus();
    String discoveryUrl = discovery.getDiscoveryUrl();
    if (discoveryUrl != null) {
      throw new PSQLException(
          GT.tr("OAuth authentication failed. Server status: {0}. Discovery URL: {1}",
              status, discoveryUrl),
          PSQLState.CONNECTION_REJECTED);
    }
    throw new PSQLException(
        GT.tr("OAuth authentication failed. Server status: {0}", status),
        PSQLState.CONNECTION_REJECTED);
  }

  /**
   * Builds the initial client response per RFC 7628 section 3.1.
   * Format: n,,\x01auth=Bearer token\x01\x01
   * The GS2 header "n,," signals no channel binding. The attribute-value
   * list uses 0x01 as both a leading separator and a terminator.
   */
  byte[] buildInitialResponse() {
    byte[] gs2Header = "n,,".getBytes(StandardCharsets.UTF_8);
    // \x01auth=Bearer token\x01\x01
    byte[] attrPrefix = new byte[]{0x01, 'a', 'u', 't', 'h', '=', 'B', 'e', 'a', 'r', 'e', 'r', ' '};
    byte[] tokenBytes = new String(token).getBytes(StandardCharsets.UTF_8);
    byte[] terminator = new byte[]{0x01, 0x01};

    byte[] response = new byte[gs2Header.length + attrPrefix.length
        + tokenBytes.length + terminator.length];
    int pos = 0;
    System.arraycopy(gs2Header, 0, response, pos, gs2Header.length);
    pos += gs2Header.length;
    System.arraycopy(attrPrefix, 0, response, pos, attrPrefix.length);
    pos += attrPrefix.length;
    System.arraycopy(tokenBytes, 0, response, pos, tokenBytes.length);
    pos += tokenBytes.length;
    System.arraycopy(terminator, 0, response, pos, terminator.length);

    Arrays.fill(tokenBytes, (byte) 0);
    return response;
  }

  /**
   * Holds discovery metadata from the server's OAUTHBEARER error response.
   */
  static final class OAuthDiscoveryInfo {
    private final @Nullable String status;
    private final @Nullable String discoveryUrl;
    private final @Nullable String scope;

    private OAuthDiscoveryInfo(@Nullable String status, @Nullable String discoveryUrl,
        @Nullable String scope) {
      this.status = status;
      this.discoveryUrl = discoveryUrl;
      this.scope = scope;
    }

    @Nullable String getStatus() {
      return status;
    }

    @Nullable String getDiscoveryUrl() {
      return discoveryUrl;
    }

    @Nullable String getScope() {
      return scope;
    }

    /**
     * Minimal JSON parser for the server's error response. The response is a
     * flat JSON object with string values only. We avoid pulling in a JSON
     * library dependency for this simple case.
     */
    static OAuthDiscoveryInfo parse(String json) {
      String status = extractJsonString(json, "status");
      String discoveryUrl = extractJsonString(json, "openid-configuration");
      String scope = extractJsonString(json, "scope");
      return new OAuthDiscoveryInfo(status, discoveryUrl, scope);
    }

    /**
     * Extracts a JSON string value for the given key, handling backslash escape
     * sequences so a \" inside the value does not prematurely terminate it.
     */
    private static @Nullable String extractJsonString(String json, String key) {
      String search = "\"" + key + "\"";
      int keyIdx = json.indexOf(search);
      if (keyIdx < 0) {
        return null;
      }
      int colonIdx = json.indexOf(':', keyIdx + search.length());
      if (colonIdx < 0) {
        return null;
      }
      int startQuote = json.indexOf('"', colonIdx + 1);
      if (startQuote < 0) {
        return null;
      }
      StringBuilder sb = new StringBuilder();
      int i = startQuote + 1;
      while (i < json.length()) {
        char c = json.charAt(i);
        if (c == '\\' && i + 1 < json.length()) {
          char escaped = json.charAt(i + 1);
          if (escaped == '"' || escaped == '\\') {
            sb.append(escaped);
          } else {
            sb.append('\\').append(escaped);
          }
          i += 2;
        } else if (c == '"') {
          return sb.toString();
        } else {
          sb.append(c);
          i++;
        }
      }
      return null; // unterminated string — malformed JSON
    }
  }
}
