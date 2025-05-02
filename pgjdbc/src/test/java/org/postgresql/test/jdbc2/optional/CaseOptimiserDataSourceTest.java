/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.core.BaseConnection;
import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.jdbc2.optional.SimpleDataSource;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DataSource test to ensure the BaseConnection is configured with column sanitiser disabled.
 */
public class CaseOptimiserDataSourceTest {
  private BaseDataSource bds;
  protected Connection conn;

  @BeforeEach
  void setUp() throws SQLException {
    Connection conn = getDataSourceConnection();
    assertTrue(conn instanceof BaseConnection);
    BaseConnection bc = (BaseConnection) conn;
    assertTrue(bc.isColumnSanitiserDisabled(),
        "Expected state [TRUE] of base connection configuration failed test.");
    Statement insert = conn.createStatement();
    TestUtil.createTable(conn, "allmixedup",
        "id int primary key, \"DESCRIPTION\" varchar(40), \"fOo\" varchar(3)");
    insert.execute(TestUtil.insertSQL("allmixedup", "1,'mixed case test', 'bar'"));
    insert.close();
    conn.close();
  }

  @AfterEach
  void tearDown() throws SQLException {
    Connection conn = getDataSourceConnection();
    Statement drop = conn.createStatement();
    drop.execute("drop table allmixedup");
    drop.close();
    conn.close();
    bds.setDisableColumnSanitiser(false);
  }

  /*
   * Test to ensure a datasource can be configured with the column sanitiser optimisation. This test
   * checks for a side effect of the sanitiser being disabled. The column is not expected to be
   * found.
   */
  @Test
  void dataSourceDisabledSanitiserPropertySucceeds() throws SQLException {
    String label = "FOO";
    Connection conn = getDataSourceConnection();
    PreparedStatement query =
        conn.prepareStatement("select * from allmixedup");
    if (0 < TestUtil.findColumn(query, label)) {
      fail(String.format("Did not expect to find the column with the label [%1$s].", label));
    }
    query.close();
    conn.close();
  }

  /**
   * Gets a connection from the current BaseDataSource.
   */
  protected Connection getDataSourceConnection() throws SQLException {
    if (bds == null) {
      initializeDataSource();
    }
    return bds.getConnection();
  }

  protected void initializeDataSource() throws PSQLException {
    if (bds == null) {
      bds = new SimpleDataSource();
      setupDataSource(bds);
      bds.setDisableColumnSanitiser(true);
    }
  }

  public static void setupDataSource(BaseDataSource bds) throws PSQLException {
    bds.setServerName(TestUtil.getServer());
    bds.setPortNumber(TestUtil.getPort());
    bds.setDatabaseName(TestUtil.getDatabase());
    bds.setUser(TestUtil.getUser());
    bds.setPassword(TestUtil.getPassword());
    bds.setPrepareThreshold(TestUtil.getPrepareThreshold());
    bds.setProtocolVersion(TestUtil.getProtocolVersion());
  }
}
