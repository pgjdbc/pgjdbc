/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2004-2014, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.test.jdbc2.optional;

import org.postgresql.core.BaseConnection;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.jdbc2.optional.SimpleDataSource;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/*
 * DataSource test to ensure the BaseConnection is configured with column
 * sanitiser disabled.
 */
public class CaseOptimiserDataSourceTest extends TestCase {
  private BaseDataSource bds;
  protected Connection conn;

  protected void initializeDataSource() {
    if (bds == null) {
      bds = new SimpleDataSource();
      setupDataSource(bds);
      bds.setDisableColumnSanitiser(true);
    }
  }

  public void setUp() throws SQLException {
    Connection conn = getDataSourceConnection();
    assertTrue(conn instanceof BaseConnection);
    BaseConnection bc = (BaseConnection) conn;
    assertTrue(
        "Expected state [TRUE] of base connection configuration failed test."
        , bc.isColumnSanitiserDisabled());
    Statement insert = conn.createStatement();
    TestUtil.createTable(conn, "allmixedup",
        "id int primary key, \"DESCRIPTION\" varchar(40), \"fOo\" varchar(3)");
    insert.execute(TestUtil.insertSQL("allmixedup",
        "1,'mixed case test', 'bar'"));
    insert.close();
    conn.close();
  }

  @Override
  public void tearDown() throws SQLException {
    Connection conn = getDataSourceConnection();
    Statement drop = conn.createStatement();
    drop.execute("drop table allmixedup");
    drop.close();
    conn.close();
    bds.setDisableColumnSanitiser(false);
  }

  /*
   * Test to ensure a datasource can be configured with the column sanitiser
   * optimisation. This test checks for a side effect of the sanitiser being
   * disabled. The column is not expected to be found.
   */
  public void testDataSourceDisabledSanitiserPropertySucceeds()
      throws SQLException {
    String label = "FOO";
    PreparedStatement query = getDataSourceConnection().prepareStatement(
        "select * from allmixedup");
    if (0 < TestUtil.findColumn(query, label)) {
      fail(String.format(
          "Did not expect to find the column with the label [%1$s].",
          label));
    }
    query.close();
  }

  /**
   * Gets a connection from the current BaseDataSource
   */
  protected Connection getDataSourceConnection() throws SQLException {
    if (bds == null) {
      initializeDataSource();
    }
    return bds.getConnection();
  }

  public static void setupDataSource(BaseDataSource bds) {
    bds.setServerName(TestUtil.getServer());
    bds.setPortNumber(TestUtil.getPort());
    bds.setDatabaseName(TestUtil.getDatabase());
    bds.setUser(TestUtil.getUser());
    bds.setPassword(TestUtil.getPassword());
    bds.setPrepareThreshold(TestUtil.getPrepareThreshold());
    bds.setLogLevel(TestUtil.getLogLevel());
    bds.setProtocolVersion(TestUtil.getProtocolVersion());
  }

  public CaseOptimiserDataSourceTest(String name) {
    super(name);
  }
}
