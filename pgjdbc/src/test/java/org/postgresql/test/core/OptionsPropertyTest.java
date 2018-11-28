/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

public class OptionsPropertyTest {
  private static final String schemaName = "options_property_test";
  private static final String optionsValue = "-c search_path=" + schemaName;

  @Before
  public void setUp() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();
    stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + ";");
    stmt.execute("CREATE SCHEMA " + schemaName + ";");
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  public void testOptionsInProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.OPTIONS.getName(), optionsValue);

    Connection con = TestUtil.openDB(props);
    Statement stmt = con.createStatement();
    stmt.execute("SHOW search_path");

    ResultSet rs = stmt.getResultSet();
    if (!rs.next()) {
      Assert.fail("'options' connection initialization parameter should be passed to the database.");
    }
    Assert.assertEquals("'options' connection initialization parameter should be passed to the database.", schemaName, rs.getString(1));

    stmt.close();
    TestUtil.closeDB(con);
  }

  @After
  public void tearDown() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();
    stmt.execute("DROP SCHEMA " + schemaName + ";");
    stmt.close();
    TestUtil.closeDB(con);
  }
}
