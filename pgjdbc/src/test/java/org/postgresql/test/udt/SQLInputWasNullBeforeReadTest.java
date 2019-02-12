/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Types;
import java.util.Collections;
import java.util.Map;

public class SQLInputWasNullBeforeReadTest extends BaseTest4 {

  public static class WasNullBeforeReadSQLData implements SQLData {

    private String sqlTypeName;

    @Override
    public String getSQLTypeName() throws SQLException {
      return sqlTypeName;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      if (typeName == null) {
        throw new IllegalArgumentException();
      }
      sqlTypeName = typeName;
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
    result.getObject("test", Collections.<String, Class<?>>singletonMap("int8", WasNullBeforeReadSQLData.class));
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
