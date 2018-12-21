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
public class SingleAttributeRequiredTest {
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

  public static class NoAttributeSQLData implements SQLData {

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
      // Does not read anything
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      // Does not write anything
    }
  }

  @Test(expected = SQLException.class)
  public void testNoReadWithConnectionMapDirect() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("text", NoAttributeSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 'test'::text AS test");
    result.next();
    result.getObject("test");
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testNoReadWithConnectionMapInferred() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("SomeTypeMappedToNoAttributeSQLData", NoAttributeSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 'test'::text AS test");
    result.next();
    result.getObject("test", NoAttributeSQLData.class);
  }
  //#endif

  @Test(expected = SQLException.class)
  public void testNoReadWithGetObjectMap() throws Exception {
    ResultSet result = con.createStatement().executeQuery("SELECT 'test'::text AS test");
    result.next();
    result.getObject("test", Collections.<String, Class<?>>singletonMap("text", NoAttributeSQLData.class));
  }

  @Test(expected = SQLException.class)
  public void testNoWrite() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::text");
    pstmt.setObject(1, new NoAttributeSQLData());
  }

  @Test(expected = SQLException.class)
  public void testNoWriteWithType() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::text");
    pstmt.setObject(1, new NoAttributeSQLData(), Types.OTHER);
  }
}
