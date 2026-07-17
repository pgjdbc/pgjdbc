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
import org.postgresql.plugin.OAuthTokenProvider;
import org.postgresql.plugin.OAuthTokenRequest;
import org.postgresql.test.OAuthTestTokenProvider;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Integration tests for OAuth Bearer (OAUTHBEARER SASL) authentication.
 * These tests require PostgreSQL 18+ with OAuth configured.
 */
public class OAuthTest {

  private static @Nullable Connection con;

  private static final OAuthTokenRequest tokenRequest = new OAuthTokenRequest(
      TestUtil.getOAuthUsername(),
      TestUtil.getOAuthIssuer(),
      TestUtil.getOAuthClientId(),
      TestUtil.getOAuthClientSecret(),
      TestUtil.getOAuthScope());

  private static final OAuthTestTokenProvider provider = new OAuthTestTokenProvider();

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

  /**
   * Static token authentication.
   */
  @Test
  void staticToken() throws Exception {
    String username = TestUtil.getOAuthUsername();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, new String(provider.getToken(tokenRequest)));

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  /**
   * Token provided by {@link OAuthTokenProvider} plugin class.
   */
  @Test
  void tokenProvider() throws SQLException {
    String username = TestUtil.getOAuthUsername();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(props, TestUtil.getTokenProviderClassName());
    PGProperty.OAUTH_ISSUER.set(props, TestUtil.getOAuthIssuer());
    PGProperty.OAUTH_ALLOW_INSECURE_ISSUER.set(props, "true");
    PGProperty.OAUTH_CLIENT_ID.set(props, TestUtil.getOAuthClientId());
    PGProperty.OAUTH_CLIENT_SECRET.set(props, TestUtil.getOAuthClientSecret());
    PGProperty.OAUTH_SCOPE.set(props, TestUtil.getOAuthScope());
    PGProperty.OAUTH_USERNAME.set(props, username);
    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  /**
   * An invalid bearer token causes the server to reject the connection.
   */
  @Test
  void invalidToken() throws Exception {
    String username = TestUtil.getOAuthUsername();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, "invalid-bearer-token");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Server should reject an invalid bearer token");
    assertEquals(
        "FATAL: OAuth bearer authentication failed for user \"" + username + "\"",
        ex.getMessage());
  }

  @Test
  void requireAuthAllowsOAuth() throws Exception {
    String username = TestUtil.getOAuthUsername();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_UNENCRYPTED_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, new String(provider.getToken(tokenRequest)));
    PGProperty.REQUIRE_AUTH.set(props, "oauth");

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  @Test
  void requireAuthForbidsOAuth() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOAuthUsername());
    PGProperty.OAUTH_TOKEN.set(props, new String(provider.getToken(tokenRequest)));
    PGProperty.REQUIRE_AUTH.set(props, "!oauth");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject the connection when requireAuth disallows OAuth Bearer");
    // Assert only on the SQL state: the message is localized via GT.tr and varies by JVM locale.
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }

  @Test
  void channelBindingIncompatibleWithOAuth() throws Exception {
    // channelBinding requires a TLS connection, so this scenario only applies when SSL is enabled.
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOAuthUsername());
    PGProperty.SSL_MODE.set(props, "require");
    PGProperty.OAUTH_TOKEN.set(props, new String(provider.getToken(tokenRequest)));
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
}
