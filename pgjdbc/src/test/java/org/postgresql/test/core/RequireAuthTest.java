/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

class RequireAuthTest {

  @Test
  void testRequireAuthAllowPassword() throws SQLException {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "password");

    // This should succeed if server uses password auth
    try (Connection conn = TestUtil.openDB(props)) {
      assertTrue(conn.isValid(5));
    }
  }

  @Test
  void testRequireAuthRejectPassword() {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "!password");

    // This should fail if server uses password auth
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }

  @Test
  void testRequireAuthAllowMd5() throws SQLException {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "md5");

    // This should succeed if server uses md5 auth
    try (Connection conn = TestUtil.openDB(props)) {
      assertTrue(conn.isValid(5));
    }
  }

  @Test
  void testRequireAuthRejectMd5() {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "!md5");

    // This should fail if server uses md5 auth
    assertThrows(PSQLException.class, () -> {
      try (Connection conn = TestUtil.openDB(props)) {
        // Should not reach here
      }
    });
  }

  @Test
  void testRequireAuthAllowNone() throws SQLException {
    Properties props = new Properties();
    PGProperty.REQUIRE_AUTH.set(props, "none");

    // This should succeed if server uses no auth (trust)
    try (Connection conn = TestUtil.openDB(props)) {
      assertTrue(conn.isValid(5));
    }
  }

  @Test
  void testRequireAuthRejectNone() {
    Properties props = new Properties();
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
