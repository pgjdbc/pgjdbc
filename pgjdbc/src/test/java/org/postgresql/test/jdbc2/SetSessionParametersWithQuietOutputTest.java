/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class SetSessionParametersWithQuietOutputTest {

  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("quietOutput", "true");
    con = TestUtil.openDB(props);
    TestUtil.createTempTable(con, "test_statement", "i int");
    TestUtil.createTempTable(con, "test_trigger_source", "i int");
    TestUtil.createTempTable(con, "test_trigger_value", "i int");
    TestUtil.createSchema(con, "another_schema_than_public");
    TestUtil.createTable(con, "another_schema_than_public.test_statement_in_another_schema", "i int");
    Statement stmt = con.createStatement();
    stmt.close();
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.dropTable(con, "test_statement");
    TestUtil.dropTable(con, "test_trigger_source");
    TestUtil.dropTable(con, "test_trigger_value");
    TestUtil.dropSchema(con, "another_schema_than_public");
    TestUtil.closeDB(con);
  }

  @Test
  void testUpdateCountForInsertWithLocalVariable() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2));
    try (Statement stmt = con.createStatement()) {
      //Given
      int localVariable = 42;
      //When
      int count = stmt.executeUpdate("BEGIN;"
          + "SET LOCAL var.test_value=" + localVariable + ";"
          + "INSERT INTO test_statement VALUES (current_setting('var.test_value')::int);"
          + "COMMIT");
      //Then
      assertEquals(1, count, "Insert should return the update count");
      assertEquals(1, stmt.getUpdateCount(), "Insert should return the update count");
    }
  }

  @Test
  void testUpdateCountForUpdateWithLocalVariable() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2));
    try (Statement stmt = con.createStatement()) {
      //Given
      stmt.execute("INSERT INTO test_statement VALUES (1), (2), (3)");
      int localVariable = 42;
      //When
      int count = stmt.executeUpdate("BEGIN;"
          + "SET LOCAL var.test_value=" + localVariable + ";"
          + "UPDATE test_statement SET i=current_setting('var.test_value')::int WHERE i>0;"
          + "COMMIT");
      //Then
      assertEquals(3, count, "Update should return the update count");
      assertEquals(3, stmt.getUpdateCount(), "Update should return the update count");
    }
  }

  @Test
  void testUpdateCountForDeleteWithLocalVariable() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2));
    try (Statement stmt = con.createStatement()) {
      //Given
      stmt.execute("INSERT INTO test_statement VALUES (1), (2), (3)");
      int localVariable = 42;
      //When
      int count = stmt.executeUpdate("BEGIN;"
          + "SET LOCAL var.test_value=" + localVariable + ";"
          + "DELETE FROM test_statement WHERE i<=current_setting('var.test_value')::int;"
          + "COMMIT");
      //Then
      assertEquals(3, count, "Delete should return the update count");
      assertEquals(3, stmt.getUpdateCount(), "Delete should return the update count");
    }
  }

  @Test
  public void testUpdateCountForMultipleStatementsWithSearchPath() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      int count;
      count = stmt.executeUpdate("SET search_path = another_schema_than_public;"
          + "INSERT INTO test_statement_in_another_schema VALUES (1);"
          + "INSERT INTO test_statement_in_another_schema VALUES (2);"
          + "INSERT INTO test_statement_in_another_schema VALUES (3)");
      assertEquals(1, count, "Multiple inserts should return the last update count");

      count = stmt.executeUpdate("SET search_path = another_schema_than_public;"
          + "UPDATE test_statement_in_another_schema SET i=2 WHERE i=1;"
          + "UPDATE test_statement_in_another_schema SET i=4 WHERE i=3");
      assertEquals(1, count, "Multiple updates should return the last update count");

      count = stmt.executeUpdate("SET search_path = another_schema_than_public;"
          + "UPDATE test_statement_in_another_schema SET i=2");
      assertEquals(3, count, "Multiple updates should return the last update count");

      count = stmt.executeUpdate("SET search_path = another_schema_than_public;"
          + "DELETE FROM test_statement_in_another_schema WHERE i > 0");
      assertEquals(3, count, "Multiple deletes should return the last update count");

      count = stmt.executeUpdate("SET search_path TO public;"
          + "INSERT INTO test_statement VALUES (1);"
          + "SET search_path TO another_schema_than_public;"
          + "INSERT INTO test_statement_in_another_schema VALUES (1);"
          + "SET search_path TO public;"
          + "UPDATE test_statement SET i=2 WHERE i=1;"
          + "SET search_path TO another_schema_than_public;"
          + "UPDATE test_statement_in_another_schema SET i=2 WHERE i=1;"
          + "DELETE FROM test_statement_in_another_schema WHERE i=2;"
          + "SET search_path TO public;");
      assertEquals(1, count, "Multiple deletes should return the last update count");
    }
  }

  @Test
  public void testSingleSelectStatementsWithSetBefore() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2));
    try (Statement stmt = con.createStatement()) {
      con.setAutoCommit(false);
      stmt.execute("INSERT INTO test_statement SELECT * FROM GENERATE_SERIES(1, 10)");
      ResultSet resultSet = stmt.executeQuery("BEGIN;"
          + "SET search_path = 'public';"
          + "SET LOCAL var.test_value=42;"
          + "SELECT * FROM test_statement WHERE i<current_setting('var.test_value')::int;"
          + "COMMIT");
      int count = 0;
      while (resultSet.next()) {
        count++;
      }
      resultSet.close();
      assertEquals(10, count, "Single select should return all rows");
      assertFalse(stmt.getMoreResults(), "There should be no more results");
      assertEquals(-1, stmt.getUpdateCount(), "There should be no update count");
    }
  }

  @Test
  public void testMultipleSelectStatementsWithSetPathBefore() throws SQLException {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("INSERT INTO test_statement SELECT * FROM GENERATE_SERIES(1, 10)");
      boolean ok = stmt.execute("SET search_path = 'public';"
          + "SET search_path = 'public';"
          + "SELECT * FROM test_statement WHERE i>5;"
          + "SET search_path = 'public';"
          + "SELECT * FROM test_statement WHERE i<=5;"
          + "SET search_path = 'public';");
      assertTrue(ok);
      ResultSet resultSet = stmt.getResultSet();
      int count = 0;
      while (resultSet.next()) {
        count++;
      }
      resultSet.close();
      assertEquals(5, count, "First select should return the first SELECT");
      assertTrue(stmt.getMoreResults(), "There should be more results");
      resultSet = stmt.getResultSet();
      count = 0;
      while (resultSet.next()) {
        count++;
      }
      resultSet.close();
      assertEquals(5, count, "Second select should return last SELECT");
      assertFalse(stmt.getMoreResults(), "There should be no more results");
      assertEquals(-1, stmt.getUpdateCount(), "There should be no update count");
    }
  }

  @Test
  public void testSetLocalUserSetting() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_6));
    try (Statement stmt = con.createStatement()) {
      assertThrows(SQLException.class, () -> stmt.executeQuery("SELECT current_setting('var.test', FALSE)"));
      stmt.execute("SET SESSION var.test='session';");
      ResultSet rs = stmt.executeQuery("BEGIN;"
          + "SET LOCAL var.test='local';"
          + "SELECT current_setting('var.test', FALSE)");
      assertTrue(rs.next());
      assertEquals("local", rs.getString(1), "Current setting should be 'test'");
      rs.close();
    }
  }

  @Test
  public void testSetSessionUserSetting() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_6));
    try (Statement stmt = con.createStatement()) {
      ResultSet rs = stmt.executeQuery("SET SESSION var.test='test';"
          + "SELECT current_setting('var.test', FALSE);"
          + "SET SESSION var.test='another';");
      assertTrue(rs.next());
      assertEquals("test", rs.getString(1), "Current setting should be returned");
      rs.close();
      assertFalse(stmt.getMoreResults(), "There should be no more results");
      assertEquals(-1, stmt.getUpdateCount(), "There should be no update count");
    }
  }

  @Test
  public void testTriggerWithLocalUserSetting() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2));
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DROP TRIGGER IF EXISTS update_on_change_trigger ON test_trigger_source");
      stmt.execute("CREATE OR REPLACE FUNCTION update_on_change() RETURNS TRIGGER AS $$\n"
          + "BEGIN\n"
          + "  INSERT INTO test_trigger_value VALUES (current_setting('var.test_value')::int);\n"
          + "  RETURN NEW;\n"
          + "END;\n"
          + "$$ LANGUAGE plpgsql;\n"
          + "CREATE TRIGGER update_on_change_trigger\n"
          + "AFTER INSERT ON test_trigger_source\n"
          + "FOR EACH ROW EXECUTE PROCEDURE update_on_change()");
      assertEquals(1, stmt.executeUpdate("BEGIN;"
          + "SET LOCAL var.test_value=42;"
          + "INSERT INTO test_trigger_source VALUES (1);"
          + "COMMIT"), "Insert should return the update count");
      ResultSet rs = stmt.executeQuery("SELECT i FROM test_trigger_value ORDER BY i");
      assertTrue(rs.next());
      assertEquals(42, rs.getInt(1), "Trigger should have been executed with the local setting");
      rs.close();
    }
  }

  @Test
  public void testSelectWithLocalUserSetting() throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2));
    try (Statement stmt = con.createStatement()) {
      con.setAutoCommit(false);
      assertEquals(10, stmt.executeUpdate("BEGIN;"
          + "SET LOCAL var.nb_test=10;"
          + "SET LOCAL var.test_value=42;"
          + "INSERT INTO test_statement"
          + " SELECT current_setting('var.test_value')::int"
          + " FROM generate_series(1, current_setting('var.nb_test')::int);"
          + "COMMIT"), "Insert should return the update count");
      ResultSet rs = stmt.executeQuery("SELECT i FROM test_statement ORDER BY i");
      for (int i = 1; i <= 10; i++) {
        assertTrue(rs.next());
        assertEquals(42, rs.getInt(1), "Current setting should be 42");
      }
      rs.close();
    }
  }
}
