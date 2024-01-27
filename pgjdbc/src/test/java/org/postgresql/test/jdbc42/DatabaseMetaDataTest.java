/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

class DatabaseMetaDataTest {

  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createSchema(conn, "test_schema");
    TestUtil.createEnumType(conn, "test_schema.test_enum", "'val'");
    TestUtil.createTable(conn, "test_schema.off_path_table", "var test_schema.test_enum[]");
    TestUtil.createEnumType(conn, "_test_enum", "'evil'");
    TestUtil.createEnumType(conn, "test_enum", "'other'");
    TestUtil.createTable(conn, "on_path_table", "a test_schema.test_enum[], b _test_enum, c test_enum[]");
    TestUtil.createTable(conn, "decimaltest", "a decimal, b decimal(10, 5)");
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.dropTable(conn, "decimaltest");
    TestUtil.dropTable(conn, "on_path_table");
    TestUtil.dropType(conn, "test_enum");
    TestUtil.dropType(conn, "_test_enum");
    TestUtil.dropSchema(conn, "test_schema");
    TestUtil.closeDB(conn);
  }

  @Test
  void getColumnsForNullScale() throws Exception {
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

    assertFalse(rs.next());
  }

  @Test
  void getCorrectSQLTypeForOffPathTypes() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "off_path_table", "%");
    assertTrue(rs.next());
    assertEquals("var", rs.getString("COLUMN_NAME"));
    assertEquals("\"test_schema\".\"_test_enum\"", rs.getString("TYPE_NAME"), "Detects correct off-path type name");
    assertEquals(Types.ARRAY, rs.getInt("DATA_TYPE"), "Detects correct SQL type for off-path types");

    assertFalse(rs.next());
  }

  @Test
  void getCorrectSQLTypeForShadowedTypes() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "on_path_table", "%");

    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals("\"test_schema\".\"_test_enum\"", rs.getString("TYPE_NAME"), "Correctly maps types from other schemas");
    assertEquals(Types.ARRAY, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    // = TYPE _test_enum AS ENUM ('evil')
    assertEquals("_test_enum", rs.getString("TYPE_NAME"));
    assertEquals(Types.VARCHAR, rs.getInt("DATA_TYPE"));

    assertTrue(rs.next());
    assertEquals("c", rs.getString("COLUMN_NAME"));
    // = array of TYPE test_enum AS ENUM ('value')
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v16)) {
      assertEquals("_test_enum_1", rs.getString("TYPE_NAME"), "Correctly detects shadowed array type name");
    } else {
      assertEquals("___test_enum", rs.getString("TYPE_NAME"), "Correctly detects shadowed array type name");
    }
    assertEquals(Types.ARRAY, rs.getInt("DATA_TYPE"), "Correctly detects type of shadowed name");

    assertFalse(rs.next());
  }

  @Test
  void largeOidIsHandledCorrectly() throws SQLException {
    TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();

    try {
      ti.getSQLType((int) 4294967295L); // (presumably) unused OID 4294967295, which is 2**32 - 1
    } catch (PSQLException ex) {
      assertEquals(ex.getSQLState(), PSQLState.NO_DATA.getState());
    }
  }

  @Test
  void oidConversion() throws SQLException {
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

  @Test
  void oidConversionThrowsForNegativeLongValues() throws SQLException {
    assertThrows(PSQLException.class, () -> {
      TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();
      ti.longOidToInt(-1);
    });
  }

  @Test
  void oidConversionThrowsForTooLargeLongValues() throws SQLException {
    assertThrows(PSQLException.class, () -> {
      TypeInfo ti = conn.unwrap(PgConnection.class).getTypeInfo();
      ti.longOidToInt(1L << 32);
    });
  }
}
