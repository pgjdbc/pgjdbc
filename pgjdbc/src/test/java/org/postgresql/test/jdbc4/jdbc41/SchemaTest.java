/*
 * Copyright (c) 2010, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;

public class SchemaTest {
  private Connection conn;
  private boolean dropUserSchema;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    Statement stmt = conn.createStatement();
    try {
      stmt.execute("CREATE SCHEMA " + TestUtil.getUser());
      dropUserSchema = true;
    } catch (SQLException e) {
      /* assume schema existed */
    }
    stmt.execute("CREATE SCHEMA schema1");
    stmt.execute("CREATE SCHEMA schema2");
    stmt.execute("CREATE SCHEMA \"schema 3\"");
    stmt.execute("CREATE SCHEMA \"schema \"\"4\"");
    stmt.execute("CREATE SCHEMA \"schema '5\"");
    stmt.execute("CREATE SCHEMA \"schema ,6\"");
    stmt.execute("CREATE SCHEMA \"UpperCase\"");
    TestUtil.createTable(conn, "schema1.table1", "id integer");
    TestUtil.createTable(conn, "schema2.table2", "id integer");
    TestUtil.createTable(conn, "\"UpperCase\".table3", "id integer");
    TestUtil.createTable(conn, "schema1.sptest", "id integer");
    TestUtil.createTable(conn, "schema2.sptest", "id varchar");
  }

  @After
  public void tearDown() throws SQLException {
    conn.setAutoCommit(true);
    conn.setSchema(null);
    Statement stmt = conn.createStatement();
    if (dropUserSchema) {
      stmt.execute("DROP SCHEMA " + TestUtil.getUser() + " CASCADE");
    }
    stmt.execute("DROP SCHEMA schema1 CASCADE");
    stmt.execute("DROP SCHEMA schema2 CASCADE");
    stmt.execute("DROP SCHEMA \"schema 3\" CASCADE");
    stmt.execute("DROP SCHEMA \"schema \"\"4\" CASCADE");
    stmt.execute("DROP SCHEMA \"schema '5\" CASCADE");
    stmt.execute("DROP SCHEMA \"schema ,6\"");
    stmt.execute("DROP SCHEMA \"UpperCase\" CASCADE");
    TestUtil.closeDB(conn);
  }

  /**
   * Test that what you set is what you get.
   */
  @Test
  public void testGetSetSchema() throws SQLException {
    conn.setSchema("schema1");
    assertEquals("schema1", conn.getSchema());
    conn.setSchema("schema2");
    assertEquals("schema2", conn.getSchema());
    conn.setSchema("schema 3");
    assertEquals("schema 3", conn.getSchema());
    conn.setSchema("schema \"4");
    assertEquals("schema \"4", conn.getSchema());
    conn.setSchema("schema '5");
    assertEquals("schema '5", conn.getSchema());
    conn.setSchema("UpperCase");
    assertEquals("UpperCase", conn.getSchema());
  }

  /**
   * Test that setting the schema allows to access objects of this schema without prefix, hide
   * objects from other schemas but doesn't prevent to prefix-access to them.
   */
  @Test
  public void testUsingSchema() throws SQLException {
    Statement stmt = conn.createStatement();
    try {
      try {
        conn.setSchema("schema1");
        stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
        stmt.executeQuery(TestUtil.selectSQL("schema2.table2", "*"));
        try {
          stmt.executeQuery(TestUtil.selectSQL("table2", "*"));
          fail("Objects of schema2 should not be visible without prefix");
        } catch (SQLException e) {
          // expected
        }

        conn.setSchema("schema2");
        stmt.executeQuery(TestUtil.selectSQL("table2", "*"));
        stmt.executeQuery(TestUtil.selectSQL("schema1.table1", "*"));
        try {
          stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
          fail("Objects of schema1 should not be visible without prefix");
        } catch (SQLException e) {
          // expected
        }

        conn.setSchema("UpperCase");
        stmt.executeQuery(TestUtil.selectSQL("table3", "*"));
        stmt.executeQuery(TestUtil.selectSQL("schema1.table1", "*"));
        try {
          stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
          fail("Objects of schema1 should not be visible without prefix");
        } catch (SQLException e) {
          // expected
        }
      } catch (SQLException e) {
        fail("Could not find expected schema elements: " + e.getMessage());
      }
    } finally {
      try {
        stmt.close();
      } catch (SQLException e) {
      }
    }
  }

  /**
   * Test that get schema returns the schema with the highest priority in the search path.
   */
  @Test
  public void testMultipleSearchPath() throws SQLException {
    execute("SET search_path TO schema1,schema2");
    assertEquals("schema1", conn.getSchema());

    execute("SET search_path TO \"schema ,6\",schema2");
    assertEquals("schema ,6", conn.getSchema());
  }

  @Test
  public void testSchemaInProperties() throws Exception {
    Properties properties = new Properties();
    properties.setProperty("currentSchema", "schema1");
    Connection conn = TestUtil.openDB(properties);
    try {
      assertEquals("schema1", conn.getSchema());

      Statement stmt = conn.createStatement();
      stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
      stmt.executeQuery(TestUtil.selectSQL("schema2.table2", "*"));
      try {
        stmt.executeQuery(TestUtil.selectSQL("table2", "*"));
        fail("Objects of schema2 should not be visible without prefix");
      } catch (SQLException e) {
        // expected
      }
    } finally {
      TestUtil.closeDB(conn);
    }
  }

  @Test
  public void testSchemaPath$User() throws Exception {
    execute("SET search_path TO \"$user\",public,schema2");
    assertEquals(TestUtil.getUser(), conn.getSchema());
  }

  private void execute(String sql) throws SQLException {
    Statement stmt = conn.createStatement();
    try {
      stmt.execute(sql);
    } finally {
      try {
        stmt.close();
      } catch (SQLException e) {
      }
    }
  }

  @Test
  public void testSearchPathPreparedStatementAutoCommitFalse() throws SQLException {
    conn.setAutoCommit(false);
    testSearchPathPreparedStatement();
  }

  @Test
  public void testSearchPathPreparedStatementAutoCommitTrue() throws SQLException {
    testSearchPathPreparedStatement();
  }

  @Test
  public void testSearchPathPreparedStatement() throws SQLException {
    execute("set search_path to schema1,public");
    PreparedStatement ps = conn.prepareStatement("select * from sptest");
    for (int i = 0; i < 10; i++) {
      ps.execute();
    }
    assertColType(ps, "sptest should point to schema1.sptest, thus column type should be INT",
        Types.INTEGER);
    ps.close();
    execute("set search_path to schema2,public");
    ps = conn.prepareStatement("select * from sptest");
    assertColType(ps, "sptest should point to schema2.sptest, thus column type should be VARCHAR",
        Types.VARCHAR);
    ps.close();
  }

  @Test
  public void testCurrentSchemaPropertyVisibilityTableDuringFunctionCreation() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty(PGProperty.CURRENT_SCHEMA.getName(), "public,schema1,schema2");
    Connection connection = TestUtil.openDB(properties);

    TestUtil.execute("create table schema1.check_table (test_col text)", connection);
    TestUtil.execute("insert into schema1.check_table (test_col) values ('test_value')", connection);
    TestUtil.execute("create or replace function schema2.check_fun () returns text as $$"
        + " select test_col from check_table"
        + "$$ language sql immutable", connection);
    connection.close();
  }

  @Test
  public void testCurrentSchemaPropertyNotVisibilityTableDuringFunctionCreation() throws SQLException {
    Properties properties = new Properties();
    properties.setProperty(PGProperty.CURRENT_SCHEMA.getName(), "public,schema2");

    try (Connection connection = TestUtil.openDB(properties)) {
      TestUtil.execute("create table schema1.check_table (test_col text)", connection);
      TestUtil.execute("insert into schema1.check_table (test_col) values ('test_value')", connection);
      TestUtil.execute("create or replace function schema2.check_fun (txt text) returns text as $$"
          + " select test_col from check_table"
          + "$$ language sql immutable", connection);
    } catch (PSQLException e) {
      String sqlState = e.getSQLState();
      String message = e.getMessage();
      assertThat("Test creates function in schema 'schema2' and this function try use table \"check_table\" "
            + "from schema 'schema1'. We expect here sql error code - "
            + PSQLState.UNDEFINED_TABLE + ", because search_path does not contains schema 'schema1' and "
            + "postgres does not see table \"check_table\"",
            sqlState,
            equalTo(PSQLState.UNDEFINED_TABLE.getState())
      );
      assertThat(
          "Test creates function in schema 'schema2' and this function try use table \"check_table\" "
              + "from schema 'schema1'. We expect here that sql error message will be contains \"check_table\", "
              + "because search_path does not contains schema 'schema1' and postgres does not see "
              + "table \"check_table\"",
            message,
            containsString("\"check_table\"")
      );
    }
  }

  @Test
  public void testCurrentSchemaPropertyVisibilityFunction() throws SQLException {
    testCurrentSchemaPropertyVisibilityTableDuringFunctionCreation();
    Properties properties = new Properties();
    properties.setProperty(PGProperty.CURRENT_SCHEMA.getName(), "public,schema1,schema2");
    Connection connection = TestUtil.openDB(properties);

    TestUtil.execute("select check_fun()", connection);
    connection.close();
  }

  @Test
  public void testCurrentSchemaPropertyNotVisibilityTableInsideFunction() throws SQLException {
    testCurrentSchemaPropertyVisibilityTableDuringFunctionCreation();
    Properties properties = new Properties();
    properties.setProperty(PGProperty.CURRENT_SCHEMA.getName(), "public,schema2");

    try (Connection connection = TestUtil.openDB(properties)) {
      TestUtil.execute("select check_fun()", connection);
    } catch (PSQLException e) {
      String sqlState = e.getSQLState();
      String message = e.getMessage();
      assertThat("Test call function in schema 'schema2' and this function uses table \"check_table\" "
            + "from schema 'schema1'. We expect here sql error code - " + PSQLState.UNDEFINED_TABLE + ", "
            + "because search_path does not contains schema 'schema1' and postgres does not see table \"check_table\".",
            sqlState,
            equalTo(PSQLState.UNDEFINED_TABLE.getState())
      );
      assertThat(
          "Test call function in schema 'schema2' and this function uses table \"check_table\" "
              + "from schema 'schema1'. We expect here that sql error message will be contains \"check_table\", because "
              + " search_path does not contains schema 'schema1' and postgres does not see table \"check_table\"",
          message,
          containsString("\"check_table\"")
      );
    }
  }

  private void assertColType(PreparedStatement ps, String message, int expected) throws SQLException {
    ResultSet rs = ps.executeQuery();
    ResultSetMetaData md = rs.getMetaData();
    int columnType = md.getColumnType(1);
    assertEquals(message,
        expected, columnType);
    rs.close();
  }

}
