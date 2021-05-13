/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;

public class LogServerMessagePropertyTest {
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
    Assume.assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_1));
    try {
      TestUtil.execute(CREATE_TABLE_SQL, conn);
      if (batch) {
        PreparedStatement stmt = conn.prepareStatement(INSERT_SQL);
        stmt.addBatch();
        stmt.addBatch();
        stmt.executeBatch();
      } else {
        // First insert should work
        TestUtil.execute(INSERT_SQL, conn);
        // Second insert should throw a duplicate key error
        TestUtil.execute(INSERT_SQL, conn);
      }
    } catch (SQLException e) {
      Assert.assertEquals("SQL state must be for a unique violation", PSQLState.UNIQUE_VIOLATION.getState(), e.getSQLState());
      return e.getMessage();
    } finally {
      conn.close();
    }
    // Should never get here:
    Assert.fail("A duplicate key exception should have occurred");
    return null;
  }

  private static String testViolatePrimaryKey(Properties props) throws SQLException {
    return testViolatePrimaryKey(props, false);
  }

  private static void assertMessageContains(String message, String text) {
    if (message.toLowerCase().indexOf(text.toLowerCase()) < 0) {
      Assert.fail(String.format("Message must contain text '%s': %s", text, message));
    }
  }

  private static void assertMessageDoesNotContain(String message, String text) {
    if (message.toLowerCase().indexOf(text.toLowerCase()) >= 0) {
      Assert.fail(String.format("Message must not contain text '%s': %s", text, message));
    }
  }

  @Test
  public void testWithDefaults() throws SQLException {
    Properties props = new Properties();
    String message = testViolatePrimaryKey(props);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  /**
   * NOTE: This should be the same as the default case as "true" is the default.
   */
  @Test
  public void testWithExplicitlyEnabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "true");
    String message = testViolatePrimaryKey(props);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  @Test
  public void testWithLogServerErrorDetailDisabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "false");
    String message = testViolatePrimaryKey(props);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    assertMessageDoesNotContain(message, "Detail:");
    assertMessageDoesNotContain(message, SECRET_VALUE);
  }

  @Test
  public void testBatchWithDefaults() throws SQLException {
    Properties props = new Properties();
    String message = testViolatePrimaryKey(props, true);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  /**
   * NOTE: This should be the same as the default case as "true" is the default.
   */
  @Test
  public void testBatchExplicitlyEnabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "true");
    String message = testViolatePrimaryKey(props, true);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    assertMessageContains(message, "Detail:");
    assertMessageContains(message, SECRET_VALUE);
  }

  @Test
  public void testBatchWithLogServerErrorDetailDisabled() throws SQLException {
    Properties props = new Properties();
    props.setProperty(PGProperty.LOG_SERVER_ERROR_DETAIL.getName(), "false");
    String message = testViolatePrimaryKey(props, true);
    assertMessageContains(message, PRIMARY_KEY_NAME);
    assertMessageDoesNotContain(message, "Detail:");
    assertMessageDoesNotContain(message, SECRET_VALUE);
  }
}
