/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertNotNull;

import org.postgresql.PGStatement;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 *
 * @author kib
 */
public class Binary2StringTest extends BaseTest4 {

  private PreparedStatement statement;

  @Before
  public void setUp() throws Exception {
    super.setUp();
    Assume.assumeTrue("Server-prepared statements are not supported in 'simple protocol only'",
        preferQueryMode != PreferQueryMode.EXTENDED_FOR_PREPARED);
    TestUtil.createTable(con, "testGeometric", "p point");
    Statement stmt = con.createStatement();
    stmt.execute("insert into testGeometric(p) values ('(1,1)'),('(2,2)')");
    statement = con.prepareStatement("select p from testGeometric");

    ((PGStatement) statement).setPrepareThreshold(-1);
  }

  @After
  public void tearDown() {
    try {
      TestUtil.dropTable(con, "testGeometric");
    } catch (SQLException e) {
      // ignore
    }
  }

  @Test
  public void test() throws Exception {
    ResultSet rs = statement.executeQuery();
    while (rs.next()) {
      String p = rs.getString("p");
      assertNotNull("Failed to convert to String", p);
    }
  }

}
