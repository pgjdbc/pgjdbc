/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

class ChannelBindingRequiredTest {

  private static final String MD5_ROLE_NAME = "test_channel_binding_md5";
  private static final String SASL_ROLE_NAME = "test_channel_binding_sasl";
  private static final String TEST_PASSWORD = "test_password_123";

  @BeforeAll
  static void setUp() throws Exception {
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v11);

    try (Connection conn = TestUtil.openPrivilegedDB()) {
      String sslEnabled = TestUtil.queryForString(conn, "SHOW ssl");
      assumeTrue("on".equals(sslEnabled), "SSL must be enabled for channel binding tests");

      TestUtil.execute(conn, "SET password_encryption = 'md5'");
      TestUtil.execute(conn, "DROP ROLE IF EXISTS " + MD5_ROLE_NAME);
      TestUtil.execute(conn, "CREATE ROLE " + MD5_ROLE_NAME + " WITH LOGIN PASSWORD '" + TEST_PASSWORD + "'");

      TestUtil.execute(conn,"SET password_encryption='scram-sha-256'");
      TestUtil.execute(conn,"DROP ROLE IF EXISTS " + SASL_ROLE_NAME);
      TestUtil.execute(conn,"CREATE ROLE " + SASL_ROLE_NAME + " WITH LOGIN PASSWORD '" + TEST_PASSWORD + "'");
    }
  }

  @AfterAll
  static void tearDown() throws Exception {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      TestUtil.execute(conn, "DROP ROLE IF EXISTS " + MD5_ROLE_NAME);
      TestUtil.execute(conn, "DROP ROLE IF EXISTS " + SASL_ROLE_NAME);
    }
  }

  /**
   * Test that md5 authentication fails when channel binding is required.
   * Channel binding is only supported with SSL + SCRAM authentication.
   */
  @Test
  void testMD5AuthWithChannelBindingRequiredFails() {
    Properties props = new Properties();
    PGProperty.USER.set(props, MD5_ROLE_NAME);
    PGProperty.PASSWORD.set(props, TEST_PASSWORD);
    PGProperty.CHANNEL_BINDING.set(props, "require");
    PGProperty.SSL_MODE.set(props, "require");

    PSQLException ex = assertThrows(PSQLException.class, () -> TestUtil.openDB(props),
        "Connection with MD5 auth and channel binding required should fail");

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    String errorMessage = ex.getMessage().toLowerCase(Locale.ROOT);
    assertTrue(errorMessage.contains("channel binding") && errorMessage.contains("md5"),
        "Error message should mention both channel binding requirement and MD5 authentication: " + ex.getMessage());
  }


  /**
   * Test that SCRAM authentication fails when channel binding is required with no SSL.
   * Channel binding is only supported with SSL + SCRAM authentication.
   */
  @Test
  void testScramAuthWithNoSSLChannelBindingRequiredFails() {
    Properties props = new Properties();
    PGProperty.USER.set(props, SASL_ROLE_NAME);
    PGProperty.PASSWORD.set(props, TEST_PASSWORD);
    PGProperty.CHANNEL_BINDING.set(props, "require");
    PGProperty.SSL_MODE.set(props, "disable");

    PSQLException ex = assertThrows(PSQLException.class, () -> TestUtil.openDB(props),
        "Connection with SCRAM auth and channel binding required should fail without SSL");

    assertEquals(PSQLState.CONNECTION_REJECTED.getState(), ex.getSQLState());
    String errorMessage = ex.getMessage().toLowerCase(Locale.ROOT);
    assertTrue(errorMessage.contains("channel binding") && errorMessage.contains("ssl"),
        "Error message should mention both channel binding requirement and ssl: " + ex.getMessage());
  }

  /**
   * Test that SASL authentication succeeds when channel binding is required.
   * This should work as channel binding is supported with SCRAM authentication.
   */
  @Test
  void testSASLAuthWithChannelBindingRequiredSucceeds() throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, SASL_ROLE_NAME);
    PGProperty.PASSWORD.set(props, TEST_PASSWORD);
    PGProperty.CHANNEL_BINDING.set(props, "require");
    PGProperty.SSL_MODE.set(props, "require");

    try (Connection conn = TestUtil.openDB(props);
         Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT current_user")) {
      assertTrue(rs.next(), "Has result row");
      assertEquals(SASL_ROLE_NAME, rs.getString(1));
    }
  }
}
