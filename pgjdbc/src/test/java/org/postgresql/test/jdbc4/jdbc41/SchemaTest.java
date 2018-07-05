/*
 * Copyright (c) 2010, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4.jdbc41;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.test.TestUtil;

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
  private Connection _conn;
  private boolean dropUserSchema;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
    Statement stmt = _conn.createStatement();
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
    TestUtil.createTable(_conn, "schema1.table1", "id integer");
    TestUtil.createTable(_conn, "schema2.table2", "id integer");
    TestUtil.createTable(_conn, "\"UpperCase\".table3", "id integer");
    TestUtil.createTable(_conn, "schema1.sptest", "id integer");
    TestUtil.createTable(_conn, "schema2.sptest", "id varchar");
  }

  @After
  public void tearDown() throws SQLException {
    _conn.setAutoCommit(true);
    _conn.setSchema(null);
    Statement stmt = _conn.createStatement();
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
    TestUtil.closeDB(_conn);
  }

  /**
   * Test that what you set is what you get.
   */
  @Test
  public void testGetSetSchema() throws SQLException {
    _conn.setSchema("schema1");
    assertEquals("schema1", _conn.getSchema());
    _conn.setSchema("schema2");
    assertEquals("schema2", _conn.getSchema());
    _conn.setSchema("schema 3");
    assertEquals("schema 3", _conn.getSchema());
    _conn.setSchema("schema \"4");
    assertEquals("schema \"4", _conn.getSchema());
    _conn.setSchema("schema '5");
    assertEquals("schema '5", _conn.getSchema());
    _conn.setSchema("UpperCase");
    assertEquals("UpperCase", _conn.getSchema());
  }

  /**
   * Test that setting the schema allows to access objects of this schema without prefix, hide
   * objects from other schemas but doesn't prevent to prefix-access to them.
   */
  @Test
  public void testUsingSchema() throws SQLException {
    Statement stmt = _conn.createStatement();
    try {
      try {
        _conn.setSchema("schema1");
        stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
        stmt.executeQuery(TestUtil.selectSQL("schema2.table2", "*"));
        try {
          stmt.executeQuery(TestUtil.selectSQL("table2", "*"));
          fail("Objects of schema2 should not be visible without prefix");
        } catch (SQLException e) {
          // expected
        }

        _conn.setSchema("schema2");
        stmt.executeQuery(TestUtil.selectSQL("table2", "*"));
        stmt.executeQuery(TestUtil.selectSQL("schema1.table1", "*"));
        try {
          stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
          fail("Objects of schema1 should not be visible without prefix");
        } catch (SQLException e) {
          // expected
        }

        _conn.setSchema("UpperCase");
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
    assertEquals("schema1", _conn.getSchema());

    execute("SET search_path TO \"schema ,6\",schema2");
    assertEquals("schema ,6", _conn.getSchema());
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
    assertEquals(TestUtil.getUser(), _conn.getSchema());
  }

  private void execute(String sql) throws SQLException {
    Statement stmt = _conn.createStatement();
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
    _conn.setAutoCommit(false);
    testSearchPathPreparedStatement();
  }

  @Test
  public void testSearchPathPreparedStatementAutoCommitTrue() throws SQLException {
    testSearchPathPreparedStatement();
  }

  @Test
  public void testSearchPathPreparedStatement() throws SQLException {
    execute("set search_path to schema1,public");
    PreparedStatement ps = _conn.prepareStatement("select * from sptest");
    for (int i = 0; i < 10; i++) {
      ps.execute();
    }
    assertColType(ps, "sptest should point to schema1.sptest, thus column type should be INT",
        Types.INTEGER);
    ps.close();
    execute("set search_path to schema2,public");
    ps = _conn.prepareStatement("select * from sptest");
    assertColType(ps, "sptest should point to schema2.sptest, thus column type should be VARCHAR",
        Types.VARCHAR);
    ps.close();
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
