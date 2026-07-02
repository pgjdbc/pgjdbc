/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.v3.OAuthAuthenticator;
import org.postgresql.plugin.OAuthTokenProvider;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.TestLogHandler;
import org.postgresql.util.URLCoder;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Integration tests for OAuth Bearer (OAUTHBEARER SASL) authentication.
 * These tests require PostgreSQL 18+ with OAuth configured.
 */
public class OAuthTest {

  private static @Nullable Connection con;
  private static final String USERNAME = "testoauth";
  private static final String KEYCLOAK_CLIENT_ID = "pgjdbc-test";
  private static final String KEYCLOAK_PASSWORD = "testoidc-password";
  private static final String KEYCLOAK_TOKEN_URL = "http://localhost:8080/realms/pgjdbc/protocol/openid-connect/token";
  private static final String KEYCLOAK_SCOPE = "pgjdbc";

  @BeforeAll
  static void setUp() throws Exception {
    TestUtil.assumeOAuthTestsEnabled();

    con = TestUtil.openPrivilegedDB();

    assumeTrue(
        TestUtil.haveMinimumServerVersion(con, ServerVersion.v18),
        "PostgreSQL 18+ required for OAuth tests");
  }

  @AfterAll
  static void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  private @Nullable TestLogHandler logHandler;
  private @Nullable Logger oauthLogger;
  private @Nullable Level previousOauthLoggerLevel;

  @BeforeEach
  void installLogHandler() {
    logHandler = new TestLogHandler();
    oauthLogger = Logger.getLogger(OAuthAuthenticator.class.getName());
    previousOauthLoggerLevel = oauthLogger.getLevel();
    oauthLogger.addHandler(logHandler);
    oauthLogger.setLevel(Level.ALL);
  }

  @AfterEach
  void uninstallLogHandler() {
    if (oauthLogger != null && logHandler != null) {
      oauthLogger.removeHandler(logHandler);
      oauthLogger.setLevel(previousOauthLoggerLevel);
    }
    logHandler = null;
    oauthLogger = null;
  }

  /**
   * Static token authentication.
   */
  @Test
  void staticToken() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, USERNAME);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchToken(KEYCLOAK_TOKEN_URL, KEYCLOAK_SCOPE));

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, USERNAME);
    }
  }

  /**
   * Token provided by {@link OAuthTokenProvider} plugin class.
   */
  @Test
  void tokenProvider() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, USERNAME);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED.set(props, "true");
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(props, TokenProvider.class.getName());

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, USERNAME);
    }
  }

  /**
   * An invalid bearer token causes the server to reject the connection.
   */
  @Test
  void invalidToken() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, USERNAME);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, "invalid-bearer-token");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Server should reject an invalid bearer token");
    assertEquals(
        "FATAL: OAuth bearer authentication failed for user \"" + USERNAME + "\"",
        ex.getMessage());
  }

  @Test
  void requireAuthAllowsOAuth() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, USERNAME);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchToken(KEYCLOAK_TOKEN_URL, KEYCLOAK_SCOPE));
    PGProperty.REQUIRE_AUTH.set(props, "oauth-bearer");

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, USERNAME);
    }
  }

  @Test
  void requireAuthForbidsOAuth() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, USERNAME);
    PGProperty.OAUTH_TOKEN.set(props, fetchToken(KEYCLOAK_TOKEN_URL, KEYCLOAK_SCOPE));
    PGProperty.REQUIRE_AUTH.set(props, "!oauth-bearer");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject the connection when requireAuth disallows OAuth Bearer");
    assertEquals(
        "The server requested SASL authentication with mechanisms [OAUTHBEARER], but non of them configured or supported by the driver.",
        ex.getMessage());
  }

  @Test
  void channelBindingIncompatibleWithOAuth() throws Exception {
    // channelBinding requires a TLS connection, so this scenario only applies when SSL is enabled.
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.USER.set(props, USERNAME);
    PGProperty.SSL_MODE.set(props, "require");
    PGProperty.OAUTH_TOKEN.set(props, fetchToken(KEYCLOAK_TOKEN_URL, KEYCLOAK_SCOPE));
    PGProperty.CHANNEL_BINDING.set(props, "require");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject OAuth when channelBinding=require");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(
        "Channel binding is not supported for OAuth authentication.",
        ex.getMessage());
  }

  public static class TokenProvider implements OAuthTokenProvider {
    @Override
    public char @Nullable [] getToken() throws PSQLException {
      String token = null;
      try {
        token = fetchToken(KEYCLOAK_TOKEN_URL, KEYCLOAK_SCOPE);
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
  }

  /**
   * Verifies that the given connection is authenticated as the expected user.
   */
  private static void assertCurrentUser(Connection c, String expectedUser) throws SQLException {
    try (Statement stmt = c.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_user")) {
      assertTrue(rs.next(), "Expected at least one row from SELECT current_user");
      assertEquals(expectedUser, rs.getString(1), "current_user should match the expected user");
    }
  }

  private static String fetchToken(String url, String scope) throws Exception {
    String body = "grant_type=password"
        + "&client_id=" + URLCoder.encode(KEYCLOAK_CLIENT_ID)
        + "&password=" + URLCoder.encode(KEYCLOAK_PASSWORD)
        + "&username=" + URLCoder.encode(USERNAME)
        + "&scope=" + URLCoder.encode(scope);

    String response = httpPost(url, body);
    String key = "\"access_token\":\"";
    int start = response.indexOf(key);
    if (start < 0) {
      throw new PSQLException(
          "access_token not found in Keycloak response: " + response,
          PSQLState.CONNECTION_REJECTED);
    }
    start += key.length();
    int end = response.indexOf('"', start);
    return response.substring(start, end);
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
