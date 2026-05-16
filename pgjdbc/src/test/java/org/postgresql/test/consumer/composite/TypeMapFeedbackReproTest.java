/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reproduces three pieces of reviewer feedback for the typecache branch.
 * Each test pins the <b>expected</b> (correct) behavior, so it fails today
 * and turns green once the underlying gap is closed:
 *
 * <ol>
 *   <li><b>Array support</b> — {@code Array.getArray(map)} must work for
 *       binary arrays and {@code Array.getResultSet(map)} must work in all
 *       transfer modes.</li>
 *   <li><b>CallableStatement support</b> — named-parameter
 *       {@code registerOutParameter(String, …)}, {@code getObject(String)},
 *       {@code getObject(String, Map)} and {@code getObject(String, Class<T>)}
 *       must work for an OUT parameter declared in the function signature.</li>
 *   <li><b>Qualified / quoted identifiers in {@code Map<String, Class<?>>}</b> —
 *       map keys should be resolved against the type's identifier in all
 *       common forms (bare name, schema-qualified, quoted-qualified,
 *       mixed-case with or without quotes), not only against the literal
 *       output of {@code pg_catalog.format_type()}.</li>
 * </ol>
 */
public class TypeMapFeedbackReproTest {

  private static final String SCHEMA = "repro_typemap_schema";
  private static final String ADDRESS_TYPE = SCHEMA + ".address_t";
  private static final String MIXED_TYPE = SCHEMA + ".\"MixedCaseAddress\"";

  @BeforeAll
  static void setUpSchema() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      cleanup(con);
      TestUtil.createSchema(con, SCHEMA);
      TestUtil.execute(con, "CREATE TYPE " + ADDRESS_TYPE
          + " AS (street text, postal_code text)");
      TestUtil.execute(con, "CREATE TYPE " + MIXED_TYPE
          + " AS (street text, postal_code text)");
      TestUtil.createTable(con, "repro_typemap_data",
          "id int primary key, addr " + ADDRESS_TYPE + ", mixed " + MIXED_TYPE);
      TestUtil.execute(con, "INSERT INTO repro_typemap_data VALUES (1, "
          + "ROW('Main 1','11111')::" + ADDRESS_TYPE + ", "
          + "ROW('Side 9','22222')::" + MIXED_TYPE + ")");
      // Function with a named OUT parameter for the CallableStatement repros.
      TestUtil.execute(con, "CREATE OR REPLACE FUNCTION repro_typemap_f("
          + "OUT total int) AS $$ BEGIN total := 42; END; $$ LANGUAGE plpgsql");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      cleanup(con);
    }
  }

  private static void cleanup(Connection con) throws SQLException {
    TestUtil.dropFunction(con, "repro_typemap_f", "");
    TestUtil.dropTable(con, "repro_typemap_data");
    TestUtil.dropSchema(con, SCHEMA);
  }

  // ---------------------------------------------------------------------------
  // (3) Map<String, Class<?>> key normalisation — expected behavior
  // ---------------------------------------------------------------------------

  /**
   * The bare type name {@code "address_t"} should resolve to the user-defined
   * composite even when the type lives in a schema outside {@code search_path}.
   * Today the lookup is a literal {@code map.get(format_type(oid, null))} which
   * fails for the schema-qualified variant returned by PostgreSQL.
   */
  @Test
  void mapKeyBareNameShouldResolveQualifiedType() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      Map<String, Class<?>> bareKey = new HashMap<>();
      bareKey.put("address_t", Address.class);

      try (Statement st = con.createStatement();
           ResultSet rs = st.executeQuery("SELECT addr FROM repro_typemap_data WHERE id=1")) {
        assertTrue(rs.next());
        Object value = rs.getObject(1, bareKey);
        assertInstanceOf(Address.class, value,
            "bare-name 'address_t' must resolve to Address, got: " + value);
        Address a = (Address) value;
        assertEquals("Main 1", a.street);
        assertEquals("11111", a.postalCode);
      }
    }
  }

  /**
   * Quoted-qualified key ({@code "\"repro_typemap_schema\".\"address_t\""})
   * is the form people paste from psql's {@code \dT}. It must be normalised
   * and resolve to the same type as the bare or qualified variant.
   */
  @Test
  void mapKeyQuotedQualifiedShouldResolvePlainType() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      Map<String, Class<?>> quotedKey = new HashMap<>();
      quotedKey.put("\"repro_typemap_schema\".\"address_t\"", Address.class);

      try (Statement st = con.createStatement();
           ResultSet rs = st.executeQuery("SELECT addr FROM repro_typemap_data WHERE id=1")) {
        assertTrue(rs.next());
        Object value = rs.getObject(1, quotedKey);
        assertInstanceOf(Address.class, value,
            "quoted-qualified key must resolve to Address, got: " + value);
      }
    }
  }

  /**
   * For a mixed-case type created with a quoted identifier
   * ({@code "MixedCaseAddress"}) every natural Java-side form should resolve:
   * the bare identifier, the unquoted schema-qualified form, and the fully
   * quoted-qualified form. Today only the exact {@code format_type()} output
   * works ({@code repro_typemap_schema."MixedCaseAddress"}).
   */
  @Test
  void mapKeyMixedCaseShouldResolveAcrossNaturalForms() throws SQLException {
    String[] candidateKeys = {
        "MixedCaseAddress",
        "repro_typemap_schema.MixedCaseAddress",
        "\"repro_typemap_schema\".\"MixedCaseAddress\"",
        "repro_typemap_schema.\"MixedCaseAddress\"",
    };
    try (Connection con = TestUtil.openDB()) {
      for (String key : candidateKeys) {
        Map<String, Class<?>> map = new HashMap<>();
        map.put(key, Address.class);
        try (Statement st = con.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT mixed FROM repro_typemap_data WHERE id=1")) {
          assertTrue(rs.next());
          Object value = rs.getObject(1, map);
          assertInstanceOf(Address.class, value,
              "key " + key + " must resolve to Address, got: " + value);
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // (1) Array support — expected behavior
  // ---------------------------------------------------------------------------

  /**
   * {@code Array.getArray(map)} must materialise the array in both text and
   * binary transfer modes. For a built-in element type the map is effectively
   * a no-op, but binary path must not refuse a non-empty map.
   */
  @Test
  void arrayGetArrayWithMapShouldWorkForBinaryArrays() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.INT4_ARRAY + "," + Oid.INT4);
    try (Connection con = TestUtil.openDB(props);
         PreparedStatement ps = con.prepareStatement("SELECT '{1,2,3}'::int4[]")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        Array array = rs.getArray(1);
        assertNotNull(array);

        Map<String, Class<?>> map = new HashMap<>();
        map.put("anything", Address.class);

        Object javaArray = array.getArray(map);
        assertInstanceOf(Integer[].class, javaArray);
        Integer[] values = (Integer[]) javaArray;
        assertEquals(3, values.length);
        assertEquals(1, values[0]);
        assertEquals(2, values[1]);
        assertEquals(3, values[2]);
      }
    }
  }

  /**
   * {@code Array.getResultSet(map)} must return a two-column ResultSet
   * (index, value) just like {@link Array#getResultSet()}. A non-empty map
   * for a built-in element type is still valid input.
   */
  @Test
  void arrayGetResultSetWithMapShouldWork() throws SQLException {
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement("SELECT '{1,2,3}'::int4[]")) {
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        Array array = rs.getArray(1);
        assertNotNull(array);

        Map<String, Class<?>> map = new HashMap<>();
        map.put("anything", Address.class);

        try (ResultSet arrayRs = array.getResultSet(map)) {
          assertNotNull(arrayRs);
          assertTrue(arrayRs.next());
          assertEquals(1, arrayRs.getInt(2));
          assertTrue(arrayRs.next());
          assertEquals(2, arrayRs.getInt(2));
          assertTrue(arrayRs.next());
          assertEquals(3, arrayRs.getInt(2));
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // (2) CallableStatement support — expected behavior
  // ---------------------------------------------------------------------------

  /**
   * Named-parameter registration must work for a function whose OUT
   * parameter is declared with a name ({@code OUT total int}). Today
   * {@link CallableStatement#registerOutParameter(String, int)} throws
   * not-implemented, which blocks every callable-by-name flow.
   */
  @Test
  void callableStatementRegisterOutParameterByNameShouldWork() throws SQLException {
    try (Connection con = TestUtil.openDB();
         CallableStatement cs = con.prepareCall("{ ? = call repro_typemap_f() }")) {
      cs.registerOutParameter("total", java.sql.Types.INTEGER);
      cs.execute();
      assertEquals(42, cs.getInt(1));
    }
  }

  @Test
  void callableStatementGetObjectByNameShouldReturnValue() throws SQLException {
    try (Connection con = TestUtil.openDB();
         CallableStatement cs = con.prepareCall("{ ? = call repro_typemap_f() }")) {
      cs.registerOutParameter(1, java.sql.Types.INTEGER);
      cs.execute();
      Object value = cs.getObject("total");
      assertInstanceOf(Integer.class, value);
      assertEquals(42, value);
    }
  }

  @Test
  void callableStatementGetObjectByNameMapShouldReturnValue() throws SQLException {
    try (Connection con = TestUtil.openDB();
         CallableStatement cs = con.prepareCall("{ ? = call repro_typemap_f() }")) {
      cs.registerOutParameter(1, java.sql.Types.INTEGER);
      cs.execute();
      Map<String, Class<?>> map = new HashMap<>();
      map.put("int4", Integer.class);
      Object value = cs.getObject("total", map);
      assertEquals(42, ((Number) value).intValue());
    }
  }

  @Test
  void callableStatementGetObjectByNameClassShouldReturnValue() throws SQLException {
    try (Connection con = TestUtil.openDB();
         CallableStatement cs = con.prepareCall("{ ? = call repro_typemap_f() }")) {
      cs.registerOutParameter(1, java.sql.Types.INTEGER);
      cs.execute();
      Integer value = cs.getObject("total", Integer.class);
      assertEquals(42, value);
    }
  }

  // ---------------------------------------------------------------------------
  // Control: the {@code format_type()} output is already accepted today.
  // This stays green as a baseline that the lookup itself works once the
  // key matches exactly.
  // ---------------------------------------------------------------------------

  @Test
  void mapKeyExactFormatTypeOutputResolves() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      Map<String, Class<?>> exactKey = new HashMap<>();
      exactKey.put("repro_typemap_schema.address_t", Address.class);
      try (Statement st = con.createStatement();
           ResultSet rs = st.executeQuery("SELECT addr FROM repro_typemap_data WHERE id=1")) {
        assertTrue(rs.next());
        Object value = rs.getObject(1, exactKey);
        assertInstanceOf(Address.class, value,
            "exact format_type key must resolve, got: " + value);
        Address a = (Address) value;
        assertEquals("Main 1", a.street);
        assertEquals("11111", a.postalCode);
      }
    }
  }

  /** Minimal SQLData target used by the type-map tests. */
  public static final class Address implements SQLData {
    String typeName = "";
    String street = "";
    String postalCode = "";

    @Override
    public String getSQLTypeName() {
      return typeName;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.street = stream.readString();
      this.postalCode = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(street);
      stream.writeString(postalCode);
    }
  }
}
