/*-------------------------------------------------------------------------
*
* Copyright (c) 2010-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class ClientInfoTest extends TestCase {

  private Connection _conn;

  public ClientInfoTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
  }

  protected void tearDown() throws SQLException {
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

  public void testSetAppName() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, "9.0")) {
      return;
    }

    _conn.setClientInfo("ApplicationName", "my app");
    assertEquals("my app", getAppName());
    assertEquals("my app", _conn.getClientInfo("ApplicationName"));
    assertEquals("my app", _conn.getClientInfo().getProperty("ApplicationName"));
  }

  public void testSetAppNameProps() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, "9.0")) {
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
  public void testMissingName() throws SQLException {
    if (!TestUtil.haveMinimumServerVersion(_conn, "9.0")) {
      return;
    }

    _conn.setClientInfo("ApplicationName", "my app");

    // According to the spec, empty properties must clear all (because all names are missing)
    _conn.setClientInfo(new Properties());

    String applicationName = _conn.getClientInfo("ApplicationName");
    assertTrue("".equals(applicationName) || applicationName == null);
  }
}
