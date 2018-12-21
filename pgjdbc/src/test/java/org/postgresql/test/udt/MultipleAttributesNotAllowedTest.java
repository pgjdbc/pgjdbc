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

/**
 * Currently, user-defined data types may only have a single attribute, and must have that one attribute.
 * This tests the enforcement of these constraints.
 */
public class MultipleAttributesNotAllowedTest {
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

  public static class TwoAttributeSQLData implements SQLData {

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
      stream.readLong();
      stream.readBoolean();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeLong(0x0ddba11);
      stream.writeBoolean(true);
    }
  }

  @Test(expected = SQLException.class)
  public void testTwoReadWithConnectionMapDirect() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int8", TwoAttributeSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int8 AS test");
    result.next();
    result.getObject("test");
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testTwoReadWithConnectionMapInferred() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("SomeTypeMappedToNoAttributeSQLData", TwoAttributeSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int8 AS test");
    result.next();
    result.getObject("test", TwoAttributeSQLData.class);
  }
  //#endif

  @Test(expected = SQLException.class)
  public void testTwoReadWithGetObjectMap() throws Exception {
    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int8 AS test");
    result.next();
    result.getObject("test", Collections.<String, Class<?>>singletonMap("int8", TwoAttributeSQLData.class));
  }

  @Test(expected = SQLException.class)
  public void testTwoWrite() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int8");
    pstmt.setObject(1, new TwoAttributeSQLData());
  }

  @Test(expected = SQLException.class)
  public void testTwoWriteWithType() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int8");
    pstmt.setObject(1, new TwoAttributeSQLData(), Types.OTHER);
  }
}
