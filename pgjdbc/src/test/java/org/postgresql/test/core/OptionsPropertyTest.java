/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

class OptionsPropertyTest {
  private static final String schemaName = "options_property_test";
  private static final String optionsValue = "-c search_path=" + schemaName;

  @BeforeEach
  void setUp() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();
    stmt.execute("DROP SCHEMA IF EXISTS " + schemaName + ";");
    stmt.execute("CREATE SCHEMA " + schemaName + ";");
    stmt.close();
    TestUtil.closeDB(con);
  }

  @Test
  void optionsInProperties() throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.OPTIONS.getName(), optionsValue);

    Connection con = TestUtil.openDB(props);
    Statement stmt = con.createStatement();
    stmt.execute("SHOW search_path");

    ResultSet rs = stmt.getResultSet();
    if (!rs.next()) {
      fail("'options' connection initialization parameter should be passed to the database.");
    }
    assertEquals(schemaName, rs.getString(1), "'options' connection initialization parameter should be passed to the database.");

    stmt.close();
    TestUtil.closeDB(con);
  }

  @AfterEach
  void tearDown() throws Exception {
    Connection con = TestUtil.openDB();
    Statement stmt = con.createStatement();
    stmt.execute("DROP SCHEMA " + schemaName + ";");
    stmt.close();
    TestUtil.closeDB(con);
  }
}
