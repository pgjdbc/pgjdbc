/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertNotNull;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/*
 * Some simple tests to check that the required components needed for JBuilder stay working
 *
 */
public class JBuilderTest {

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    Connection con = TestUtil.openDB();

    TestUtil.createTable(con, "test_c", "source text,cost money,imageid int4");

    TestUtil.closeDB(con);
  }

  // Tear down the fixture for this test case.
  @After
  public void tearDown() throws Exception {
    Connection con = TestUtil.openDB();
    TestUtil.dropTable(con, "test_c");
    TestUtil.closeDB(con);
  }

  /*
   * This tests that Money types work. JDBCExplorer barfs if this fails.
   */
  @Test
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
