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
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OAuth token provider for the test suite.
 */
public class OAuthTestTokenProvider implements OAuthTokenProvider {

  private static final Pattern ACCESS_TOKEN_PATTERN =
      Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"");

  @Override
  public char[] getToken(OAuthTokenRequest request) throws PSQLException {
    String token = null;
    try {
      token = fetchToken(request);
    } catch (Exception ex) {
      throw new PSQLException(
          "Failed to get OAuth token: " + ex.getMessage(),
          PSQLState.CONNECTION_REJECTED, ex);
    }
    if (token == null || token.isEmpty()) {
      throw new PSQLException(
          "oauthToken system property not set",
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    return token.toCharArray();
  }

  private static String fetchToken(OAuthTokenRequest request) throws Exception {
    String issuer = require(request.getIssuer(), "oauthIssuer");
    String body = "grant_type=password"
        + "&client_id=" + URLCoder.encode(require(request.getClientId(), "oauthClientId"))
        + "&password=" + URLCoder.encode(require(request.getClientSecret(), "oauthClientSecret"))
        + "&username=" + URLCoder.encode(require(request.getUsername(), "oauthUsername"))
        + "&scope=" + URLCoder.encode(require(request.getScope(), "oauthScope"));

    String response = httpPost(issuer, body);
    Matcher matcher = ACCESS_TOKEN_PATTERN.matcher(response);
    if (!matcher.find()) {
      throw new PSQLException(
          "access_token not found in Keycloak response: " + response,
          PSQLState.CONNECTION_REJECTED);
    }
    return castNonNull(matcher.group(1));
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
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      return reader.lines().collect(Collectors.joining("\n"));
    }
  }
}
