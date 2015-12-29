/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc3;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;

import junit.framework.TestCase;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class CompositeTest extends TestCase {

  private Connection _conn;

  public CompositeTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createSchema(_conn, "\"Composites\"");
    TestUtil.createCompositeType(_conn, "simplecompositetest", "i int, d decimal, u uuid");
    TestUtil.createCompositeType(_conn, "nestedcompositetest", "t text, s simplecompositetest");
    TestUtil.createCompositeType(_conn, "\"Composites\".\"ComplexCompositeTest\"",
        "l bigint[], n nestedcompositetest[], s simplecompositetest");
    TestUtil.createTable(_conn, "compositetabletest",
        "s simplecompositetest, cc \"Composites\".\"ComplexCompositeTest\"[]");
  }

  protected void tearDown() throws SQLException {
    TestUtil.dropTable(_conn, "compositetabletest");
    TestUtil.dropType(_conn, "\"Composites\".\"ComplexCompositeTest\"");
    TestUtil.dropType(_conn, "nestedcompositetest");
    TestUtil.dropType(_conn, "simplecompositetest");
    TestUtil.dropSchema(_conn, "\"Composites\"");
    TestUtil.closeDB(_conn);
  }

  public void testSimpleSelect() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT '(1,2.2,)'::simplecompositetest");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo = (PGobject) rs.getObject(1);
    assertEquals("simplecompositetest", pgo.getType());
    assertEquals("(1,2.2,)", pgo.getValue());
  }

  public void testComplexSelect() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement(
        "SELECT '(\"{1,2}\",{},\"(1,2.2,)\")'::\"Composites\".\"ComplexCompositeTest\"");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo = (PGobject) rs.getObject(1);
    assertEquals("\"Composites\".\"ComplexCompositeTest\"", pgo.getType());
    assertEquals("(\"{1,2}\",{},\"(1,2.2,)\")", pgo.getValue());
  }

  public void testSimpleArgumentSelect() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?");
    PGobject pgo = new PGobject();
    pgo.setType("simplecompositetest");
    pgo.setValue("(1,2.2,)");
    pstmt.setObject(1, pgo);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo2 = (PGobject) rs.getObject(1);
    assertEquals(pgo, pgo2);
  }

  public void testComplexArgumentSelect() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("SELECT ?");
    PGobject pgo = new PGobject();
    pgo.setType("\"Composites\".\"ComplexCompositeTest\"");
    pgo.setValue("(\"{1,2}\",{},\"(1,2.2,)\")");
    pstmt.setObject(1, pgo);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo2 = (PGobject) rs.getObject(1);
    assertEquals(pgo, pgo2);
  }

  public void testCompositeFromTable() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("INSERT INTO compositetabletest VALUES(?, ?)");
    PGobject pgo1 = new PGobject();
    pgo1.setType("public.simplecompositetest");
    pgo1.setValue("(1,2.2,)");
    pstmt.setObject(1, pgo1);
    String[] ctArr = new String[1];
    ctArr[0] = "(\"{1,2}\",{},\"(1,2.2,)\")";
    Array pgarr1 = _conn.createArrayOf("\"Composites\".\"ComplexCompositeTest\"", ctArr);
    pstmt.setArray(2, pgarr1);
    int res = pstmt.executeUpdate();
    assertEquals(1, res);
    pstmt = _conn.prepareStatement("SELECT * FROM compositetabletest");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo2 = (PGobject) rs.getObject(1);
    Array pgarr2 = (Array) rs.getObject(2);
    assertEquals("public.simplecompositetest", pgo2.getType());
    assertEquals("\"Composites\".\"ComplexCompositeTest\"", pgarr2.getBaseTypeName());
    Object[] pgobjarr2 = (Object[]) pgarr2.getArray();
    assertEquals(1, pgobjarr2.length);
    PGobject arr2Elem = (PGobject) pgobjarr2[0];
    assertEquals("\"Composites\".\"ComplexCompositeTest\"", arr2Elem.getType());
    assertEquals("(\"{1,2}\",{},\"(1,2.2,)\")", arr2Elem.getValue());
    rs.close();
    pstmt = _conn.prepareStatement("SELECT c FROM compositetabletest c");
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo3 = (PGobject) rs.getObject(1);
    assertEquals("compositetabletest", pgo3.getType());
    assertEquals("(\"(1,2.2,)\",\"{\"\"(\\\\\"\"{1,2}\\\\\"\",{},\\\\\"\"(1,2.2,)\\\\\"\")\"\"}\")",
        pgo3.getValue());
  }

  public void testNullArrayElement() throws SQLException {
    PreparedStatement pstmt =
        _conn.prepareStatement("SELECT array[NULL, NULL]::compositetabletest[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    assertEquals("compositetabletest", arr.getBaseTypeName());
    Object[] items = (Object[]) arr.getArray();
    assertEquals(2, items.length);
    assertNull(items[0]);
    assertNull(items[1]);
  }
}
