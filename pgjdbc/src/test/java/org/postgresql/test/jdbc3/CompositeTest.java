/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

class CompositeTest {

  private Connection conn;

  @BeforeAll
  static void beforeClass() throws Exception {
    try (Connection conn = TestUtil.openDB();) {
      assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_3), "uuid requires PostgreSQL 8.3+");
    }
  }

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createSchema(conn, "\"Composites\"");
    TestUtil.createCompositeType(conn, "simplecompositetest", "i int, d decimal, u uuid");
    TestUtil.createCompositeType(conn, "\"Composites\".\"SimpleComposite\"",
        "i int, d decimal, u uuid");
    TestUtil.createCompositeType(conn, "nestedcompositetest", "t text, s simplecompositetest");
    TestUtil.createCompositeType(conn, "\"Composites\".\"ComplexCompositeTest\"",
        "l bigint[], n nestedcompositetest[], s simplecompositetest");
    TestUtil.createTable(conn, "compositetabletest",
        "s simplecompositetest, cc \"Composites\".\"ComplexCompositeTest\"[]");
    TestUtil.createTable(conn, "\"Composites\".\"Table\"",
        "s simplecompositetest, cc \"Composites\".\"ComplexCompositeTest\"[]");
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.dropTable(conn, "\"Composites\".\"Table\"");
    TestUtil.dropTable(conn, "compositetabletest");
    TestUtil.dropType(conn, "\"Composites\".\"ComplexCompositeTest\"");
    TestUtil.dropType(conn, "nestedcompositetest");
    TestUtil.dropType(conn, "simplecompositetest");
    TestUtil.dropType(conn, "\"Composites\".\"SimpleComposite\"");
    TestUtil.dropSchema(conn, "\"Composites\"");
    TestUtil.closeDB(conn);
  }

  @Test
  void simpleSelect() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("SELECT '(1,2.2,)'::simplecompositetest");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo = (PGobject) rs.getObject(1);
    assertEquals("simplecompositetest", pgo.getType());
    assertEquals("(1,2.2,)", pgo.getValue());
  }

  @Test
  void testAddType() throws SQLException {
    PGConnection pgconn = conn.unwrap(PGConnection.class);
    pgconn.addDataType("\"Composites\".\"SimpleComposite\"", SimpleComposite.class);
    PreparedStatement pstmt = conn.prepareStatement("SELECT '(1,2.2,a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11)'::\"Composites\".\"SimpleComposite\"");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Object simpleComposite = rs.getObject(1);
    assertEquals(SimpleComposite.class, simpleComposite.getClass());
    assertEquals("(1,2.2,a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11)", ((SimpleComposite)simpleComposite).getValue());
  }

  @Test
  void complexSelect() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement(
        "SELECT '(\"{1,2}\",{},\"(1,2.2,)\")'::\"Composites\".\"ComplexCompositeTest\"");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo = (PGobject) rs.getObject(1);
    assertEquals("\"Composites\".\"ComplexCompositeTest\"", pgo.getType());
    assertEquals("(\"{1,2}\",{},\"(1,2.2,)\")", pgo.getValue());
  }

  @Test
  void simpleArgumentSelect() throws SQLException {
    assumeTrue(conn.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE, "Skip if running in simple query mode");
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?");
    PGobject pgo = new PGobject();
    pgo.setType("simplecompositetest");
    pgo.setValue("(1,2.2,)");
    pstmt.setObject(1, pgo);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo2 = (PGobject) rs.getObject(1);
    assertEquals(pgo, pgo2);
  }

  @Test
  void complexArgumentSelect() throws SQLException {
    assumeTrue(conn.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE, "Skip if running in simple query mode");
    PreparedStatement pstmt = conn.prepareStatement("SELECT ?");
    PGobject pgo = new PGobject();
    pgo.setType("\"Composites\".\"ComplexCompositeTest\"");
    pgo.setValue("(\"{1,2}\",{},\"(1,2.2,)\")");
    pstmt.setObject(1, pgo);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo2 = (PGobject) rs.getObject(1);
    assertEquals(pgo, pgo2);
  }

  @Test
  void compositeFromTable() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO compositetabletest VALUES(?, ?)");
    PGobject pgo1 = new PGobject();
    pgo1.setType("public.simplecompositetest");
    pgo1.setValue("(1,2.2,)");
    pstmt.setObject(1, pgo1);
    String[] ctArr = new String[1];
    ctArr[0] = "(\"{1,2}\",{},\"(1,2.2,)\")";
    Array pgarr1 = conn.createArrayOf("\"Composites\".\"ComplexCompositeTest\"", ctArr);
    pstmt.setArray(2, pgarr1);
    int res = pstmt.executeUpdate();
    assertEquals(1, res);
    pstmt = conn.prepareStatement("SELECT * FROM compositetabletest");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo2 = (PGobject) rs.getObject(1);
    Array pgarr2 = (Array) rs.getObject(2);
    assertEquals("simplecompositetest", pgo2.getType());
    assertEquals("\"Composites\".\"ComplexCompositeTest\"", pgarr2.getBaseTypeName());
    Object[] pgobjarr2 = (Object[]) pgarr2.getArray();
    assertEquals(1, pgobjarr2.length);
    PGobject arr2Elem = (PGobject) pgobjarr2[0];
    assertEquals("\"Composites\".\"ComplexCompositeTest\"", arr2Elem.getType());
    assertEquals("(\"{1,2}\",{},\"(1,2.2,)\")", arr2Elem.getValue());
    rs.close();
    pstmt = conn.prepareStatement("SELECT c FROM compositetabletest c");
    rs = pstmt.executeQuery();
    assertTrue(rs.next());
    PGobject pgo3 = (PGobject) rs.getObject(1);
    assertEquals("compositetabletest", pgo3.getType());
    assertEquals("(\"(1,2.2,)\",\"{\"\"(\\\\\"\"{1,2}\\\\\"\",{},\\\\\"\"(1,2.2,)\\\\\"\")\"\"}\")",
        pgo3.getValue());
  }

  @Test
  void nullArrayElement() throws SQLException {
    PreparedStatement pstmt =
        conn.prepareStatement("SELECT array[NULL, NULL]::compositetabletest[]");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    Array arr = rs.getArray(1);
    assertEquals("compositetabletest", arr.getBaseTypeName());
    Object[] items = (Object[]) arr.getArray();
    assertEquals(2, items.length);
    assertNull(items[0]);
    assertNull(items[1]);
  }

  @Test
  void tableMetadata() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO compositetabletest VALUES(?, ?)");
    PGobject pgo1 = new PGobject();
    pgo1.setType("public.simplecompositetest");
    pgo1.setValue("(1,2.2,)");
    pstmt.setObject(1, pgo1);
    String[] ctArr = new String[1];
    ctArr[0] = "(\"{1,2}\",{},\"(1,2.2,)\")";
    Array pgarr1 = conn.createArrayOf("\"Composites\".\"ComplexCompositeTest\"", ctArr);
    pstmt.setArray(2, pgarr1);
    int res = pstmt.executeUpdate();
    assertEquals(1, res);
    pstmt = conn.prepareStatement("SELECT t FROM compositetabletest t");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    String name = rs.getMetaData().getColumnTypeName(1);
    assertEquals("compositetabletest", name);
  }

  @Test
  void complexTableNameMetadata() throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO \"Composites\".\"Table\" VALUES(?, ?)");
    PGobject pgo1 = new PGobject();
    pgo1.setType("public.simplecompositetest");
    pgo1.setValue("(1,2.2,)");
    pstmt.setObject(1, pgo1);
    String[] ctArr = new String[1];
    ctArr[0] = "(\"{1,2}\",{},\"(1,2.2,)\")";
    Array pgarr1 = conn.createArrayOf("\"Composites\".\"ComplexCompositeTest\"", ctArr);
    pstmt.setArray(2, pgarr1);
    int res = pstmt.executeUpdate();
    assertEquals(1, res);
    pstmt = conn.prepareStatement("SELECT t FROM \"Composites\".\"Table\" t");
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next());
    String name = rs.getMetaData().getColumnTypeName(1);
    assertEquals("\"Composites\".\"Table\"", name);
  }
}
