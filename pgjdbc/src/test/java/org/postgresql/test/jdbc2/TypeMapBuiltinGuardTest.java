/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.core.Field;
import org.postgresql.geometric.PGpoint;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGobject;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The JDBC connection type map ({@link java.sql.Connection#setTypeMap}) customizes only user-defined
 * types. These tests pin that {@code ResultSet.getObject(int)} does not let a type-map entry for a
 * built-in type hijack a built-in column, on its own and as a field of an {@code SQLData} composite,
 * while a {@code PGConnection.addDataType} registration still overrides a built-in type.
 */
public class TypeMapBuiltinGuardTest extends BaseTest4 {

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createCompositeType(con, "f8_labeled", "label varchar, n int");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropType(con, "f8_labeled");
    super.tearDown();
  }

  @Test
  public void setTypeMapIgnoredForBuiltinVarchar() throws SQLException {
    Map<String, Class<?>> map = new HashMap<>();
    map.put("varchar", RefusingSqlData.class);
    con.setTypeMap(map);

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT 'abc'::varchar")) {
      assertTrue(rs.next());
      assertEquals("abc", rs.getObject(1));
    }
  }

  @Test
  public void setTypeMapIgnoredForBuiltinTimestamp() throws SQLException {
    Map<String, Class<?>> map = new HashMap<>();
    map.put("timestamp", RefusingSqlData.class);
    con.setTypeMap(map);

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT TIMESTAMP '2021-02-03 04:05:06'")) {
      assertTrue(rs.next());
      assertInstanceOf(Timestamp.class, rs.getObject(1));
    }
  }

  @Test
  public void setTypeMapIgnoredForBuiltinFieldInsideComposite() throws SQLException {
    // The composite is user-defined, so it is mapped; its varchar field is built-in, so the
    // {"varchar" -> RefusingSqlData} entry must not reach it. Without the guard, reading the nested
    // field through SQLInput.readObject() throws.
    Map<String, Class<?>> map = new HashMap<>();
    map.put("f8_labeled", Labeled.class);
    map.put("varchar", RefusingSqlData.class);
    con.setTypeMap(map);

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT ROW('hello', 7)::f8_labeled")) {
      assertTrue(rs.next());
      Labeled labeled = assertInstanceOf(Labeled.class, rs.getObject(1));
      assertInstanceOf(String.class, labeled.label);
      assertEquals("hello", labeled.label);
      assertEquals(7, labeled.n);
    }
  }

  @Test
  public void setTypeMapIgnoredForBuiltinFieldInsideCompositeBinary() throws SQLException {
    // Same as the previous test, but the composite is transferred in binary so the nested field is
    // read through PgSQLInputBinary.decodeObject() rather than the text path.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection binCon = TestUtil.openDB(props)) {
      assumeTrue(binCon.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE,
          "binary transfer needs the extended protocol");

      Map<String, Class<?>> map = new HashMap<>();
      map.put("f8_labeled", Labeled.class);
      map.put("varchar", RefusingSqlData.class);
      binCon.setTypeMap(map);

      try (PreparedStatement ps = binCon.prepareStatement("SELECT ROW('hello', 7)::f8_labeled");
           ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(Field.BINARY_FORMAT, ((PGResultSetMetaData) rs.getMetaData()).getFormat(1),
            "composite must come back in binary for this regression to exercise PgSQLInputBinary");
        Labeled labeled = assertInstanceOf(Labeled.class, rs.getObject(1));
        assertInstanceOf(String.class, labeled.label);
        assertEquals("hello", labeled.label);
        assertEquals(7, labeled.n);
      }
    }
  }

  @Test
  public void addDataTypeStillOverridesBuiltin() throws SQLException {
    con.unwrap(PGConnection.class).addDataType("varchar", VarcharPgObject.class);

    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT 'abc'::varchar")) {
      assertTrue(rs.next());
      VarcharPgObject value = assertInstanceOf(VarcharPgObject.class, rs.getObject(1));
      assertEquals("abc", value.getValue());
    }
  }

  @Test
  public void builtinGeometricAndIntervalDefaultsUnchanged() throws SQLException {
    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SELECT point '(1,2)', interval '1 day'")) {
      assertTrue(rs.next());
      assertInstanceOf(PGpoint.class, rs.getObject(1));
      assertInstanceOf(PGInterval.class, rs.getObject(2));
    }
  }

  /**
   * A type-map value for a built-in type. It refuses to be read: if the guard ever lets it reach a
   * built-in column, {@code getObject} fails loudly instead of silently returning the wrong type.
   */
  public static class RefusingSqlData implements SQLData {
    @Override
    public String getSQLTypeName() {
      return "varchar";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      throw new SQLException("type map entry for a built-in type must not be consulted");
    }

    @Override
    public void writeSQL(SQLOutput stream) {
    }
  }

  /** Composite whose built-in {@code varchar} field is read through {@link SQLInput#readObject()}. */
  public static class Labeled implements SQLData {
    Object label;
    int n;

    @Override
    public String getSQLTypeName() {
      return "f8_labeled";
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      label = stream.readObject();
      n = stream.readInt();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeObject((SQLData) null);
      stream.writeInt(n);
    }
  }

  /** PGobject subclass registered for {@code varchar} via {@code addDataType}. */
  public static class VarcharPgObject extends PGobject {
    public VarcharPgObject() {
      setType("varchar");
    }
  }
}
