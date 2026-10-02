/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.plugin.OAuthTokenProvider;
import org.postgresql.plugin.OAuthTokenRequest;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.URLCoder;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OAuth token provider for the test suite.
 */
public class OAuthTestTokenProvider implements OAuthTokenProvider {

  private static final Pattern ACCESS_TOKEN_PATTERN =
      Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

  private static final Pattern EXPIRES_IN_PATTERN =
      Pattern.compile("\"expires_in\"\\s*:\\s*(\\d+)");

  /**
   * Stop using a token this long before it expires.
   */
  private static final long EXPIRY_MARGIN_MILLIS = 30_000;

  private static final ConcurrentMap<String, CachedToken> TOKEN_CACHE = new ConcurrentHashMap<>();

  @Override
  public char[] getToken(OAuthTokenRequest request) throws PSQLException {
    String key = cacheKey(request);
    CachedToken cached = TOKEN_CACHE.get(key);
    if (cached != null && cached.isUsable()) {
      return cached.token.clone();
    }

    CachedToken fresh;
    try {
      fresh = fetchToken(request);
    } catch (PSQLException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new PSQLException(
          "Failed to get OAuth token: " + ex.getMessage(),
          PSQLState.CONNECTION_REJECTED, ex);
    }

    if (fresh.isUsable()) {
      TOKEN_CACHE.put(key, fresh);
    }
    return fresh.token.clone();
  }

  private static String cacheKey(OAuthTokenRequest request) {
    return request.getIssuer() + "\n" + request.getClientId() + "\n" + request.getScope();
  }

  private static CachedToken fetchToken(OAuthTokenRequest request) throws Exception {
    String issuer = require(request.getIssuer(), "oauthIssuer");
    String body = "grant_type=client_credentials"
        + "&client_id=" + URLCoder.encode(require(request.getClientId(), "oauthClientId"))
        + "&client_secret="
        + URLCoder.encode(require(request.getClientSecret(), "oauthClientSecret"))
        + "&scope=" + URLCoder.encode(require(request.getScope(), "oauthScope"));

    // A real provider reads token_endpoint from {issuer}/.well-known/openid-configuration. This
    // one appends Keycloak's well-known path instead, because the discovery document advertises
    // the container-internal keycloak:8080 URLs, which the tests cannot reach from the host.
    String response = httpPost(issuer + "/protocol/openid-connect/token", body);
    Matcher matcher = ACCESS_TOKEN_PATTERN.matcher(response);
    if (!matcher.find()) {
      throw new PSQLException(
          "access_token not found in Keycloak response: " + response,
          PSQLState.CONNECTION_REJECTED);
    }
    return new CachedToken(castNonNull(matcher.group(1)).toCharArray(), expiresAt(response));
  }

  /**
   * Returns when the token in the given response stops being usable, or 0 if the response carries
   * no {@code expires_in} and the token therefore must not be cached.
   */
  private static long expiresAt(String response) {
    Matcher matcher = EXPIRES_IN_PATTERN.matcher(response);
    if (!matcher.find()) {
      return 0;
    }
    long seconds = Long.parseLong(castNonNull(matcher.group(1)));
    return System.currentTimeMillis() + seconds * 1000 - EXPIRY_MARGIN_MILLIS;
  }

  private static String require(@Nullable String value, String name) throws PSQLException {
    if (value == null) {
      throw new PSQLException(
          "OAuth token request is missing required field: " + name,
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    return value;
  }

  private static String httpPost(String url, String body) throws Exception {
    HttpURLConnection conn = open(url);
    conn.setRequestMethod("POST");
    conn.setDoOutput(true);
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
    byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
    conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
    try (DataOutputStream out = new DataOutputStream(conn.getOutputStream())) {
      out.write(bodyBytes);
    }
    return readResponse(conn);
  }

  private static HttpURLConnection open(String urlStr) throws Exception {
    HttpURLConnection conn = (HttpURLConnection) URI.create(urlStr).toURL().openConnection();
    conn.setConnectTimeout(5000);
    conn.setReadTimeout(10000);
    return conn;
  }

  private static String readResponse(HttpURLConnection conn) throws Exception {
    int status = conn.getResponseCode();
    // On an error status getInputStream() throws and the body describing the error is only
    // available from getErrorStream(), so read that instead and report what the server said.
    InputStream stream = status >= HttpURLConnection.HTTP_BAD_REQUEST
        ? conn.getErrorStream()
        : conn.getInputStream();
    String payload = stream == null ? "" : readAll(stream);
    if (status >= HttpURLConnection.HTTP_BAD_REQUEST) {
      throw new PSQLException(
          "Token endpoint " + conn.getURL() + " returned HTTP " + status + ": " + payload,
          PSQLState.CONNECTION_REJECTED);
    }
    return payload;
  }

  private static String readAll(InputStream stream) throws Exception {
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.joining("\n"));
    }
  }

  private static final class CachedToken {
    private final char[] token;
    private final long usableUntilMillis;

    CachedToken(char[] token, long usableUntilMillis) {
      this.token = token;
      this.usableUntilMillis = usableUntilMillis;
    }

    boolean isUsable() {
      return System.currentTimeMillis() < usableUntilMillis;
    }
  }
}
