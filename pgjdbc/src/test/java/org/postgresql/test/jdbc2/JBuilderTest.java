/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/*
* Some simple tests to check that the required components needed for JBuilder stay working
*
*/
class JBuilderTest {

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "test_c", "source text,cost money,imageid int4");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "test_c");
    }
  }

  /*
   * This tests that Money types work. JDBCExplorer barfs if this fails.
   */
  @Test
  void money() throws Exception {
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
