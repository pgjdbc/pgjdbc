/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;

import org.junit.Before;
import org.junit.Test;

import java.sql.ResultSet;
import java.sql.Statement;

/*
 * TestCase to test the internal functionality of org.postgresql.jdbc2.DatabaseMetaData
 *
 */
public class SearchPathLookupTest {
  private BaseConnection con;

  @Before
  public void setUp() throws Exception {
    con = (BaseConnection) TestUtil.openDB();
  }

  // TODO: make @getMetaData() consider search_path as well

  /**
   * This usecase is most common, here the object we are searching for is in the current_schema (the
   * first schema in the search_path).
   */
  @Test
  public void testSearchPathNormalLookup() throws Exception {
    Statement stmt = con.createStatement();
    try {
      TestUtil.createSchema(con, "first_schema");
      TestUtil.createTable(con, "first_schema.x", "first_schema_field_n int4");
      TestUtil.createSchema(con, "second_schema");
      TestUtil.createTable(con, "second_schema.x", "second_schema_field_n text");
      TestUtil.createSchema(con, "third_schema");
      TestUtil.createTable(con, "third_schema.x", "third_schema_field_n float");
      TestUtil.createSchema(con, "last_schema");
      TestUtil.createTable(con, "last_schema.x", "last_schema_field_n text");
      stmt.execute("SET search_path TO third_schema;");
      TypeInfo typeInfo = con.getTypeInfo();
      int OID = typeInfo.getPGType("x");
      ResultSet rs = stmt.executeQuery("SELECT 'third_schema.x'::regtype::oid");
      assertTrue(rs.next());
      assertEquals(OID, rs.getInt(1));
      assertTrue(!rs.next());
      TestUtil.dropSchema(con, "first_schema");
      TestUtil.dropSchema(con, "second_schema");
      TestUtil.dropSchema(con, "third_schema");
      TestUtil.dropSchema(con, "last_schema");
    } finally {
      if (stmt != null) {
        stmt.close();
      }
      TestUtil.closeDB(con);
    }
  }

  /**
   * This usecase is for the situations, when an object is located in a schema, that is in the
   * search_path, but not in the current_schema, for example a public schema or some kind of schema,
   * that is used for keeping utility objects.
   */
  @Test
  public void testSearchPathHiddenLookup() throws Exception {
    Statement stmt = con.createStatement();
    try {
      TestUtil.createSchema(con, "first_schema");
      TestUtil.createTable(con, "first_schema.x", "first_schema_field_n int4");
      TestUtil.createSchema(con, "second_schema");
      TestUtil.createTable(con, "second_schema.y", "second_schema_field_n text");
      TestUtil.createSchema(con, "third_schema");
      TestUtil.createTable(con, "third_schema.x", "third_schema_field_n float");
      TestUtil.createSchema(con, "last_schema");
      TestUtil.createTable(con, "last_schema.y", "last_schema_field_n text");
      stmt.execute("SET search_path TO first_schema, second_schema, last_schema, public;");
      TypeInfo typeInfo = con.getTypeInfo();
      int OID = typeInfo.getPGType("y");
      ResultSet rs = stmt.executeQuery("SELECT 'second_schema.y'::regtype::oid");
      assertTrue(rs.next());
      assertEquals(OID, rs.getInt(1));
      assertTrue(!rs.next());
      TestUtil.dropSchema(con, "first_schema");
      TestUtil.dropSchema(con, "second_schema");
      TestUtil.dropSchema(con, "third_schema");
      TestUtil.dropSchema(con, "last_schema");
    } finally {
      if (stmt != null) {
        stmt.close();
      }
      TestUtil.closeDB(con);
    }
  }

  @Test
  public void testSearchPathBackwardsCompatibleLookup() throws Exception {
    Statement stmt = con.createStatement();
    try {
      TestUtil.createSchema(con, "first_schema");
      TestUtil.createTable(con, "first_schema.x", "first_schema_field int4");
      TestUtil.createSchema(con, "second_schema");
      TestUtil.createTable(con, "second_schema.x", "second_schema_field text");
      TypeInfo typeInfo = con.getTypeInfo();
      int OID = typeInfo.getPGType("x");
      ResultSet rs = stmt
          .executeQuery("SELECT oid FROM pg_type WHERE typname = 'x' ORDER BY oid DESC LIMIT 1");
      assertTrue(rs.next());
      assertEquals(OID, rs.getInt(1));
      assertTrue(!rs.next());
      TestUtil.dropSchema(con, "first_schema");
      TestUtil.dropSchema(con, "second_schema");
    } finally {
      TestUtil.closeDB(con);
    }
  }
}
