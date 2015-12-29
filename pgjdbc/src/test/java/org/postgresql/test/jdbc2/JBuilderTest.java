/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/*
 * Some simple tests to check that the required components needed for JBuilder stay working
 *
 */
public class JBuilderTest extends TestCase {

  public JBuilderTest(String name) {
    super(name);
  }

  // Set up the fixture for this testcase: the tables for this test.
  protected void setUp() throws Exception {
    Connection con = TestUtil.openDB();

    TestUtil.createTable(con, "test_c", "source text,cost money,imageid int4");

    TestUtil.closeDB(con);
  }

  // Tear down the fixture for this test case.
  protected void tearDown() throws Exception {
    Connection con = TestUtil.openDB();
    TestUtil.dropTable(con, "test_c");
    TestUtil.closeDB(con);
  }

  /*
   * This tests that Money types work. JDBCExplorer barfs if this fails.
   */
  public void testMoney() throws Exception {
    Connection con = TestUtil.openDB();

    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("select cost from test_c");
    assertNotNull(rs);

    while (rs.next()) {
      rs.getDouble(1);
    }

    rs.close();
    st.close();

    TestUtil.closeDB(con);
  }
}
