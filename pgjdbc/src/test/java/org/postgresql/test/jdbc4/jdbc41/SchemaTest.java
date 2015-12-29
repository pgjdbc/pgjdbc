/*-------------------------------------------------------------------------
*
* Copyright (c) 2010-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc4.jdbc41;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class SchemaTest extends TestCase {

  private Connection _conn;

  private boolean dropUserSchema;

  public SchemaTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
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
  }

  protected void tearDown() throws SQLException {
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
   * Test that what you set is what you get
   */
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
   * Test that get schema returns the schema with the highest priority in the search path
   */
  public void testMultipleSearchPath() throws SQLException {
    execute("SET search_path TO schema1,schema2");
    assertEquals("schema1", _conn.getSchema());

    execute("SET search_path TO \"schema ,6\",schema2");
    assertEquals("schema ,6", _conn.getSchema());
  }

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

}
