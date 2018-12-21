/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Types;
import java.util.Collections;
import java.util.Map;

public class SQLInputWasNullBeforeReadTest {
  private Connection con;

  // Set up the fixture for this testcase: the tables for this test.
  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
  }

  // Tear down the fixture for this test case.
  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(con);
  }

  public static class WasNullBeforeReadSQLData implements SQLData {

    @Override
    public String getSQLTypeName() throws SQLException {
      return WasNullBeforeReadSQLData.class.getSimpleName();
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      stream.wasNull();
      stream.readLong();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeLong(0x0ddba11);
    }
  }

  @Test(expected = SQLException.class)
  public void testWasNullBeforeReadWithConnectionMapDirect() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int8", WasNullBeforeReadSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int8 AS test");
    result.next();
    result.getObject("test");
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testWasNullBeforeReadWithConnectionMapInferred() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("WasNullBeforeReadSQLData", WasNullBeforeReadSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int8 AS test");
    result.next();
    result.getObject("test", WasNullBeforeReadSQLData.class);
  }
  //#endif

  @Test(expected = SQLException.class)
  public void testWasNullBeforeReadWithGetObjectMap() throws Exception {
    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int8 AS test");
    result.next();
    result.getObject("test", Collections.singletonMap("int8", WasNullBeforeReadSQLData.class));
  }

  @Test
  public void testWrite() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int8");
    pstmt.setObject(1, new WasNullBeforeReadSQLData());
    pstmt.executeQuery();
  }

  @Test
  public void testWriteWithType() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int8");
    pstmt.setObject(1, new WasNullBeforeReadSQLData(), Types.OTHER);
    pstmt.executeQuery();
  }

  @Test
  public void testWriteNoCast() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?");
    pstmt.setObject(1, new WasNullBeforeReadSQLData());
    pstmt.executeQuery();
  }

  @Test
  public void testWriteWithTypeNoCast() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?");
    pstmt.setObject(1, new WasNullBeforeReadSQLData(), Types.OTHER);
    pstmt.executeQuery();
  }
}
