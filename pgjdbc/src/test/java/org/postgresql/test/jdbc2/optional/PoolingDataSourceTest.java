/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.ds.common.BaseDataSource;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Minimal tests for pooling DataSource. Needs many more.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PoolingDataSourceTest extends BaseDataSourceTest {
  private static final String DS_NAME = "JDBC 2 SE Test DataSource";

  @Override
  @SuppressWarnings("deprecation")
  public void tearDown() throws SQLException {
    if (bds instanceof org.postgresql.jdbc2.optional.PoolingDataSource) {
      ((org.postgresql.jdbc2.optional.PoolingDataSource) bds).close();
    }
    super.tearDown();
  }

  /**
   * Creates and configures a new SimpleDataSource.
   */
  @Override
  @SuppressWarnings("deprecation")
  protected void initializeDataSource() throws PSQLException {
    if (bds == null) {
      org.postgresql.jdbc2.optional.PoolingDataSource ds =
          new org.postgresql.jdbc2.optional.PoolingDataSource();
      bds = ds;
      setupDataSource(bds);
      ds.setDataSourceName(DS_NAME);
      ds.setInitialConnections(2);
      ds.setMaxConnections(10);
    }
  }

  /**
   * In this case, we *do* want it to be pooled.
   */
  @Override
  public void testNotPooledConnection() throws SQLException {
    con = getDataSourceConnection();
    String name = con.toString();
    con.close();
    con = getDataSourceConnection();
    String name2 = con.toString();
    con.close();
    assertEquals(name, name2, "Pooled DS doesn't appear to be pooling connections!");
  }

  /**
   * In this case, the desired behavior is dereferencing.
   */
  @Override
  protected void compareJndiDataSource(BaseDataSource oldbds, BaseDataSource bds) {
    assertSame(bds, oldbds, "DataSource was serialized or recreated, should have been dereferenced");
  }

  /**
   * Check that 2 DS instances can't use the same name.
   */
  @Test
  @SuppressWarnings("deprecation")
  public void testCantReuseName() throws PSQLException {
    initializeDataSource();
    org.postgresql.jdbc2.optional.PoolingDataSource pds =
        new org.postgresql.jdbc2.optional.PoolingDataSource();
    try {
      pds.setDataSourceName(DS_NAME);
      fail("Should have denied 2nd DataSource with same name");
    } catch (IllegalArgumentException e) {
    }
  }

  /**
   * Closing a Connection twice is not an error.
   */
  @Test
  public void testDoubleConnectionClose() throws SQLException {
    con = getDataSourceConnection();
    con.close();
    con.close();
  }

  /**
   * Closing a Statement twice is not an error.
   */
  @Test
  public void testDoubleStatementClose() throws SQLException {
    con = getDataSourceConnection();
    Statement stmt = con.createStatement();
    stmt.close();
    stmt.close();
    con.close();
  }

  @Test
  public void testConnectionObjectMethods() throws SQLException {
    con = getDataSourceConnection();

    Connection conRef = con;
    assertEquals(con, conRef);

    int hc1 = con.hashCode();
    con.close();
    int hc2 = con.hashCode();

    assertEquals(con, conRef);
    assertEquals(hc1, hc2);
  }

  @Test
  public void testStatementObjectMethods() throws SQLException {
    con = getDataSourceConnection();

    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT 1");
    Statement stmtRef = stmt;

    assertEquals(stmt, stmtRef);
    // Currently we aren't proxying ResultSet, so this doesn't
    // work, see Bug #1010542.
    // assertEquals(stmt, rs.getStatement());

    int hc1 = stmt.hashCode();
    stmt.close();
    int hc2 = stmt.hashCode();

    assertEquals(stmt, stmtRef);
    assertEquals(hc1, hc2);
  }

}
