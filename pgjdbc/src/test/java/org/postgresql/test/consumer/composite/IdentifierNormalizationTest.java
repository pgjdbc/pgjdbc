/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Struct;
import java.sql.Types;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Exercises identifier normalization in user-supplied
 * {@code Map<String, Class<?>>} keys across every JDBC surface that performs
 * a type-map lookup:
 *
 * <ul>
 *   <li>{@link ResultSet#getObject(int, Map)}</li>
 *   <li>{@link Struct#getAttributes(Map)} on a nested composite attribute</li>
 *   <li>{@link SQLInput#readObject()} during composite dispatch inside an
 *       outer {@link SQLData}</li>
 *   <li>{@link Array#getArray(Map)} with composite element type</li>
 *   <li>{@link CallableStatement#getObject(int, Map)} for a composite OUT</li>
 * </ul>
 *
 * <p>For each surface every spec-legal identifier form must resolve to the
 * user's {@link SQLData} class, not just the exact output of
 * {@code pg_catalog.format_type(oid, null)}. Identifier-form sources are split
 * by the type's case-sensitivity:</p>
 *
 * <ul>
 *   <li>{@link #bareTypeKeys()} — forms aimed at a lowercase, non-search-path
 *       type ({@code idnorm_schema.bare_t}).</li>
 *   <li>{@link #mixedCaseTypeKeys()} — forms aimed at a case-preserving type
 *       ({@code idnorm_schema."MixedCase"}).</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
public class IdentifierNormalizationTest {

  private static final String SCHEMA = "idnorm_schema";
  private static final String BARE_TYPE = SCHEMA + ".bare_t";
  private static final String MIXED_TYPE = SCHEMA + ".\"MixedCase\"";
  private static final String OUTER_BARE_TYPE = SCHEMA + ".outer_bare_t";
  private static final String OUTER_MIXED_TYPE = SCHEMA + ".outer_mixed_t";

  @BeforeAll
  static void setUpSchema() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      cleanup(con);
      TestUtil.createSchema(con, SCHEMA);
      TestUtil.createCompositeType(con, BARE_TYPE, "label text, score int");
      TestUtil.createCompositeType(con, MIXED_TYPE, "label text, score int");
      TestUtil.createCompositeType(con, OUTER_BARE_TYPE,
          "id int, inner_attr " + BARE_TYPE);
      TestUtil.createCompositeType(con, OUTER_MIXED_TYPE,
          "id int, inner_attr " + MIXED_TYPE);
      TestUtil.createTable(con, "idnorm_data",
          "id int primary key, "
              + "bare_col " + BARE_TYPE + ", "
              + "mixed_col " + MIXED_TYPE + ", "
              + "outer_bare_col " + OUTER_BARE_TYPE + ", "
              + "outer_mixed_col " + OUTER_MIXED_TYPE + ", "
              + "bare_arr " + BARE_TYPE + "[], "
              + "mixed_arr " + MIXED_TYPE + "[]");
      TestUtil.execute(con, "CREATE OR REPLACE FUNCTION idnorm_f_bare("
          + "OUT result " + BARE_TYPE + ") AS $$ "
          + "BEGIN result := ROW('out-bare', 42)::" + BARE_TYPE + "; END; "
          + "$$ LANGUAGE plpgsql");
      TestUtil.execute(con, "CREATE OR REPLACE FUNCTION idnorm_f_mixed("
          + "OUT result " + MIXED_TYPE + ") AS $$ "
          + "BEGIN result := ROW('out-mixed', 43)::" + MIXED_TYPE + "; END; "
          + "$$ LANGUAGE plpgsql");
      TestUtil.execute(con, "INSERT INTO idnorm_data VALUES ("
          + "1, "
          + "ROW('bare-hello', 1)::" + BARE_TYPE + ", "
          + "ROW('mixed-hello', 2)::" + MIXED_TYPE + ", "
          + "ROW(10, ROW('inner-bare', 11)::" + BARE_TYPE + ")::" + OUTER_BARE_TYPE + ", "
          + "ROW(20, ROW('inner-mixed', 21)::" + MIXED_TYPE + ")::" + OUTER_MIXED_TYPE + ", "
          + "ARRAY[ROW('bare-arr-1', 100)::" + BARE_TYPE + ", "
          + "ROW('bare-arr-2', 200)::" + BARE_TYPE + "], "
          + "ARRAY[ROW('mixed-arr-1', 300)::" + MIXED_TYPE + ", "
          + "ROW('mixed-arr-2', 400)::" + MIXED_TYPE + "])");
    }
  }

  @AfterAll
  static void tearDownSchema() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      cleanup(con);
    }
  }

  private static void cleanup(Connection con) throws SQLException {
    TestUtil.dropFunction(con, "idnorm_f_bare", "");
    TestUtil.dropFunction(con, "idnorm_f_mixed", "");
    TestUtil.dropTable(con, "idnorm_data");
    TestUtil.dropSchema(con, SCHEMA);
  }

  // ---------------------------------------------------------------------------
  // Identifier-form sources
  // ---------------------------------------------------------------------------

  /** Forms that should resolve to {@code idnorm_schema.bare_t}. */
  static Stream<Arguments> bareTypeKeys() {
    return Stream.of(
        Arguments.of("bare", "bare_t"),
        Arguments.of("schema-qualified (baseline)", SCHEMA + ".bare_t"),
        Arguments.of("fully quoted-qualified",
            "\"" + SCHEMA + "\".\"bare_t\""),
        Arguments.of("partial-quoted (type only)",
            SCHEMA + ".\"bare_t\""),
        Arguments.of("partial-quoted (schema only)",
            "\"" + SCHEMA + "\".bare_t")
    );
  }

  /** Forms that should resolve to {@code idnorm_schema."MixedCase"}. */
  static Stream<Arguments> mixedCaseTypeKeys() {
    return Stream.of(
        Arguments.of("unquoted bare", "MixedCase"),
        Arguments.of("unquoted schema-qualified",
            SCHEMA + ".MixedCase"),
        Arguments.of("fully quoted-qualified (baseline)",
            "\"" + SCHEMA + "\".\"MixedCase\""),
        Arguments.of("partial-quoted (type only)",
            SCHEMA + ".\"MixedCase\"")
    );
  }

  // ---------------------------------------------------------------------------
  // Surface A — ResultSet.getObject(int, Map)
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("bareTypeKeys")
  void resultSetGetObjectMap_bareType(String label, String mapKey) throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, BareT.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT bare_col FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Object value = rs.getObject(1, typeMap);
      assertInstanceOf(BareT.class, value,
          label + ": expected BareT, got " + value);
      BareT b = (BareT) value;
      assertEquals("bare-hello", b.label);
      assertEquals(1, b.score);
    }
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("mixedCaseTypeKeys")
  void resultSetGetObjectMap_mixedCaseType(String label, String mapKey) throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, MixedCase.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT mixed_col FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Object value = rs.getObject(1, typeMap);
      assertInstanceOf(MixedCase.class, value,
          label + ": expected MixedCase, got " + value);
      MixedCase m = (MixedCase) value;
      assertEquals("mixed-hello", m.label);
      assertEquals(2, m.score);
    }
  }

  // ---------------------------------------------------------------------------
  // Surface B — Struct.getAttributes(Map) on a nested composite attribute
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("bareTypeKeys")
  void structGetAttributesMap_bareType(String label, String mapKey) throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, BareT.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT outer_bare_col FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Struct outer = rs.getObject(1, Struct.class);
      assertNotNull(outer);
      Object[] attrs = outer.getAttributes(typeMap);
      assertEquals(2, attrs.length);
      assertEquals(10, attrs[0]);
      assertInstanceOf(BareT.class, attrs[1],
          label + ": nested attribute must resolve to BareT, got " + attrs[1]);
      BareT inner = (BareT) attrs[1];
      assertEquals("inner-bare", inner.label);
      assertEquals(11, inner.score);
    }
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("mixedCaseTypeKeys")
  void structGetAttributesMap_mixedCaseType(String label, String mapKey) throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, MixedCase.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT outer_mixed_col FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Struct outer = rs.getObject(1, Struct.class);
      assertNotNull(outer);
      Object[] attrs = outer.getAttributes(typeMap);
      assertEquals(2, attrs.length);
      assertEquals(20, attrs[0]);
      assertInstanceOf(MixedCase.class, attrs[1],
          label + ": nested attribute must resolve to MixedCase, got " + attrs[1]);
      MixedCase inner = (MixedCase) attrs[1];
      assertEquals("inner-mixed", inner.label);
      assertEquals(21, inner.score);
    }
  }

  // ---------------------------------------------------------------------------
  // Surface C — SQLInput.readObject() during composite dispatch
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("bareTypeKeys")
  void sqlInputReadObject_bareNestedComposite(String label, String mapKey) throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(OUTER_BARE_TYPE, OuterBareT.class);
    typeMap.put(mapKey, BareT.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT outer_bare_col FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      OuterBareT outer = (OuterBareT) rs.getObject(1, typeMap);
      assertNotNull(outer);
      assertEquals(10, outer.id);
      assertNotNull(outer.innerAttr,
          label + ": nested SQLData must materialize via map key");
      assertEquals("inner-bare", outer.innerAttr.label);
      assertEquals(11, outer.innerAttr.score);
    }
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("mixedCaseTypeKeys")
  void sqlInputReadObject_mixedCaseNestedComposite(String label, String mapKey)
      throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(OUTER_MIXED_TYPE, OuterMixedT.class);
    typeMap.put(mapKey, MixedCase.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT outer_mixed_col FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      OuterMixedT outer = (OuterMixedT) rs.getObject(1, typeMap);
      assertNotNull(outer);
      assertEquals(20, outer.id);
      assertNotNull(outer.innerAttr,
          label + ": nested SQLData must materialize via map key");
      assertEquals("inner-mixed", outer.innerAttr.label);
      assertEquals(21, outer.innerAttr.score);
    }
  }

  // ---------------------------------------------------------------------------
  // Surface D — Array.getArray(Map) with composite element
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("bareTypeKeys")
  void arrayGetArrayMap_bareCompositeElement(String label, String mapKey) throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, BareT.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT bare_arr FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Array array = rs.getArray(1);
      Object[] elements = (Object[]) array.getArray(typeMap);
      assertEquals(2, elements.length);
      assertInstanceOf(BareT.class, elements[0],
          label + ": array element must materialize to BareT");
      BareT first = (BareT) elements[0];
      assertEquals("bare-arr-1", first.label);
      assertEquals(100, first.score);
    }
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("mixedCaseTypeKeys")
  void arrayGetArrayMap_mixedCaseCompositeElement(String label, String mapKey)
      throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, MixedCase.class);
    try (Connection con = TestUtil.openDB();
         PreparedStatement ps = con.prepareStatement(
             "SELECT mixed_arr FROM idnorm_data WHERE id = 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      Array array = rs.getArray(1);
      Object[] elements = (Object[]) array.getArray(typeMap);
      assertEquals(2, elements.length);
      assertInstanceOf(MixedCase.class, elements[0],
          label + ": array element must materialize to MixedCase");
      MixedCase first = (MixedCase) elements[0];
      assertEquals("mixed-arr-1", first.label);
      assertEquals(300, first.score);
    }
  }

  // ---------------------------------------------------------------------------
  // Surface E — CallableStatement.getObject(int, Map)
  // ---------------------------------------------------------------------------

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("bareTypeKeys")
  void callableStatementGetObjectMap_bareCompositeOut(String label, String mapKey)
      throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, BareT.class);
    try (Connection con = TestUtil.openDB();
         CallableStatement cs = con.prepareCall("{ ? = call idnorm_f_bare() }")) {
      cs.registerOutParameter(1, Types.STRUCT, BARE_TYPE);
      cs.execute();
      Object value = cs.getObject(1, typeMap);
      assertInstanceOf(BareT.class, value,
          label + ": OUT must resolve to BareT, got " + value);
      BareT b = (BareT) value;
      assertEquals("out-bare", b.label);
      assertEquals(42, b.score);
    }
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("mixedCaseTypeKeys")
  void callableStatementGetObjectMap_mixedCaseCompositeOut(String label, String mapKey)
      throws SQLException {
    Map<String, Class<?>> typeMap = new HashMap<>();
    typeMap.put(mapKey, MixedCase.class);
    try (Connection con = TestUtil.openDB();
         CallableStatement cs = con.prepareCall("{ ? = call idnorm_f_mixed() }")) {
      cs.registerOutParameter(1, Types.STRUCT, MIXED_TYPE);
      cs.execute();
      Object value = cs.getObject(1, typeMap);
      assertInstanceOf(MixedCase.class, value,
          label + ": OUT must resolve to MixedCase, got " + value);
      MixedCase m = (MixedCase) value;
      assertEquals("out-mixed", m.label);
      assertEquals(43, m.score);
    }
  }

  // ---------------------------------------------------------------------------
  // SQLData targets used by the surfaces above
  // ---------------------------------------------------------------------------

  /** Leaf composite for {@code idnorm_schema.bare_t}. */
  public static final class BareT implements SQLData {
    String typeName = "";
    String label = "";
    int score;

    @Override
    public String getSQLTypeName() {
      return typeName;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.label = stream.readString();
      this.score = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(label);
      stream.writeInt(score);
    }
  }

  /** Leaf composite for {@code idnorm_schema."MixedCase"}. */
  public static final class MixedCase implements SQLData {
    String typeName = "";
    String label = "";
    int score;

    @Override
    public String getSQLTypeName() {
      return typeName;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.label = stream.readString();
      this.score = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(label);
      stream.writeInt(score);
    }
  }

  /** Outer composite with a {@code bare_t} attribute. */
  public static final class OuterBareT implements SQLData {
    String typeName = "";
    int id;
    BareT innerAttr;

    @Override
    public String getSQLTypeName() {
      return typeName;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.id = stream.readInt();
      this.innerAttr = (BareT) stream.readObject();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(id);
      stream.writeObject(innerAttr);
    }
  }

  /** Outer composite with a {@code "MixedCase"} attribute. */
  public static final class OuterMixedT implements SQLData {
    String typeName = "";
    int id;
    MixedCase innerAttr;

    @Override
    public String getSQLTypeName() {
      return typeName;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.id = stream.readInt();
      this.innerAttr = (MixedCase) stream.readObject();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeInt(id);
      stream.writeObject(innerAttr);
    }
  }
}
