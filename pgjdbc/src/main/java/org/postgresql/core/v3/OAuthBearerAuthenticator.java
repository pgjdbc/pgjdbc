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
  private final String token;

  OAuthBearerAuthenticator(PGStream pgStream, String token) {
    this.pgStream = pgStream;
    this.token = token;
  }

  /**
   * Sends the SASLInitialResponse message with the OAUTHBEARER mechanism and
   * the bearer token formatted per RFC 7628.
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
  }

  /**
   * Handles AUTH_REQ_SASL_CONTINUE which indicates authentication failure.
   * The server sends a JSON error body with optional discovery metadata.
   * We must respond with a single 0x01 byte to acknowledge the failure.
   *
   * @return discovery info parsed from the server's error response
   */
  OAuthDiscoveryInfo handleAuthenticationSASLContinue(int length) throws IOException, PSQLException {
    String json = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLContinue(OAuth error: {0})", json);

    OAuthDiscoveryInfo discovery = OAuthDiscoveryInfo.parse(json);

    // Send dummy client response (single 0x01 byte) to acknowledge failure
    pgStream.sendChar(PgMessageType.SASL_RESPONSE);
    pgStream.sendInteger4(Integer.BYTES + 1);
    pgStream.sendChar(1);
    pgStream.flush();

    throw new PSQLException(
        GT.tr("OAuth authentication failed. Server status: {0}", discovery.getStatus()),
        PSQLState.CONNECTION_REJECTED);
  }

  /**
   * Builds the initial client response per RFC 7628 section 3.1:
   * {@code n,,\x01auth=Bearer <token>\x01\x01}
   */
  private byte[] buildInitialResponse() {
    String response = "n,,auth=Bearer " + token + "";
    return response.getBytes(StandardCharsets.UTF_8);
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
      int endQuote = json.indexOf('"', startQuote + 1);
      if (endQuote < 0) {
        return null;
      }
      return json.substring(startQuote + 1, endQuote);
    }
  }
}
