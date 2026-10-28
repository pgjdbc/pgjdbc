/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.plugin.OauthTokenProvider;
import org.postgresql.plugin.OauthTokenRequest;
import org.postgresql.ssl.NonValidatingFactory;
import org.postgresql.test.OauthTestTokenProvider;
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
public class OauthTest {

  private static @Nullable Connection con;

  private static final OauthTokenRequest tokenRequest = OauthTokenRequest.builder().build();

  private static final OauthTestTokenProvider provider =
      new OauthTestTokenProvider(new Properties());

  private static final Pattern unencryptedConnectionWarning = Pattern.compile(
      "Sending the OAuth bearer token to \\{0\\} over an unencrypted connection");

  private static final Pattern unverifiedConnectionWarning = Pattern.compile(
      "Sending the OAuth bearer token to \\{0\\} over TLS without verifying the server "
          + "certificate");

  private static final Logger oauthLogger =
      Logger.getLogger("org.postgresql.core.v3.OauthAuthenticator");
  private static final TestLogHandler logHandler = new TestLogHandler();
  private static @Nullable Level previousLevel;
  private static boolean previousUseParentHandlers;
  private static boolean loggerConfigured;

  @BeforeAll
  static void setUp() throws Exception {
    TestUtil.assumeOauthTestsEnabled();

    con = TestUtil.openPrivilegedDB();

    assumeTrue(
        TestUtil.haveMinimumServerVersion(con, ServerVersion.v18),
        "PostgreSQL 18+ required for OAuth tests");

    previousLevel = oauthLogger.getLevel();
    previousUseParentHandlers = oauthLogger.getUseParentHandlers();
    oauthLogger.addHandler(logHandler);
    oauthLogger.setLevel(Level.ALL);
    // Record the messages here without also emitting them to the console.
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

  @Test
  void connectsWithAStaticToken() throws Exception {
    String username = TestUtil.getOauthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  /**
   * Token provided by {@link OauthTokenProvider} plugin class, which reads its settings from
   * connection properties of its own.
   */
  @Test
  void connectsWithATokenProvider() throws SQLException {
    String username = TestUtil.getOauthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.set(props, TestUtil.getTokenProviderClassName());
    props.setProperty(OauthTestTokenProvider.ISSUER, TestUtil.getOauthIssuer());
    props.setProperty(OauthTestTokenProvider.CLIENT_ID, TestUtil.getOauthClientId());
    props.setProperty(OauthTestTokenProvider.CLIENT_SECRET, TestUtil.getOauthClientSecret());
    props.setProperty(OauthTestTokenProvider.SCOPE, TestUtil.getOauthScope());
    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
    }
  }

  @Test
  void refusesATokenTheValidatorRejects() throws Exception {
    String username = TestUtil.getOauthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, "invalid-bearer-token");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Server should reject an invalid bearer token");
    assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(), ex.getSQLState());
    assertNotNull(ex.getCause(), "The error result should be chained as the cause");
    assertTrue(ex.getCause().getMessage().contains("invalid_token"),
        () -> "The error result should carry the status, but was: " + ex.getCause().getMessage());
  }

  @Test
  void connectsWhenRequireAuthAllowsOauth() throws Exception {
    String username = TestUtil.getOauthRole();
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
  void refusesOauthWhenRequireAuthExcludesIt() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOauthRole());
    PGProperty.OAUTH_ALLOW_INSECURE_CONNECTION.set(props, "true");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());
    PGProperty.REQUIRE_AUTH.set(props, "!oauth");

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject the connection when requireAuth disallows OAuth Bearer");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("Authentication method is not allowed by requireAuth"), ex.getMessage());
  }

  @Test
  void refusesOauthUnderChannelBindingRequire() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOauthRole());
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
  void refusesToSendTheTokenOverPlaintext() throws Exception {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOauthRole());
    PGProperty.SSL_MODE.set(props, "disable");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject OAUTHBEARER over an unencrypted connection unless explicitly allowed");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAUTHBEARER sends the bearer token only over an encrypted connection to a "
        + "verified server, and this connection is not encrypted (sslmode={0}). Set "
        + "sslmode=verify-full or verify-ca, or use GSS encryption (RFC 7628 section 3, "
        + "RFC 6750 section 5.3).",
        "disable"), ex.getMessage());
  }

  @Test
  void refusesToSendTheTokenOverUnverifiedTls() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOauthRole());
    PGProperty.SSL_MODE.set(props, "require");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject OAUTHBEARER over TLS that does not verify the server certificate");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAUTHBEARER sends the bearer token only to a verified server, and "
        + "sslmode={0} does not verify the server certificate. Set sslmode=verify-full "
        + "or verify-ca (RFC 6750 section 5.3).", "require"), ex.getMessage());
  }

  /**
   * The production configuration: TLS with a verified server certificate, and no
   * oauthAllowInsecureConnection.
   */
  @Test
  void connectsOverVerifiedTls() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    String username = TestUtil.getOauthRole();
    Properties props = new Properties();
    PGProperty.USER.set(props, username);
    // The test server certificate is issued for localhost.
    TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
    PGProperty.SSL_MODE.set(props, "verify-full");
    PGProperty.SSL_ROOT_CERT.set(props, TestUtil.getSslTestCertPath("goodroot.crt"));
    PGProperty.SSL_CERT.set(props, "");
    PGProperty.SSL_KEY.set(props, "");
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    logHandler.records.clear();
    try (Connection c = TestUtil.openDB(props)) {
      assertCurrentUser(c, username);
      // pg_stat_ssl is built in, unlike ssl_is_used(), which needs the sslinfo extension.
      assertEquals(Boolean.TRUE,
          TestUtil.queryForBoolean(c, "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()"),
          "The connection should use TLS");
    }
    assertTrue(logHandler.getRecordsMatching(unverifiedConnectionWarning).isEmpty(),
        "A verified connection must not log the oauthAllowInsecureConnection warning");
    assertTrue(logHandler.getRecordsMatching(unencryptedConnectionWarning).isEmpty(),
        "A verified connection must not log the unencrypted connection warning");
  }

  /**
   * NonValidatingFactory accepts any certificate, so sslmode=verify-ca does not make the server
   * verified.
   */
  @Test
  void refusesToSendTheTokenThroughNonValidatingFactory() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getOauthRole());
    PGProperty.SSL_MODE.set(props, "verify-ca");
    PGProperty.SSL_FACTORY.set(props, NonValidatingFactory.class.getName());
    PGProperty.OAUTH_TOKEN.set(props, fetchTokenAsString());

    PSQLException ex = assertThrows(
        PSQLException.class,
        () -> TestUtil.openDB(props),
        "Driver should reject OAUTHBEARER over TLS through a factory that accepts any certificate");
    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    assertEquals(GT.tr("OAUTHBEARER sends the bearer token only to a verified server, and "
        + "sslfactory={0} does not verify the server certificate. Remove sslfactory, or set "
        + "it to a factory that verifies the certificate (RFC 6750 section 5.3).",
        NonValidatingFactory.class.getName()), ex.getMessage());
  }

  @Test
  void sendsTheTokenOverUnverifiedTlsWhenAllowed() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    String username = TestUtil.getOauthRole();
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
  void sendsTheTokenOverPlaintextWhenAllowed() throws Exception {
    String username = TestUtil.getOauthRole();
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

  /** A String keeps the token in memory until garbage collection, which only a test can afford. */
  private static String fetchTokenAsString() throws PSQLException {
    return new String(provider.getToken(tokenRequest));
  }

  private static void assertCurrentUser(Connection c, String expectedUser) throws SQLException {
    try (Statement stmt = c.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT current_user")) {
      assertTrue(rs.next(), "Expected at least one row from SELECT current_user");
      assertEquals(expectedUser, rs.getString(1), "current_user should match the expected user");
    }
  }
}
