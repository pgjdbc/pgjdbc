/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import org.postgresql.PGConnection;
import org.postgresql.core.Utils;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;
import org.postgresql.util.PasswordUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class PasswordUtilTest {
  @Rule
  public ServerVersionRule versionRule = new ServerVersionRule();

  private static final SecureRandom rng = new SecureRandom();

  private static String randomSuffix() {
    return Long.toHexString(rng.nextLong());
  }

  private void assertValidUsernamePassword(String user, String password) {
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    try (Connection conn = TestUtil.openDB(props)) {
      String actualUser = TestUtil.queryForString(conn, "SELECT USER");
      Assert.assertEquals("User should match", user, actualUser);
    } catch (SQLException e) {
      throw new RuntimeException("Failed to authenticate using supplied user and password", e);
    }
  }

  private void assertInvalidUsernamePassword(String user, String password) {
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    Assert.assertThrows("User should not be able to authenticate", SQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        conn.getSchema(); // Do something with conn to appease checkstyle
      }
    });
  }

  private void assertWiped(char[] passwordChars) {
    for (int i = 0; i < passwordChars.length; i++) {
      Assert.assertEquals("passwordChars[" + i + "] should be wiped", ' ', passwordChars[i]);
    }
  }

  private void testUserPassword(@Nullable String encryptionType, String username, String password,
      String encodedPassword) throws SQLException {
    String escapedUsername = Utils.escapeIdentifier(null, username).toString();

    try (Connection superConn = TestUtil.openPrivilegedDB()) {
      TestUtil.execute(superConn, "CREATE USER " //
          + escapedUsername //
          + " WITH PASSWORD '" + encodedPassword + "'");

      String shadowPass = TestUtil.queryForString(superConn, //
          "SELECT passwd FROM pg_shadow WHERE usename = ?", username);
      Assert.assertEquals("pg_shadow value of password must match encoded", shadowPass, encodedPassword);

      // We should be able to log in using our new user:
      assertValidUsernamePassword(username, password);
      // We also check that we cannot log in with the wrong password to ensure that
      // the server is not simply trusting everything
      assertInvalidUsernamePassword(username, "Bad Password:" + password);

      String newPassword = "mySecretNewPassword" + randomSuffix();
      PGConnection pgConn = superConn.unwrap(PGConnection.class);
      char[] newPasswordChars = newPassword.toCharArray();
      pgConn.alterUserPassword(username, newPasswordChars, encryptionType);
      Assert.assertNotEquals("newPassword char[] array should be wiped and not match original after encoding", newPassword, String.valueOf(newPasswordChars));
      assertWiped(newPasswordChars);

      // We should be able to log in using our new password
      assertValidUsernamePassword(username, newPassword);
      // We also check that we cannot log in with the wrong password to ensure that
      // the server is not simply trusting everything
      assertInvalidUsernamePassword(username, "Bad Password:" + newPassword);
    } finally {
      try (Connection superConn = TestUtil.openPrivilegedDB()) {
        TestUtil.execute(superConn, "DROP USER " + escapedUsername);
      } catch (Exception ignore) { }
    }
  }

  private void testUserPassword(@Nullable String encryptionType, String username, String password) throws SQLException {
    char[] passwordChars = password.toCharArray();
    String encodedPassword = PasswordUtil.encodePassword(username, passwordChars, encryptionType);
    Assert.assertNotEquals("password char[] array should be wiped and not match original password after encoding", password, String.valueOf(passwordChars));
    assertWiped(passwordChars);
    testUserPassword(encryptionType, username, password, encodedPassword);
  }

  private void testUserPassword(@Nullable String encryptionType) throws SQLException {
    String username = "test_password_" + randomSuffix();
    String password = "t0pSecret" + randomSuffix();

    testUserPassword(encryptionType, username, password);
    testUserPassword(encryptionType, username, "password with spaces");
    testUserPassword(encryptionType, username, "password with single ' quote'");
    testUserPassword(encryptionType, username, "password with double \" quote'");
    testUserPassword(encryptionType, username + " with spaces", password);
    testUserPassword(encryptionType, username + " with single ' quote", password);
    testUserPassword(encryptionType, username + " with single \" quote", password);
  }

  @Test
  public void testServerDefault() throws SQLException {
    String encryptionType;
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      encryptionType = TestUtil.queryForString(conn, "SHOW password_encryption");
    }
    testUserPassword(encryptionType);
  }

  @Test
  @HaveMinimalServerVersion("10.0")
  public void testDriverDefault() throws SQLException {
    testUserPassword(null);
  }

  @Test
  public void testMD5() throws SQLException {
    testUserPassword("md5");
  }

  @Test
  public void testEncryptionTypeValueOfOn() throws SQLException {
    testUserPassword("on");
  }

  @Test
  public void testEncryptionTypeValueOfOff() throws SQLException {
    testUserPassword("off");
  }

  @Test
  @HaveMinimalServerVersion("10.0")
  public void testScramSha256() throws SQLException {
    testUserPassword("scram-sha-256");
  }

  @Test
  @HaveMinimalServerVersion("10.0")
  public void testCustomScramParams() throws SQLException {
    String username = "test_password_" + randomSuffix();
    String password = "t0pSecret" + randomSuffix();
    byte[] salt = new byte[32];
    rng.nextBytes(salt);
    int iterations = 12345;
    String encodedPassword = PasswordUtil.encodeScramSha256(password.toCharArray(), iterations, salt);
    Assert.assertTrue("encoded password should have custom iteration count", encodedPassword.startsWith("SCRAM-SHA-256$" + iterations + ":"));
    testUserPassword("scram-sha-256", username, password, encodedPassword);
  }

  @Test
  public void testUnknownEncryptionType() throws SQLException {
    String username = "test_password_" + randomSuffix();
    String password = "t0pSecret" + randomSuffix();
    char[] passwordChars = password.toCharArray();
    Assert.assertThrows(SQLException.class, () -> {
      PasswordUtil.encodePassword(username, passwordChars, "not-a-real-encryption-type");
    });
    assertWiped(passwordChars);
  }
}
