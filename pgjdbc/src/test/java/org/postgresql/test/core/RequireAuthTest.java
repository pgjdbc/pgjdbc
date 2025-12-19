/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.test.TestUtil.TEST_URL_PROPERTY_PREFIX;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

class RequireAuthTest {

  @BeforeAll
  static void beforeAll() {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      conn.createStatement().execute("create user nobody with password 'none'");
      conn.createStatement().execute("create user pword with password 'password'");
      conn.createStatement().execute("create user scram with password 'scram'");
      conn.createStatement().execute("create database authtest owner nobody");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @AfterAll
  static void afterAll() {
    try (Connection conn = TestUtil.openPrivilegedDB()) {
      conn.createStatement().execute("drop database authtest");
      conn.createStatement().execute("drop user scram");
      conn.createStatement().execute("drop user pword");
      conn.createStatement().execute("drop user nobody");
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void testRequireAuthAllowPassword() throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", "pword");
    props.setProperty("password", "password");
    props.setProperty(TEST_URL_PROPERTY_PREFIX + PGProperty.PG_DBNAME.getName(), "authtest");
    PGProperty.REQUIRE_AUTH.set(props, "password");

    // This should succeed if server uses password auth
    try (Connection conn = TestUtil.openDB(props)) {
      assertTrue(conn.isValid(5));
    }
  }

  @Test
  void testRequireAuthRejectPassword() {
    Properties props = new Properties();
    props.setProperty("user", "pword");
    props.setProperty("password", "password");
    props.setProperty(TEST_URL_PROPERTY_PREFIX + PGProperty.PG_DBNAME.getName(), "authtest");

    PGProperty.REQUIRE_AUTH.set(props, "!password");

    // This should fail if server uses password auth
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }

  @Test
  void testRequireAuthAllowNone() throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", "nobody");
    props.setProperty("password", "none");
    props.setProperty(TEST_URL_PROPERTY_PREFIX + PGProperty.PG_DBNAME.getName(), "authtest");

    PGProperty.REQUIRE_AUTH.set(props, "none");

    // This should succeed if server uses no auth (trust)
    try (Connection conn = TestUtil.openDB(props)) {
      assertTrue(conn.isValid(5));
    }
  }

  @Test
  void testRequireAuthRejectNone() {
    Properties props = new Properties();
    props.setProperty("user", "nobody");
    props.setProperty("password", "none");
    props.setProperty(TEST_URL_PROPERTY_PREFIX + PGProperty.PG_DBNAME.getName(), "authtest");

    PGProperty.REQUIRE_AUTH.set(props, "!none");

    // This should fail if server uses no auth (trust)
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }

  @Test
  void testRequireAuthMultipleAllowed() throws SQLException {
    Properties props = new Properties();
    props.setProperty("user", "scram");
    props.setProperty("password", "scram");
    props.setProperty(TEST_URL_PROPERTY_PREFIX + PGProperty.PG_DBNAME.getName(), "authtest");

    PGProperty.REQUIRE_AUTH.set(props, "password,md5,scram-sha-256");

    // This should succeed if server uses any of the allowed methods
    try (Connection conn = TestUtil.openDB(props)) {
      assertTrue(conn.isValid(5));
    }
  }

  @Test
  void testRequireAuthMultipleRejected() {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "!password,!scram-sha-256");
    props.setProperty("user", "pword");
    props.setProperty("password", "password");

    // This should fail if server uses password or md5 auth
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }

  @Test
  void testRequireAuthMixedAllowRejectThrowsException() {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "md5,!password");

    // This should throw exception due to mixing positive and negative options
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }

  @Test
  void testRequireAuthDuplicateThrowsException() {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "md5,password,md5");

    // This should throw exception due to duplicate authentication method
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }

  @Test
  void testRequireAuthInvalidMethodThrowsException() {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "invalid,md5");

    // This should throw exception due to invalid authentication method
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }
}
