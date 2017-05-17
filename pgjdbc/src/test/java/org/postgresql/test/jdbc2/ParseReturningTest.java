/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

/**
 * Created by davec on 2017-05-16.
 */

import org.postgresql.test.TestUtil;

import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class ParseReturningTest extends BaseTest {
  Statement stmt;

  public ParseReturningTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    super.setUp();

    TestUtil.createTempTable(con, "test_returning", "id serial primary key, address text, returning_allowed bool");
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(con, "testreturning");
    super.tearDown();
  }

  /*
  This actually tests for tables with the word returning and columns with the word returning in them
   */
  @Test
  public void testColumnReturning() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement("insert into test_returning (address,"
        + "\nreturning_allowed) values (?,?)", Statement.RETURN_GENERATED_KEYS);
    pstmt.setString(1, "a");
    pstmt.setBoolean(2, true);
    assertEquals(1,pstmt.executeUpdate());
    ResultSet generatedKeys = pstmt.getGeneratedKeys();
    assertTrue("There should be at least one result", generatedKeys.next());

  }
}
