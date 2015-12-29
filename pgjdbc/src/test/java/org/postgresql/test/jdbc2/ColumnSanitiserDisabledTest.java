/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2014, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.test.jdbc2;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/*
 * This test suite will check the behaviour of the findColumnIndex
 * method. This is testing the behaviour when sanitiser is disabled.
 */
public class ColumnSanitiserDisabledTest extends TestCase {
  private Connection conn;

  public ColumnSanitiserDisabledTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty("disableColumnSanitiser", Boolean.TRUE.toString());
    conn = TestUtil.openDB(props);
    assertTrue(conn instanceof BaseConnection);
    BaseConnection bc = (BaseConnection) conn;
    assertTrue(
        "Expected state [TRUE] of base connection configuration failed test."
        , bc.isColumnSanitiserDisabled());
        /* Quoted columns will be stored with case preserved. Driver will
         * receive column names as defined in db server. */
    TestUtil.createTable(conn, "allmixedup",
        "id int primary key, \"DESCRIPTION\" varchar(40), \"fOo\" varchar(3)");
    Statement data = conn.createStatement();
    data.execute(TestUtil.insertSQL("allmixedup",
        "1,'mixed case test', 'bar'"));
    data.close();
  }

  protected void tearDown() throws Exception {
    TestUtil.dropTable(conn, "allmixedup");
    TestUtil.closeDB(conn);
    System.setProperty("disableColumnSanitiser", "false");
  }

    /*
     * Test cases checking different combinations of columns origination from
     * database against application supplied column names.
     */

  public void testTableColumnLowerNowFindFindLowerCaseColumn()
      throws SQLException {
    findColumn("id", true);
  }

  public void testTableColumnLowerNowFindFindUpperCaseColumn()
      throws SQLException {
    findColumn("ID", true);
  }

  public void testTableColumnLowerNowFindFindMixedCaseColumn()
      throws SQLException {
    findColumn("Id", false);
  }

  public void testTableColumnUpperNowFindFindLowerCaseColumn()
      throws SQLException {
    findColumn("description", true);
  }

  public void testTableColumnUpperNowFindFindUpperCaseColumn()
      throws SQLException {
    findColumn("DESCRIPTION", true);
  }

  public void testTableColumnUpperNowFindFindMixedCaseColumn()
      throws SQLException {
    findColumn("Description", false);
  }

  public void testTableColumnMixedNowFindLowerCaseColumn()
      throws SQLException {
    findColumn("foo", false);
  }

  public void testTableColumnMixedNowFindFindUpperCaseColumn()
      throws SQLException {
    findColumn("FOO", false);
  }

  public void testTableColumnMixedNowFindFindMixedCaseColumn()
      throws SQLException {
    findColumn("fOo", true);
  }

  private void findColumn(String label, boolean failOnNotFound)
      throws SQLException {
    PreparedStatement query = conn
        .prepareStatement("select * from allmixedup");
    if (0 == TestUtil.findColumn(query, label) && failOnNotFound) {
      fail(String
          .format("Expected to find the column with the label [%1$s].",
              label));
    }
    query.close();
  }
}
