/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

public class DatabaseMetaDataTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createSchema(conn, "test_schema");
    TestUtil.createEnumType(conn, "test_schema.test_enum", "'val'");
    TestUtil.createTable(conn, "test_schema.off_path_table", "var test_schema.test_enum[]");
    TestUtil.createEnumType(conn, "_test_enum", "'evil'");
    TestUtil.createEnumType(conn, "test_enum", "'other'");
    TestUtil.createTable(conn, "on_path_table", "a test_schema.test_enum[], b _test_enum, c test_enum[]");
    TestUtil.createTable(conn, "decimaltest", "a decimal, b decimal(10, 5)");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(conn, "decimaltest");
    TestUtil.dropTable(conn, "on_path_table");
    TestUtil.dropType(conn, "test_enum");
    TestUtil.dropType(conn, "_test_enum");
    TestUtil.dropSchema(conn, "test_schema");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testGetColumnsForNullScale() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "decimaltest", "%");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals(0, rs.getInt("DECIMAL_DIGITS"));
    assertTrue(rs.wasNull());

    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    assertEquals(5, rs.getInt("DECIMAL_DIGITS"));
    assertFalse(rs.wasNull());

    assertTrue(!rs.next());
  }

  @Test
  public void testGetCorrectSQLTypeForOffPathTypes() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "off_path_table", "%");
    assertTrue(rs.next());
    assertEquals("var", rs.getString("COLUMN_NAME"));
    assertEquals("Detects correct off-path type name", "\"test_schema\".\"_test_enum\"", rs.getString("TYPE_NAME"));
    assertEquals("Detects correct SQL type for off-path types", Types.ARRAY, rs.getInt("DATA_TYPE"));

    assertFalse(rs.next());
  }

  @Test
  public void testGetCorrectSQLTypeForShadowedTypes() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "on_path_table", "%");

    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals("Correctly maps types from other schemas","\"test_schema\".\"_test_enum\"", rs.getString("TYPE_NAME"));
    assertEquals(Types.ARRAY, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    // = TYPE _test_enum AS ENUM ('evil')
    assertEquals( "_test_enum", rs.getString("TYPE_NAME"));
    assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("c", rs.getString("COLUMN_NAME"));
    // = array of TYPE test_enum AS ENUM ('value')
    assertEquals("Correctly detects shadowed array type name","___test_enum", rs.getString("TYPE_NAME"));
    assertEquals("Correctly detects type of shadowed name", Types.ARRAY, rs.getInt("DATA_TYPE"));

    assertFalse(rs.next());
  }

  @Test
  public void testLargeOidIsHandledCorrectly() throws SQLException {
    TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();

    try {
      ti.getSQLType((int) 4294967295L); // (presumably) unused OID 4294967295, which is 2**32 - 1
    } catch (PSQLException ex) {
      assertEquals(ex.getSQLState(), PSQLState.NO_DATA.getState());
    }
  }

  @Test
  public void testOidConversion() throws SQLException {
    TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();
    int oid = 0;
    long loid = 0;
    assertEquals(oid, ti.longOidToInt(loid));
    assertEquals(loid, ti.intOidToLong(oid));

    oid = Integer.MAX_VALUE;
    loid = Integer.MAX_VALUE;
    assertEquals(oid, ti.longOidToInt(loid));
    assertEquals(loid, ti.intOidToLong(oid));

    oid = Integer.MIN_VALUE;
    loid = 1L << 31;
    assertEquals(oid, ti.longOidToInt(loid));
    assertEquals(loid, ti.intOidToLong(oid));

    oid = -1;
    loid = 0xFFFFFFFFL;
    assertEquals(oid, ti.longOidToInt(loid));
    assertEquals(loid, ti.intOidToLong(oid));
  }

  @Test(expected = PSQLException.class)
  public void testOidConversionThrowsForNegativeLongValues() throws SQLException {
    TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();
    ti.longOidToInt(-1);
  }

  @Test(expected = PSQLException.class)
  public void testOidConversionThrowsForTooLargeLongValues() throws SQLException {
    TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();
    ti.longOidToInt(1L << 32);
  }
}
