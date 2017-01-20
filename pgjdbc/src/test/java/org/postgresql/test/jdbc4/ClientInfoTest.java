/*
 * Copyright (c) 2010, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class ClientInfoTest {

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(_conn);
  }

  private String getAppName() throws SQLException {
    Statement stmt = _conn.createStatement();
    ResultSet rs = stmt.executeQuery("SHOW application_name");
    rs.next();
    String appName = rs.getString(1);
    rs.close();
    stmt.close();
    return appName;
  }

  @Test
  public void testSetAppName() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v9_0)) {
      return;
    }

    _conn.setClientInfo("ApplicationName", "my app");
    assertEquals("my app", getAppName());
    assertEquals("my app", _conn.getClientInfo("ApplicationName"));
    assertEquals("my app", _conn.getClientInfo().getProperty("ApplicationName"));
  }

  @Test
  public void testExplicitSetAppNameNotificationIsParsed() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v9_0)) {
      return;
    }

    String appName = "test-42";

    Statement s = _conn.createStatement();
    s.execute("set application_name='" + appName + "'");
    s.close();
    assertEquals("application_name was set to " + appName + ", and it should be visible via "
        + "con.getClientInfo", appName, _conn.getClientInfo("ApplicationName"));
    assertEquals("application_name was set to " + appName + ", and it should be visible via "
        + "con.getClientInfo", appName, _conn.getClientInfo().get("ApplicationName"));
  }

  @Test
  public void testSetAppNameProps() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v9_0)) {
      return;
    }

    Properties props = new Properties();
    props.put("ApplicationName", "my app");
    _conn.setClientInfo(props);
    assertEquals("my app", getAppName());
    assertEquals("my app", _conn.getClientInfo("ApplicationName"));
    assertEquals("my app", _conn.getClientInfo().getProperty("ApplicationName"));
  }

  /**
   * Test that no exception is thrown when an unknown property is set
   */
  @Test
  public void testWarningOnUnknownName() throws SQLException {
    try {
      _conn.setClientInfo("UnexisingClientInfoName", "NoValue");
    } catch (SQLClientInfoException e) {
      fail("Trying to set an unexisting name must not throw an exception (spec)");
    }
    assertTrue(_conn.getWarnings() != null);
  }

  /**
   * Test that a name missing in the properties given to setClientInfo should be unset (spec)
   */
  @Test
  public void testMissingName() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v9_0)) {
      return;
    }

    _conn.setClientInfo("ApplicationName", "my app");

    // According to the spec, empty properties must clear all (because all names are missing)
    _conn.setClientInfo(new Properties());

    String applicationName = _conn.getClientInfo("ApplicationName");
    assertTrue("".equals(applicationName) || applicationName == null);
  }
}
