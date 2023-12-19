/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PasswordUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class PasswordUtilTest {
  Connection con;

  @Before
  public void setUp() throws Exception {
    Properties properties = new Properties();
    PGProperty.PREFER_QUERY_MODE.set(properties, "simple");
    con = TestUtil.openDB(properties);
  }

  @After
  public void tearDown() throws SQLException {
    con.close();
  }

  @Test
  public void testGetEncryption() throws SQLException {
    assertEquals("scram-sha-256", PasswordUtil.getEncryption(con));
    setEncryption("md5");
    assertEquals("md5", PasswordUtil.getEncryption(con));
    setEncryption("\"scram-sha-256\"");
  }

  @Test
  public void testScramPassword() throws SQLException {
    PasswordUtil.alterPassword(con, TestUtil.getUser(), TestUtil.getPassword(), null);
    con.close();
    con = TestUtil.openDB();
  }

  private void setEncryption(String encryption) throws SQLException {
    try (Statement statement = con.createStatement()) {
      statement.execute("set password_encryption to " + encryption);
    }
  }
}
