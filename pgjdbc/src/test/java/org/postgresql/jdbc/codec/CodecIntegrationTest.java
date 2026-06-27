/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.CodecContext;
import org.postgresql.jdbc.PGSQLType;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Struct;
import java.util.HashMap;
import java.util.Map;

/**
 * Integration tests for the codec system per PGTYPE_SPEC.md section 18.2.
 *
 * <p>Tests cover cross-type codec decoding, SQLData integration, array+typeMap,
 * domain types, range types, and setObject with SQLType/PGSQLType.</p>
 *
 * <p>Requires a running PostgreSQL server configured via TestUtil.</p>
 */
public class CodecIntegrationTest {

  private static Connection conn;

  @BeforeAll
  public static void setUp() throws Exception {
    conn = TestUtil.openDB();
    // jsonb was introduced in PostgreSQL 9.4; older servers can't run this
    // test class.
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_4),
        "jsonb requires PostgreSQL 9.4+");
    cleanup();
    try (Statement stmt = conn.createStatement()) {
      // Create test types
      stmt.execute("CREATE TYPE codec_test_address AS (street text, city text, zip int)");
      // Create nested composite: a person has an address
      stmt.execute("CREATE TYPE codec_test_person AS (name text, home_address codec_test_address, age int)");
      stmt.execute("CREATE DOMAIN positive_int AS integer CHECK (value > 0)");
      stmt.execute("CREATE TABLE codec_test_data ("
          + "id serial PRIMARY KEY, "
          + "int_val int, "
          + "num_val numeric(10,2), "
          + "addr codec_test_address, "
          + "addr_arr codec_test_address[], "
          + "pos_val positive_int, "
          + "json_val jsonb, "
          + "txt_val text, "
          + "person codec_test_person, "
          + "addr_with_nulls codec_test_address"
          + ")");
      stmt.execute("INSERT INTO codec_test_data (int_val, num_val, addr, addr_arr, pos_val, json_val, txt_val, person, addr_with_nulls) "
          + "VALUES (42, 123.45, ROW('Main St', 'Springfield', 62701)::codec_test_address, "
          + "ARRAY[ROW('Oak Ave', 'Shelbyville', 62702), ROW('Elm St', 'Capitol', 62703)]::codec_test_address[], "
          + "7, '{\"key\": \"value\"}'::jsonb, 'hello', "
          + "ROW('John Doe', ROW('Elm St', 'Boston', 02101)::codec_test_address, 30)::codec_test_person, "
          + "ROW(NULL, 'CityOnly', NULL)::codec_test_address)");
    }
  }

  @AfterAll
  public static void tearDown() throws Exception {
    if (conn != null) {
      cleanup();
      conn.close();
    }
  }

  private static void cleanup() throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS codec_test_data CASCADE");
      stmt.execute("DROP TYPE IF EXISTS codec_test_person CASCADE");
      stmt.execute("DROP TYPE IF EXISTS codec_test_address CASCADE");
      stmt.execute("DROP DOMAIN IF EXISTS positive_int CASCADE");
    }
  }

  // ==================== Cross-Type Codec Tests ====================

  @Test
  void getInt_fromInt4Column() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT int_val FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      assertEquals(42, rs.getInt(1));
    }
  }

  @Test
  void getInt_fromNumericColumn() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT num_val FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      // NumericCodec.decodeAsInt() should truncate the decimal part
      assertEquals(123, rs.getInt(1));
    }
  }

  @Test
  void getBigDecimal_fromInt4Column() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT int_val FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      // Int4Codec.decodeAsBigDecimal() should return exact value
      assertEquals(new BigDecimal("42"), rs.getBigDecimal(1));
    }
  }

  // ==================== SQLData / Composite Tests ====================

  @Test
  void getObject_asSQLData() throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("codec_test_address", TestAddress.class);
    conn.setTypeMap(typeMap);
    try {
      try (Statement stmt = conn.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT addr FROM codec_test_data WHERE id = 1")) {
        assertTrue(rs.next());
        Object obj = rs.getObject(1);
        assertInstanceOf(TestAddress.class, obj);
        TestAddress addr = (TestAddress) obj;
        assertEquals("Main St", addr.street);
        assertEquals("Springfield", addr.city);
        assertEquals(62701, addr.zip);
      }
    } finally {
      conn.setTypeMap(new HashMap<>());
    }
  }

  @Test
  void getObject_withTargetClass_asSQLData() throws SQLException {
    // Tests rs.getObject(i, MyClass.class) - the two-argument form
    // This should work without setting the connection typeMap
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT addr FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      TestAddress addr = rs.getObject(1, TestAddress.class);
      assertNotNull(addr);
      assertEquals("Main St", addr.street);
      assertEquals("Springfield", addr.city);
      assertEquals(62701, addr.zip);
    }
  }

  @Test
  void getObject_asStruct() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT addr FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      Struct struct = rs.getObject(1, Struct.class);
      assertNotNull(struct);
      Object[] attrs = struct.getAttributes();
      assertEquals(3, attrs.length);
      assertEquals("Main St", attrs[0]);
      assertEquals("Springfield", attrs[1]);
      assertEquals(62701, attrs[2]);
    }
  }

  // ==================== Nested Composite Tests ====================

  @Test
  void getObject_nestedComposite_asStruct() throws SQLException {
    // Tests nested composite types: person contains address
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT person FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      Struct personStruct = rs.getObject(1, Struct.class);
      assertNotNull(personStruct);
      Object[] personAttrs = personStruct.getAttributes();
      assertEquals(3, personAttrs.length);
      assertEquals("John Doe", personAttrs[0]);
      assertEquals(30, personAttrs[2]);

      // The nested address should also be a Struct
      assertInstanceOf(Struct.class, personAttrs[1]);
      Struct addressStruct = (Struct) personAttrs[1];
      Object[] addrAttrs = addressStruct.getAttributes();
      assertEquals(3, addrAttrs.length);
      assertEquals("Elm St", addrAttrs[0]);
      assertEquals("Boston", addrAttrs[1]);
      assertEquals(2101, addrAttrs[2]);
    }
  }

  @Test
  void getObject_nestedComposite_asSQLData() throws SQLException {
    // Tests nested composite with SQLData mapping
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("codec_test_address", TestAddress.class);
    typeMap.put("codec_test_person", TestPerson.class);
    conn.setTypeMap(typeMap);
    try {
      try (Statement stmt = conn.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT person FROM codec_test_data WHERE id = 1")) {
        assertTrue(rs.next());
        Object obj = rs.getObject(1);
        assertInstanceOf(TestPerson.class, obj);
        TestPerson person = (TestPerson) obj;
        assertEquals("John Doe", person.name);
        assertEquals(30, person.age);
        assertNotNull(person.homeAddress);
        assertEquals("Elm St", person.homeAddress.street);
        assertEquals("Boston", person.homeAddress.city);
      }
    } finally {
      conn.setTypeMap(new HashMap<>());
    }
  }

  // ==================== NULL Values in Composite Tests ====================

  @Test
  void getObject_compositeWithNullFields_asStruct() throws SQLException {
    // Tests NULL values within composite fields
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT addr_with_nulls FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      Struct struct = rs.getObject(1, Struct.class);
      assertNotNull(struct);
      Object[] attrs = struct.getAttributes();
      assertEquals(3, attrs.length);
      assertNull(attrs[0]); // street is NULL
      assertEquals("CityOnly", attrs[1]);
      assertNull(attrs[2]); // zip is NULL
    }
  }

  @Test
  void getObject_compositeWithNullFields_asSQLData() throws SQLException {
    // Tests NULL values within composite via SQLData
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put("codec_test_address", TestAddress.class);
    conn.setTypeMap(typeMap);
    try {
      try (Statement stmt = conn.createStatement();
           ResultSet rs = stmt.executeQuery("SELECT addr_with_nulls FROM codec_test_data WHERE id = 1")) {
        assertTrue(rs.next());
        Object obj = rs.getObject(1);
        assertInstanceOf(TestAddress.class, obj);
        TestAddress addr = (TestAddress) obj;
        assertNull(addr.street);
        assertEquals("CityOnly", addr.city);
        assertEquals(0, addr.zip); // int defaults to 0 when null
      }
    } finally {
      conn.setTypeMap(new HashMap<>());
    }
  }

  @Test
  void getObject_nullComposite() throws SQLException {
    // Tests completely NULL composite value
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT NULL::codec_test_address")) {
      assertTrue(rs.next());
      assertNull(rs.getObject(1));
      assertNull(rs.getObject(1, Struct.class));
    }
  }

  // ==================== Array + TypeMap Tests ====================

  @Test
  void getArray_withTypeMap_convertsToSQLData() throws SQLException {
    Map<String, Class<?>> map = new HashMap<>();
    map.put("codec_test_address", TestAddress.class);

    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT addr_arr FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      Array array = rs.getArray(1);
      assertNotNull(array);
      Object[] elements = (Object[]) array.getArray(map);
      assertEquals(2, elements.length);

      assertInstanceOf(TestAddress.class, elements[0]);
      TestAddress addr1 = (TestAddress) elements[0];
      assertEquals("Oak Ave", addr1.street);
      assertEquals("Shelbyville", addr1.city);

      assertInstanceOf(TestAddress.class, elements[1]);
      TestAddress addr2 = (TestAddress) elements[1];
      assertEquals("Elm St", addr2.street);
      assertEquals("Capitol", addr2.city);
    }
  }

  @Test
  void getArray_withoutTypeMap_returnsStructs() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT addr_arr FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      Array array = rs.getArray(1);
      assertNotNull(array);
      Object[] elements = (Object[]) array.getArray();
      assertEquals(2, elements.length);
      // Without type map, elements should be PGobject or Struct
      assertNotNull(elements[0]);
      assertNotNull(elements[1]);
    }
  }

  // ==================== Domain Type Tests ====================

  @Test
  void getObject_domainType_usesBaseTypeCodec() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT pos_val FROM codec_test_data WHERE id = 1")) {
      assertTrue(rs.next());
      // Domain type should decode through the base type (int4) codec
      assertEquals(7, rs.getInt(1));
      assertEquals(7, rs.getObject(1));
    }
  }

  @Test
  void setObject_domainType_respectsConstraint() throws SQLException {
    // Server-side constraint validation: positive_int requires value > 0
    try (PreparedStatement ps = conn.prepareStatement(
        "INSERT INTO codec_test_data (pos_val) VALUES (?)")) {
      ps.setInt(1, 5);
      assertEquals(1, ps.executeUpdate());
    }
  }

  @Test
  void getObject_compositeWithDomainOverArrayField_decodesViaBaseArrayCodec() throws SQLException {
    // A composite field typed as a domain-over-array (CREATE DOMAIN d AS int[]) carries the
    // domain's OID in the record wire format. The domain keeps typtype='d' but inherits
    // typcategory='A' from its base array, and its own typelem is 0, so
    // CodecRegistry.resolveByTyptype must select DomainCodec (by typtype) before ArrayCodec
    // (by typcategory). Otherwise the field routes through ArrayCodec with typelem 0 and the
    // array silently decodes as null.
    //
    // A top-level domain column does not exercise this: the server reports the *base* type OID
    // for a domain result column, so only an embedded field preserves the domain OID.
    TestUtil.createDomain(conn, "codec_dom_intarr", "int[]");
    try {
      TestUtil.createCompositeType(conn, "codec_dom_holder", "arr codec_dom_intarr, tag text");
      try (Statement stmt = conn.createStatement();
           ResultSet rs = stmt.executeQuery(
               "SELECT ROW('{1,2,3}'::codec_dom_intarr, 'x')::codec_dom_holder")) {
        assertTrue(rs.next());
        Struct struct = assertInstanceOf(Struct.class, rs.getObject(1));
        Object[] attrs = struct.getAttributes();
        // DomainCodec forwards to the base int[] codec, so the field yields a readable Array.
        Array arr = assertInstanceOf(Array.class, attrs[0]);
        assertArrayEquals(new Integer[]{1, 2, 3}, (Integer[]) arr.getArray());
        assertEquals("x", attrs[1]);
      }
    } finally {
      TestUtil.dropType(conn, "codec_dom_holder");
      TestUtil.dropDomain(conn, "codec_dom_intarr");
    }
  }

  @Test
  void decodeText_compositeFieldCountMismatch_throwsClearError() throws SQLException {
    // The server never emits a record literal whose field count disagrees with the catalog, so a
    // skew can only arise from stale cached metadata (e.g. ALTER TYPE ADD/DROP ATTRIBUTE between
    // describe and decode). Drive the text decoder directly with hand-crafted literals to prove it
    // reports the mismatch instead of silently dropping or NULL-filling fields.
    BaseConnection bc = conn.unwrap(BaseConnection.class);
    CodecContext ctx = bc.getCodecContext();
    // codec_test_address is (street text, city text, zip int) -> three attributes.
    PgType addr = bc.getTypeInfo().getPgTypeByPgName("codec_test_address");

    // A correctly-shaped literal still decodes.
    Struct ok = (Struct) CompositeCodec.INSTANCE.decodeText("(Main St,Springfield,62701)", addr, ctx);
    assertEquals(3, ok.getAttributes().length);

    // Surplus field -> clear DATA_ERROR, not a silent drop.
    PSQLException tooMany = assertThrows(PSQLException.class,
        () -> CompositeCodec.INSTANCE.decodeText("(a,b,1,surplus)", addr, ctx));
    assertEquals(PSQLState.DATA_ERROR.getState(), tooMany.getSQLState());

    // Missing field -> clear DATA_ERROR, not a NULL-filled trailing attribute.
    PSQLException tooFew = assertThrows(PSQLException.class,
        () -> CompositeCodec.INSTANCE.decodeText("(a,b)", addr, ctx));
    assertEquals(PSQLState.DATA_ERROR.getState(), tooFew.getSQLState());
  }

  // ==================== setObject with SQLType Tests ====================

  @Test
  void setObject_withJDBCType_VARCHAR() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ?::text")) {
      ps.setObject(1, "test_value", JDBCType.VARCHAR);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("test_value", rs.getString(1));
      }
    }
  }

  @Test
  void setObject_withJDBCType_INTEGER() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ?::int")) {
      ps.setObject(1, 42, JDBCType.INTEGER);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(42, rs.getInt(1));
      }
    }
  }

  @Test
  void setObject_withPGSQLType_JSONB() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ?::jsonb ->> 'key'")) {
      ps.setObject(1, "{\"key\": \"found\"}", PGSQLType.JSONB);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("found", rs.getString(1));
      }
    }
  }

  @Test
  void setObject_withPGSQLType_TEXT() throws SQLException {
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ?")) {
      ps.setObject(1, "pg_text", PGSQLType.TEXT);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("pg_text", rs.getString(1));
      }
    }
  }

  @Test
  void setObject_withPGSQLType_UUID() throws SQLException {
    String uuid = "550e8400-e29b-41d4-a716-446655440000";
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ?::uuid")) {
      ps.setObject(1, uuid, PGSQLType.UUID);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(uuid, rs.getString(1));
      }
    }
  }

  // ==================== Range Type Tests ====================

  @Test
  void getObject_rangeType_returnsRange() throws SQLException {
    try (Statement stmt = conn.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT int4range(1, 10)")) {
      assertTrue(rs.next());
      Object range = rs.getObject(1);
      assertNotNull(range);
      // Range is returned as PGobject with string value
      String rangeStr = rs.getString(1);
      assertEquals("[1,10)", rangeStr);
    }
  }

  @Test
  void setObject_rangeType_roundtrip() throws SQLException {
    PGobject range = new PGobject();
    range.setType("int4range");
    range.setValue("[1,10)");
    try (PreparedStatement ps = conn.prepareStatement(
        "SELECT ?::int4range")) {
      ps.setObject(1, range);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals("[1,10)", rs.getString(1));
      }
    }
  }

  // ==================== Test SQLData Implementation ====================

  public static class TestAddress implements SQLData {
    public String street;
    public String city;
    public int zip;
    private String typeName;

    public TestAddress() {
    }

    @Override
    public String getSQLTypeName() {
      return typeName != null ? typeName : "codec_test_address";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.street = stream.readString();
      this.city = stream.readString();
      this.zip = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(street);
      stream.writeString(city);
      stream.writeInt(zip);
    }
  }

  public static class TestPerson implements SQLData {
    public String name;
    public TestAddress homeAddress;
    public int age;
    private String typeName;

    public TestPerson() {
    }

    @Override
    public String getSQLTypeName() {
      return typeName != null ? typeName : "codec_test_person";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.name = stream.readString();
      // Read the nested composite - should be mapped via connection typeMap
      Object addrObj = stream.readObject();
      if (addrObj instanceof TestAddress) {
        this.homeAddress = (TestAddress) addrObj;
      }
      this.age = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(name);
      stream.writeObject(homeAddress);
      stream.writeInt(age);
    }
  }
}
