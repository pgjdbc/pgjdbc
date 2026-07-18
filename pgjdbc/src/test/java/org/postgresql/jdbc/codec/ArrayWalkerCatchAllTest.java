/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Verifies that "catch-all" element types — those the legacy {@code ArrayDecoding} materialised as a
 * generic {@code Object[]} through the element codec — now decode through the shared codec walker,
 * while element types with a typed legacy decoder the walker cannot reproduce stay on the legacy
 * path. The decoded result must match the historical {@code getArray()} contract either way.
 *
 * <p>Requires a running PostgreSQL server configured via TestUtil.</p>
 */
public class ArrayWalkerCatchAllTest {

  private static Connection conn;
  private static PgCodecContext ctx;

  @BeforeAll
  public static void setUp() throws Exception {
    conn = TestUtil.openDB();
    // jsonb was introduced in PostgreSQL 9.4.
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_4),
        "jsonb requires PostgreSQL 9.4+");
    ctx = conn.unwrap(BaseConnection.class).getCodecContext();
  }

  @AfterAll
  public static void tearDown() throws Exception {
    TestUtil.closeDB(conn);
  }

  private static boolean walks(int arrayOid) throws SQLException {
    PgType arrayType = ctx.getTypeInfo().getPgTypeByOid(arrayOid);
    return ArrayCodec.canDecodeArrayViaWalker(arrayType, ctx);
  }

  @Test
  void catchAllElementTypes_routeThroughWalker() throws SQLException {
    // Legacy decoded these as Object[] via the element codec; the walker reproduces that path.
    assertTrue(walks(Oid.XML_ARRAY), "xml[] should decode via the codec walker");
    assertTrue(walks(Oid.POINT_ARRAY), "point[] should decode via the codec walker");
    assertTrue(walks(Oid.INTERVAL_ARRAY), "interval[] should decode via the codec walker");
    // bit/varbit decode to PGobject[] via the walker (BitCodec parses the binary form).
    assertTrue(walks(Oid.BIT_ARRAY), "bit[] should decode via the codec walker");
    assertTrue(walks(Oid.VARBIT_ARRAY), "varbit[] should decode via the codec walker");
    // json/jsonb decode to String[] via the walker (JsonArrayLeafCodec).
    assertTrue(walks(Oid.JSON_ARRAY), "json[] should decode via the codec walker");
    assertTrue(walks(Oid.JSONB_ARRAY), "jsonb[] should decode via the codec walker");
    // money decodes to Double[] via the walker (MoneyArrayLeafCodec).
    assertTrue(walks(Oid.MONEY_ARRAY), "money[] should decode via the codec walker");
  }

  @Test
  void domainArray_decodesToObjectArrayViaWalker() throws SQLException {
    // A DOMAIN reports sqlType DISTINCT, so its array takes the generic Object[] catch-all of the
    // codec walker (of the base-codec value), matching the legacy MappedTypeObjectArrayDecoder shape.
    // It never reaches the legacy ArrayDecoding fall-back. This holds whatever the base type is.
    // Arrays of domains were only added in PostgreSQL 11; earlier servers reject `domain[]`.
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11),
        "arrays of domains require PostgreSQL 11+");
    TestUtil.createDomain(conn, "test_dom_vc", "varchar");
    TestUtil.createDomain(conn, "test_dom_int", "integer");
    try (Statement stmt = conn.createStatement()) {
      try (ResultSet rs = stmt.executeQuery("SELECT ARRAY['a','b']::test_dom_vc[]")) {
        assertTrue(rs.next());
        Object array = rs.getArray(1).getArray();
        assertEquals(Object.class, array.getClass().getComponentType());
        assertArrayEquals(new Object[]{"a", "b"}, (Object[]) array);
      }
      try (ResultSet rs = stmt.executeQuery("SELECT ARRAY[1,2]::test_dom_int[]")) {
        assertTrue(rs.next());
        Object array = rs.getArray(1).getArray();
        assertEquals(Object.class, array.getClass().getComponentType());
        assertArrayEquals(new Object[]{1, 2}, (Object[]) array);
      }
    } finally {
      TestUtil.dropDomain(conn, "test_dom_vc");
      TestUtil.dropDomain(conn, "test_dom_int");
    }
  }

  @Test
  void pointArray_decodesToObjectArrayOfPGpoint() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT ARRAY['(1,2)','(3,4)']::point[]")) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertEquals(Object.class, array.getClass().getComponentType(),
          "legacy materialised point[] as Object[]");
      Object[] elems = (Object[]) array;
      assertEquals(2, elems.length);
      PGpoint first = assertInstanceOf(PGpoint.class, elems[0]);
      PGpoint second = assertInstanceOf(PGpoint.class, elems[1]);
      assertEquals(new PGpoint(1, 2), first);
      assertEquals(new PGpoint(3, 4), second);
    }
  }

  @Test
  void intervalArray_decodesToObjectArrayOfPGInterval() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT ARRAY['1 day','2 hours']::interval[]")) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertEquals(Object.class, array.getClass().getComponentType(),
          "legacy materialised interval[] as Object[]");
      Object[] elems = (Object[]) array;
      assertEquals(2, elems.length);
      PGInterval first = assertInstanceOf(PGInterval.class, elems[0]);
      assertEquals(1, first.getDays());
      PGInterval second = assertInstanceOf(PGInterval.class, elems[1]);
      assertEquals(2, second.getHours());
    }
  }

  @Test
  void jsonArray_keepsLegacyStringComponent() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT ARRAY['{\"a\":1}','2']::json[]")) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertEquals(String.class, array.getClass().getComponentType(),
          "json[] must stay String[] for backward compatibility");
      String[] elems = (String[]) array;
      assertEquals(2, elems.length);
      assertEquals("{\"a\":1}", elems[0]);
      assertEquals("2", elems[1]);
    }
  }

  @Test
  void jsonbArray_keepsLegacyStringComponent() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT ARRAY['2','3']::jsonb[]")) {
      assertTrue(rs.next());
      Object array = rs.getArray(1).getArray();
      assertEquals(String.class, array.getClass().getComponentType(),
          "jsonb[] must stay String[] for backward compatibility");
      String[] elems = (String[]) array;
      assertEquals(2, elems.length);
      assertEquals("2", elems[0]);
      assertEquals("3", elems[1]);
    }
  }

  @Test
  void moneyArray_decodesToDoubleArray() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      // The money text format (and thus PGmoney parsing) is locale-dependent; pin it so the test
      // is deterministic, matching the scalar PGmoney contract.
      stmt.execute("SET lc_monetary TO 'C'");
      try (ResultSet rs = stmt.executeQuery("SELECT ARRAY['1.50'::money, '2.50'::money]")) {
        assertTrue(rs.next());
        Object array = rs.getArray(1).getArray();
        assertEquals(Double.class, array.getClass().getComponentType(),
            "money[] keeps the legacy Double[] component type");
        Double[] elems = (Double[]) array;
        assertEquals(2, elems.length);
        assertEquals(1.5, elems[0], 0.0001);
        assertEquals(2.5, elems[1], 0.0001);
      }
    }
  }

  @Test
  void moneyArray_getResultSet_decodesEachElementViaCodec() throws SQLException {
    // getResultSet() tokenises the array literal and decodes the VALUE column through the element's
    // codec, never the legacy DOUBLE_OBJ_ARRAY decoder. getObject on a money element returns a Double
    // (money maps to Types.DOUBLE), matching both the scalar money contract and the legacy driver.
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("SET lc_monetary TO 'C'");
      try (ResultSet rs = stmt.executeQuery("SELECT ARRAY['1.50'::money, '2.50'::money]")) {
        assertTrue(rs.next());
        try (ResultSet ars = rs.getArray(1).getResultSet()) {
          assertTrue(ars.next());
          assertEquals(1, ars.getInt(1));
          // The VALUE column keeps the raw "$1.50" token, which the money codec parses back to its
          // numeric value; a decode/re-encode would have dropped the currency symbol and mis-parsed it.
          assertEquals(1.5, assertInstanceOf(Double.class, ars.getObject(2)), 0.0001);
          assertTrue(ars.next());
          assertEquals(2, ars.getInt(1));
          assertEquals(2.5, assertInstanceOf(Double.class, ars.getObject(2)), 0.0001);
        }
      }
    }
  }
}
