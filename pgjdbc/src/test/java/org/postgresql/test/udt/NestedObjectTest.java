/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.udt;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
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

public class NestedObjectTest {
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

  public static class NestedObjectSQLData implements SQLData {

    private int value;

    public NestedObjectSQLData() {}

    public NestedObjectSQLData(int value) {
      this.value = value;
    }

    @Override
    public String getSQLTypeName() throws SQLException {
      return NestedObjectSQLData.class.getSimpleName();
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      value = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(value);
    }
  }

  public static class ContainerSQLData implements SQLData {

    private NestedObjectSQLData object;

    public ContainerSQLData() {}

    public ContainerSQLData(NestedObjectSQLData object) {
      this.object = object;
    }

    @Override
    public String getSQLTypeName() throws SQLException {
      return ContainerSQLData.class.getSimpleName();
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      object = (NestedObjectSQLData)stream.readObject();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeObject(object);
    }
  }

  public static class ContainerSQLDataWithType implements SQLData {

    private NestedObjectSQLData object;

    public ContainerSQLDataWithType() {}

    public ContainerSQLDataWithType(NestedObjectSQLData object) {
      this.object = object;
    }

    @Override
    public String getSQLTypeName() throws SQLException {
      return ContainerSQLData.class.getSimpleName();
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      object = stream.readObject(NestedObjectSQLData.class);
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeObject(object);
    }
  }

  @Test(expected = SQLException.class)
  public void testContainerSQLDataWithConnectionMapDirect() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int4", ContainerSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int4 AS test");
    result.next();
    result.getObject("test");
  }

  @Test(expected = SQLException.class)
  public void testContainerSQLDataWithTypeWithConnectionMapDirect() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int4", ContainerSQLDataWithType.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int4 AS test");
    result.next();
    ContainerSQLDataWithType data = (ContainerSQLDataWithType)result.getObject("test");
    Assert.assertEquals(1234, data.object.value);
  }

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testContainerSQLDataWithConnectionMapInferred() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("ContainerSQLData", ContainerSQLData.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int4 AS test");
    result.next();
    result.getObject("test", ContainerSQLData.class);
  }
  //#endif

  //#if mvn.project.property.postgresql.jdbc.spec >= "JDBC4.1"
  @Test(expected = SQLException.class)
  public void testContainerSQLDataWithTypeWithConnectionMapInferred() throws Exception {
    Map<String, Class<?>> typemap = con.getTypeMap();
    typemap.put("int4", NestedObjectSQLData.class);
    typemap.put(NestedObjectSQLData.class.getSimpleName(), ContainerSQLDataWithType.class);
    con.setTypeMap(typemap);

    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int4 AS test");
    result.next();
    ContainerSQLDataWithType data = (ContainerSQLDataWithType)result.getObject("test", ContainerSQLDataWithType.class);
    Assert.assertEquals(1234, data.object.value);
  }
  //#endif

  @Test(expected = SQLException.class)
  public void testContainerSQLDataWithGetObjectMap() throws Exception {
    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int4 AS test");
    result.next();
    result.getObject("test", Collections.singletonMap("int4", ContainerSQLData.class));
  }

  @Test(expected = SQLException.class)
  public void testContainerSQLDataWithTypeWithGetObjectMap() throws Exception {
    ResultSet result = con.createStatement().executeQuery("SELECT 1234::int4 AS test");
    result.next();
    ContainerSQLDataWithType data = (ContainerSQLDataWithType)result.getObject("test", Collections.singletonMap("int4", ContainerSQLDataWithType.class));
    Assert.assertEquals(1234, data.object.value);
  }

  @Test
  public void testWriteContainerSQLData() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int4");
    pstmt.setObject(1, new ContainerSQLData(new NestedObjectSQLData(4562)));
    ResultSet result = pstmt.executeQuery();
    result.next();
    Assert.assertEquals(4562, result.getInt(1));
  }

  @Test
  public void testWriteContainerSQLDataNull() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int4");
    pstmt.setObject(1, new ContainerSQLData());
    ResultSet result = pstmt.executeQuery();
    result.next();
    Assert.assertEquals(0, result.getInt(1));
    Assert.assertTrue(result.wasNull());
  }

  @Test
  public void testWriteContainerSQLDataWithType() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int4");
    pstmt.setObject(1, new ContainerSQLData(new NestedObjectSQLData(4125443)), Types.OTHER);
    ResultSet result = pstmt.executeQuery();
    result.next();
    Assert.assertEquals(4125443, result.getInt(1));
  }

  @Test
  public void testWriteContainerSQLDataWithTypeNull() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?::int4");
    pstmt.setObject(1, new ContainerSQLData(), Types.OTHER);
    ResultSet result = pstmt.executeQuery();
    result.next();
    Assert.assertEquals(0, result.getInt(1));
    Assert.assertTrue(result.wasNull());
  }

  @Test
  public void testWriteContainerSQLDataNoCast() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?");
    pstmt.setObject(1, new ContainerSQLData(new NestedObjectSQLData(24356)));
    ResultSet result = pstmt.executeQuery();
    result.next();
    Assert.assertEquals(24356, result.getInt(1));
  }

  @Test
  public void testWriteContainerSQLDataWithTypeNoCast() throws Exception {
    PreparedStatement pstmt = con.prepareStatement("SELECT ?");
    pstmt.setObject(1, new ContainerSQLData(new NestedObjectSQLData(426)), Types.OTHER);
    ResultSet result = pstmt.executeQuery();
    result.next();
    Assert.assertEquals(426, result.getInt(1));
  }
}
