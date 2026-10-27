/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.plugin.OAuthTokenProvider;
import org.postgresql.plugin.OAuthTokenRequest;
import org.postgresql.test.OAuthTestTokenProvider;
import org.postgresql.test.TestUtil;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.TestLogHandler;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Integration tests for OAuth Bearer (OAUTHBEARER SASL) authentication.
 * These tests require PostgreSQL 18+ with OAuth configured.
 */
public class OAuthTest {

  private static @Nullable Connection con;

  private static final OAuthTokenRequest tokenRequest = new OAuthTokenRequest(
      TestUtil.getOAuthIssuer(),
      TestUtil.getOAuthClientId(),
      TestUtil.getOAuthClientSecret(),
      TestUtil.getOAuthScope());

  private static final OAuthTestTokenProvider provider = new OAuthTestTokenProvider();

  private static final Pattern unencryptedConnectionWarning = Pattern.compile(
      "oauthAllowInsecureConnection=true: OAUTHBEARER is being used over an unencrypted "
          + "connection, so the bearer token is sent in the clear. RFC 7628 §3 requires a "
          + "secure channel. Do not use this configuration in production.");

  private static final Pattern unverifiedConnectionWarning = Pattern.compile(
      "OAUTHBEARER is being used over a TLS connection whose server certificate is not verified");

  private static final Pattern tokenRejectedWarning =
      Pattern.compile("Server rejected OAuth bearer token");

  private static final Logger oauthLogger =
      Logger.getLogger("org.postgresql.core.v3.OAuthAuthenticator");
  private static final TestLogHandler logHandler = new TestLogHandler();
  private static @Nullable Level previousLevel;
  private static boolean previousUseParentHandlers;
  private static boolean loggerConfigured;

  @BeforeAll
  static void setUp() throws Exception {
    TestUtil.assumeOAuthTestsEnabled();

    con = TestUtil.openPrivilegedDB();

    assumeTrue(
        TestUtil.haveMinimumServerVersion(con, ServerVersion.v18),
        "PostgreSQL 18+ required for OAuth tests");

    previousLevel = oauthLogger.getLevel();
    previousUseParentHandlers = oauthLogger.getUseParentHandlers();
    oauthLogger.addHandler(logHandler);
    oauthLogger.setLevel(Level.ALL);
    // Route logs to our handler only, so they do not pollute standard output.
    oauthLogger.setUseParentHandlers(false);
    loggerConfigured = true;
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (loggerConfigured) {
      oauthLogger.removeHandler(logHandler);
      oauthLogger.setLevel(previousLevel);
      oauthLogger.setUseParentHandlers(previousUseParentHandlers);
      loggerConfigured = false;
    }
    TestUtil.closeDB(con);
  }

  /**
   * Static token authentication.
   */
  @Test
  void staticToken() throws Exception {
    String username = TestUtil.getOAuthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  /**
   * Token provided by {@link OAuthTokenProvider} plugin class.
   */
  @Test
  void tokenProvider() throws SQLException {
    String username = TestUtil.getOAuthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(props, TestUtil.getTokenProviderClassName());
    PGProperty.OAUTH_ISSUER.set(props, TestUtil.getOAuthIssuer());
    PGProperty.OAUTH_ALLOW_INSECURE_ISSUER.set(props, "true");
    PGProperty.OAUTH_CLIENT_ID.set(props, TestUtil.getOAuthClientId());
    PGProperty.OAUTH_CLIENT_SECRET.set(props, TestUtil.getOAuthClientSecret());
    PGProperty.OAUTH_SCOPE.set(props, TestUtil.getOAuthScope());
    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  /**
   * An invalid bearer token causes the server to reject the connection.
   */
  @Test
  void invalidToken() throws Exception {
    String username = TestUtil.getOAuthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, "invalid-bearer-token");

    logHandler.records.clear();
    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Server should reject an invalid bearer token");
    assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), ex.getSQLState());
    assertFalse(
        logHandler.getRecordsMatching(tokenRejectedWarning).isEmpty(),
        "The rejection sent in AuthenticationSASLContinue should be logged");
  }

  @Test
  void requireAuthAllowsOAuth() throws Exception {
    String username = TestUtil.getOAuthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());
    PGProperty.REQUIRE_AUTH.set(props, "oauth");

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  @Test
  void requireAuthForbidsOAuth() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOAuthRole());
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());
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
    PGProperty.USER.set(props, TestUtil.getOAuthRole());
    PGProperty.SSL_MODE.set(props, "require");
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());
    PGProperty.CHANNEL_BINDING.set(props, "require");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject OAuth when channelBinding=require");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(
        GT.tr("Channel binding is not supported for OAuth authentication."),
        ex.getMessage());
  }

  @Test
  void unencryptedConnectionRejected() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOAuthRole());
    PGProperty.SSL_MODE.set(props, "disable");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject OAUTHBEARER over an unencrypted connection unless explicitly allowed");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }

  @Test
  void unverifiedTlsConnectionRejected() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOAuthRole());
    PGProperty.SSL_MODE.set(props, "require");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject OAUTHBEARER over TLS that does not verify the server certificate");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
  }

  @Test
  void unverifiedTlsConnectionAllowed() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    String username = TestUtil.getOAuthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.SSL_MODE.set(props, "require");
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    logHandler.records.clear();
    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }

    assertFalse(
        logHandler.getRecordsMatching(unverifiedConnectionWarning).isEmpty(),
        "Expected a warning about using OAUTHBEARER against an unverified server");
    assertTrue(
        logHandler.getRecordsMatching(unencryptedConnectionWarning).isEmpty(),
        "The connection is encrypted, so the unencrypted warning must not be logged");
  }

  @Test
  void unencryptedConnectionAllowed() throws Exception {
    String username = TestUtil.getOAuthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.SSL_MODE.set(props, "disable");
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    logHandler.records.clear();
    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }

    assertFalse(
        logHandler.getRecordsMatching(unencryptedConnectionWarning).isEmpty(),
        "Expected a warning about using OAUTHBEARER over an unencrypted connection");
  }

  /**
   * Gets the token and converts it to a string. Avoid doing this in real code,
   * as token will be stored in memory until garbage collection. In tests, it's fine.
   */
  private static String fetchTokenAsString() throws PSQLException {
    return new String(provider.getToken(tokenRequest));
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
