/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Properties;

public class StringTypeParameterTest extends TestCase {

  private Connection _conn;

  public StringTypeParameterTest(String name) {
    super(name);
  }

  protected boolean setUp(String stringType) throws Exception {
    Properties props = new Properties();
    if (stringType != null) {
      props.put("stringtype", stringType);
    }
    _conn = TestUtil.openDB(props);
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_3)) {
      return false;
    }
    TestUtil.createEnumType(_conn, "mood", "'happy', 'sad'");
    TestUtil.createTable(_conn, "stringtypetest", "m mood");
    return true;
  }

  protected void tearDown() throws SQLException {
    if (_conn != null) {
      TestUtil.dropTable(_conn, "stringtypetest");
      TestUtil.dropType(_conn, "mood");
      TestUtil.closeDB(_conn);
    }
  }

  public void testParameterStringTypeVarchar() throws Exception {
    if (!TestUtil.isProtocolVersion(_conn, 3)) {
      return;
    }
    testParameterVarchar("varchar");
  }

  public void testParameterStringTypeNotSet() throws Exception {
    if (!TestUtil.isProtocolVersion(_conn, 3)) {
      return;
    }
    testParameterVarchar(null);
  }

  private void testParameterVarchar(String param) throws Exception {
    if (!setUp(param)) {
      return;
    }

    PreparedStatement update = _conn.prepareStatement("insert into stringtypetest (m) values (?)");
    update.setString(1, "sad");
    try {
      update.executeUpdate();
      fail("Expected exception thrown");
    } catch (SQLException e) {
      // expected
    }

    update.clearParameters();
    update.setObject(1, "sad", Types.VARCHAR);
    try {
      update.executeUpdate();
      fail("Expected exception thrown");
    } catch (SQLException e) {
      // expected
    }

    update.clearParameters();
    update.setObject(1, "happy", Types.OTHER);
    update.executeUpdate();
    // all good
    update.close();

    PreparedStatement query =
        _conn.prepareStatement("select * from stringtypetest where m = ? or m = ?");
    query.setString(1, "sad");
    try {
      query.executeQuery();
      fail("Expected exception thrown");
    } catch (SQLException e) {
      // expected
    }

    query.clearParameters();
    query.setObject(2, "sad", Types.VARCHAR);
    try {
      query.executeQuery();
      fail("Expected exception thrown");
    } catch (SQLException e) {
      // expected
    }

    query.clearParameters();
    query.setObject(1, "happy", Types.OTHER);
    ResultSet rs = query.executeQuery();
    assertTrue(rs.next());
    assertEquals("happy", rs.getObject("m"));

    // all good
    rs.close();
    query.close();

  }

  public void testParameterUnspecified() throws Exception {
    if (!setUp("unspecified")) {
      return;
    }

    PreparedStatement update = _conn.prepareStatement("insert into stringtypetest (m) values (?)");
    update.setString(1, "happy");
    update.executeUpdate();
    // all good

    update.clearParameters();
    update.setObject(1, "happy", Types.VARCHAR);
    update.executeUpdate();
    // all good
    update.close();

    PreparedStatement query = _conn.prepareStatement("select * from stringtypetest where m = ?");
    query.setString(1, "happy");
    ResultSet rs = query.executeQuery();
    assertTrue(rs.next());
    assertEquals("happy", rs.getObject("m"));
    rs.close();

    query.clearParameters();
    query.setObject(1, "happy", Types.VARCHAR);
    rs = query.executeQuery();
    assertTrue(rs.next());
    assertEquals("happy", rs.getObject("m"));

    // all good
    rs.close();
    query.close();

  }
}
