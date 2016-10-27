/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;

public class DatabaseMetaDataTest extends TestCase {

  private Connection _conn;

  public DatabaseMetaDataTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    if (TestUtil.haveMinimumServerVersion(_conn, "7.3")) {
      Statement stmt = _conn.createStatement();
      stmt.execute("CREATE DOMAIN mydom AS int");
      stmt.execute("CREATE TABLE domtab (a mydom)");
    }
  }

  protected void tearDown() throws Exception {
    if (TestUtil.haveMinimumServerVersion(_conn, "7.3")) {
      Statement stmt = _conn.createStatement();
      stmt.execute("DROP TABLE domtab");
      stmt.execute("DROP DOMAIN mydom");
    }
    TestUtil.closeDB(_conn);
  }

  public void testGetColumnsForDomain() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(_conn, "7.3")) {
      return;
    }

    DatabaseMetaData dbmd = _conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "domtab", "%");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(Types.DISTINCT, rs.getInt("DATA_TYPE"));
    assertEquals("mydom", rs.getString("TYPE_NAME"));
    assertEquals(Types.INTEGER, rs.getInt("SOURCE_DATA_TYPE"));
    assertTrue(!rs.next());
  }

}
