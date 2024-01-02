/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;

class LogServerMessagePropertyTest {
  private static final String PRIMARY_KEY_NAME = "lms_test_pk";
  private static final String CREATE_TABLE_SQL =
      "CREATE TABLE pg_temp.lms_test ("
      + "  id text, "
      + "  CONSTRAINT " + PRIMARY_KEY_NAME + " PRIMARY KEY (id)"
      + ")";
  private static final String SECRET_VALUE = "some_secret_value";
  private static final String INSERT_SQL =
      "INSERT INTO pg_temp.lms_test (id) VALUES ('" + SECRET_VALUE + "')";

  /**
   * Creates a connection with the additional properties, use it to
   * create a temp table with a primary key, run two inserts to generate
   * a duplicate key error, and finally return the exception message.
   */
  private static String testViolatePrimaryKey(Properties props, boolean batch) throws SQLException {
    Connection conn = TestUtil.openDB(props);
    Assumptions.assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_1));
    try {
      TestUtil.execute(conn, CREATE_TABLE_SQL);
      if (batch) {
        PreparedStatement stmt = conn.prepareStatement(INSERT_SQL);
        stmt.addBatch();
        stmt.addBatch();
        stmt.executeBatch();
      } else {
        // First insert should work
        TestUtil.execute(conn, INSERT_SQL);
        // Second insert should throw a duplicate key error
        TestUtil.execute(conn, INSERT_SQL);
      }
    } catch (SQLException e) {
      assertEquals(PSQLState.UNIQUE_VIOLATION.getState(), e.getSQLState(), "SQL state must be for a unique violation");
      return e.getMessage();
    } finally {
      conn.close();
    }
    // Should never get here:
    fail("A duplicate key exception should have occurred");
    return null;
  }

  private static String testViolatePrimaryKey(Properties props) throws SQLException {
    return testViolatePrimaryKey(props, false);
  }

  private static void assertMessageContains(String message, String text) {
    if (!message.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
      fail(String.format("Message must contain text '%s': %s", text, message));
    }
  }

  private static void assertMessageDoesNotContain(String message, String text) {
    if (message.toLowerCase(Locale.ROOT).contains(text.toLowerCase(Locale.ROOT))) {
      fail(String.format("Message must not contain text '%s': %s", text, message));
    }
  }

  @Test
  void withDefaults() throws SQLException {
    Properties props = new Properties();
    String message = testViolatePrimaryKey(props);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    // TODO: Detail is locale-specific assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  /**
   * NOTE: This should be the same as the default case as "true" is the default.
   */
  @Test
  void withExplicitlyEnabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "true");
    String message = testViolatePrimaryKey(props);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    // TODO: Detail is locale-specific assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  @Test
  void withLogServerErrorDetailDisabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "false");
    String message = testViolatePrimaryKey(props);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    assertMessageDoesNotContain(message, "Detail:");
    assertMessageDoesNotContain(message, SECRET_VALUE);
  }

  @Test
  void batchWithDefaults() throws SQLException {
    Properties props = new Properties();
    String message = testViolatePrimaryKey(props, true);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    // TODO: Detail is locale-specific assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  /**
   * NOTE: This should be the same as the default case as "true" is the default.
   */
  @Test
  void batchExplicitlyEnabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "true");
    String message = testViolatePrimaryKey(props, true);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    // TODO: Detail is locale-specific assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  @Test
  void batchWithLogServerErrorDetailDisabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "false");
    String message = testViolatePrimaryKey(props, true);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    // TODO: Detail is locale-specific assertMessageDoesNotContain(message, "Detail:");
    assertMessageDoesNotContain(message, SECRET_VALUE);
  }
}
