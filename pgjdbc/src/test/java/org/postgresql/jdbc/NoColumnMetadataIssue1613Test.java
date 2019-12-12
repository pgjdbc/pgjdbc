/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Before;
import org.junit.Test;

import java.sql.ResultSet;
import java.sql.Statement;

/**
 * If the SQL query has no column metadata, the driver shouldn't break by a null pointer exception.
 * It should return the result correctly.
 *
 * @author Ivy (ivyyiyideng@gmail.com)
 *
 */
public class NoColumnMetadataIssue1613Test extends BaseTest4 {
  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "test_no_column_metadata","id int");
  }

  @Test
  public void shouldBeNoNPE() throws Exception {
    Statement statement = con.createStatement();
    statement.execute("INSERT INTO test_no_column_metadata values (1)");
    ResultSet rs = statement.executeQuery("SELECT x FROM test_no_column_metadata x");
    assertTrue(rs.next());
  }
}
