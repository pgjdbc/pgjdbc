/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.core.Utils;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;
import org.postgresql.util.PasswordUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.Rule;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
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
      assertEquals(user, actualUser, "User should match");
    } catch (SQLException e) {
      throw new RuntimeException("Failed to authenticate using supplied user and password", e);
    }
  }

  private void assertInvalidUsernamePassword(String user, String password) {
    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    assertThrows(SQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        conn.getSchema(); // Do something with conn to appease checkstyle
      }
    }, "User should not be able to authenticate");
  }

  private void assertWiped(char[] passwordChars) {
    char[] expected = Arrays.copyOf(passwordChars, passwordChars.length);
    Arrays.fill(passwordChars, (char) 0);
    assertArrayEquals(expected, passwordChars, "password array should be all zeros after use");
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
      assertEquals(shadowPass, encodedPassword, "pg_shadow value of password must match encoded");

      // We should be able to log in using our new user:
      assertValidUsernamePassword(username, password);
      // We also check that we cannot log in with the wrong password to ensure that
      // the server is not simply trusting everything
      assertInvalidUsernamePassword(username, "Bad Password:" + password);

      String newPassword = "mySecretNewPassword" + randomSuffix();
      PGConnection pgConn = superConn.unwrap(PGConnection.class);
      char[] newPasswordChars = newPassword.toCharArray();
      pgConn.alterUserPassword(username, newPasswordChars, encryptionType);
      assertNotEquals(newPassword, String.valueOf(newPasswordChars), "newPassword char[] array should be wiped and not match original after encoding");
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
    String encodedPassword = PasswordUtil.encodePassword(
        username, passwordChars,
        encryptionType == null ? "md5" : encryptionType);
    assertNotEquals(password, String.valueOf(passwordChars), "password char[] array should be wiped and not match original password after encoding");
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
  void encodePasswordWithServersPasswordEncryption() throws SQLException {
    String encryptionType;
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      encryptionType = TestUtil.queryForString(conn, "SHOW password_encryption");
    }
    testUserPassword(encryptionType);
  }

  @Test
  void alterUserPasswordSupportsNullEncoding() throws SQLException {
    testUserPassword(null);
  }

  @Test
  void mD5() throws SQLException {
    testUserPassword("md5");
  }

  @Test
  void encryptionTypeValueOfOn() throws SQLException {
    testUserPassword("on");
  }

  @Test
  void encryptionTypeValueOfOff() throws SQLException {
    testUserPassword("off");
  }

  @Test
  @HaveMinimalServerVersion("10.0")
  void scramSha256() throws SQLException {
    testUserPassword("scram-sha-256");
  }

  @Test
  @HaveMinimalServerVersion("10.0")
  void customScramParams() throws SQLException {
    String username = "test_password_" + randomSuffix();
    String password = "t0pSecret" + randomSuffix();
    byte[] salt = new byte[32];
    rng.nextBytes(salt);
    int iterations = 12345;
    String encodedPassword = PasswordUtil.encodeScramSha256(password.toCharArray(), iterations, salt);
    assertTrue(encodedPassword.startsWith("SCRAM-SHA-256$" + iterations + ":"), "encoded password should have custom iteration count");
    testUserPassword("scram-sha-256", username, password, encodedPassword);
  }

  @Test
  void unknownEncryptionType() throws SQLException {
    String username = "test_password_" + randomSuffix();
    String password = "t0pSecret" + randomSuffix();
    char[] passwordChars = password.toCharArray();
    assertThrows(SQLException.class, () -> {
      PasswordUtil.encodePassword(username, passwordChars, "not-a-real-encryption-type");
    });
    assertWiped(passwordChars);
  }
}
