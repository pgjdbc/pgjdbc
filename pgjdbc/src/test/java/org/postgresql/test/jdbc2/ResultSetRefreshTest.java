/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.Test;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class ResultSetRefreshTest extends BaseTest4 {
  @Test
  public void testWithDataColumnThatRequiresEscaping() throws Exception {
    TestUtil.dropTable(con, "refresh_row_bad_ident");
    TestUtil.execute(con, "CREATE TABLE refresh_row_bad_ident (id int PRIMARY KEY, \"1 FROM refresh_row_bad_ident; SELECT 2; SELECT *\" int)");
    TestUtil.execute(con, "INSERT INTO refresh_row_bad_ident (id) VALUES (1), (2), (3)");

    Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = stmt.executeQuery("SELECT * FROM refresh_row_bad_ident");
    assertTrue(rs.next());
    try {
      rs.refreshRow();
    } catch (SQLException ex) {
      throw new RuntimeException("ResultSet.refreshRow() did not handle escaping data column identifiers", ex);
    }
    rs.close();
    stmt.close();
  }

  @Test
  public void testWithKeyColumnThatRequiresEscaping() throws Exception {
    TestUtil.dropTable(con, "refresh_row_bad_ident");
    TestUtil.execute(con, "CREATE TABLE refresh_row_bad_ident (\"my key\" int PRIMARY KEY)");
    TestUtil.execute(con, "INSERT INTO refresh_row_bad_ident VALUES (1), (2), (3)");

    Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
    ResultSet rs = stmt.executeQuery("SELECT * FROM refresh_row_bad_ident");
    assertTrue(rs.next());
    try {
      rs.refreshRow();
    } catch (SQLException ex) {
      throw new RuntimeException("ResultSet.refreshRow() did not handle escaping key column identifiers", ex);
    }
    rs.close();
    stmt.close();
  }
}
