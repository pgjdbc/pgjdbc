/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;

public class DatabaseMetaDataTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE DOMAIN mydom AS int");
    stmt.execute("CREATE TABLE domtab (a mydom)");
  }

  @After
  public void tearDown() throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("DROP TABLE domtab");
    stmt.execute("DROP DOMAIN mydom");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testGetColumnsForDomain() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "domtab", "%");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(Types.DISTINCT, rs.getInt("DATA_TYPE"));
    assertEquals("mydom", rs.getString("TYPE_NAME"));
    assertEquals(Types.INTEGER, rs.getInt("SOURCE_DATA_TYPE"));
    assertTrue(!rs.next());
  }

}
