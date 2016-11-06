/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/*
 * This test suite will check the behaviour of the findColumnIndex method. This is testing the
 * behaviour when sanitiser is disabled.
 */
public class ColumnSanitiserDisabledTest {
  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("disableColumnSanitiser", Boolean.TRUE.toString());
    conn = TestUtil.openDB(props);
    assertTrue(conn instanceof BaseConnection);
    BaseConnection bc = (BaseConnection) conn;
    assertTrue("Expected state [TRUE] of base connection configuration failed test.",
        bc.isColumnSanitiserDisabled());
    /*
     * Quoted columns will be stored with case preserved. Driver will receive column names as
     * defined in db server.
     */
    TestUtil.createTable(conn, "allmixedup",
        "id int primary key, \"DESCRIPTION\" varchar(40), \"fOo\" varchar(3)");
    Statement data = conn.createStatement();
    data.execute(TestUtil.insertSQL("allmixedup", "1,'mixed case test', 'bar'"));
    data.close();
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(conn, "allmixedup");
    TestUtil.closeDB(conn);
    System.setProperty("disableColumnSanitiser", "false");
  }

  /*
   * Test cases checking different combinations of columns origination from database against
   * application supplied column names.
   */

  @Test
  public void testTableColumnLowerNowFindFindLowerCaseColumn() throws SQLException {
    findColumn("id", true);
  }

  @Test
  public void testTableColumnLowerNowFindFindUpperCaseColumn() throws SQLException {
    findColumn("ID", true);
  }

  @Test
  public void testTableColumnLowerNowFindFindMixedCaseColumn() throws SQLException {
    findColumn("Id", false);
  }

  @Test
  public void testTableColumnUpperNowFindFindLowerCaseColumn() throws SQLException {
    findColumn("description", true);
  }

  @Test
  public void testTableColumnUpperNowFindFindUpperCaseColumn() throws SQLException {
    findColumn("DESCRIPTION", true);
  }

  @Test
  public void testTableColumnUpperNowFindFindMixedCaseColumn() throws SQLException {
    findColumn("Description", false);
  }

  @Test
  public void testTableColumnMixedNowFindLowerCaseColumn() throws SQLException {
    findColumn("foo", false);
  }

  @Test
  public void testTableColumnMixedNowFindFindUpperCaseColumn() throws SQLException {
    findColumn("FOO", false);
  }

  @Test
  public void testTableColumnMixedNowFindFindMixedCaseColumn() throws SQLException {
    findColumn("fOo", true);
  }

  private void findColumn(String label, boolean failOnNotFound) throws SQLException {
    PreparedStatement query = conn.prepareStatement("select * from allmixedup");
    if ((TestUtil.findColumn(query, label) == 0) && failOnNotFound) {
      fail(String.format("Expected to find the column with the label [%1$s].", label));
    }
    query.close();
  }
}
