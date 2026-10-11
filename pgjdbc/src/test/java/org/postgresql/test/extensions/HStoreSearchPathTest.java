/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.internal.Nullness;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Tests that {@code hstore} values map to {@link Map} when the type is off the connection's
 * {@code search_path}.
 *
 * <p>An extension can be installed only once per database, so these tests move the
 * {@code search_path} instead of the extension. Reads use a connection that has never sent an
 * hstore parameter, because that would cache the unqualified name and hide the bug.</p>
 */
@ParameterizedClass
@MethodSource("data")
public class HStoreSearchPathTest extends BaseTest4 {

  private static final String TEST_SCHEMA = "hstore_search_path";
  private static final String TEST_TABLE = TEST_SCHEMA + ".hstore_tbl";

  /** Resolved before the connection is opened, because binary transfer needs the oid up front. */
  private static int hstoreOid = Oid.UNSPECIFIED;
  private static @Nullable String hstoreSchema;
  private static boolean bootstrapped;

  public HStoreSearchPathTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    return Arrays.asList(
        new Object[]{BinaryMode.REGULAR},
        new Object[]{BinaryMode.FORCE});
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    // Keep the hstore type off the search_path, and put the test table on it
    PGProperty.OPTIONS.set(props, "-c search_path=" + TEST_SCHEMA);
    if (binaryMode == BinaryMode.FORCE && hstoreOid != Oid.UNSPECIFIED) {
      // forceBinary() enables binary transfer for bool only, so hstore must be added explicitly,
      // its oid not being static
      PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.BOOL + "," + hstoreOid);
    }
  }

  @Override
  public void setUp() throws Exception {
    bootstrap();
    assumeTrue(hstoreOid != Oid.UNSPECIFIED, "server has installed hstore");
    super.setUp();
    assumeFalse(preferQueryMode == PreferQueryMode.SIMPLE,
        "hstore is not supported in simple protocol only mode");
    assumeMinimumServerVersion("hstore requires PostgreSQL 8.3+", ServerVersion.v8_3);

    Connection con = Nullness.castNonNull(this.con);
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DELETE FROM " + TEST_TABLE);
    }
  }

  /** Runs on the default search_path, where hstore is still reachable unqualified. */
  private static void bootstrap() throws SQLException {
    if (bootstrapped) {
      return;
    }
    bootstrapped = true;
    try (Connection con = TestUtil.openDB()) {
      try (Statement stmt = con.createStatement();
           ResultSet rs = stmt.executeQuery(
               "SELECT t.oid, n.nspname FROM pg_catalog.pg_type t"
                   + " JOIN pg_catalog.pg_namespace n ON t.typnamespace = n.oid"
                   + " WHERE t.typname = 'hstore'")) {
        if (rs.next()) {
          hstoreOid = (int) rs.getLong(1);
          hstoreSchema = rs.getString(2);
        }
      }
      if (hstoreOid == Oid.UNSPECIFIED) {
        return;
      }
      TestUtil.createSchema(con, TEST_SCHEMA);
      TestUtil.createTable(con, TEST_TABLE,
          "id int, val \"" + Nullness.castNonNull(hstoreSchema) + "\".\"hstore\"");
    }
  }

  private Connection openTestConnection() throws SQLException {
    Properties props = new Properties();
    updateProperties(props);
    return TestUtil.openDB(props);
  }

  /**
   * Uses a separate connection: binding an hstore parameter caches the unqualified name, which
   * would mask what the read assertions check.
   */
  private void insert(int id, Map<String, String> value) throws SQLException {
    try (Connection writer = openTestConnection();
         PreparedStatement pstmt =
             writer.prepareStatement("INSERT INTO " + TEST_TABLE + " (id, val) VALUES (?, ?)")) {
      pstmt.setInt(1, id);
      pstmt.setObject(2, value);
      pstmt.executeUpdate();
    }
  }

  private ResultSet select(int id) throws SQLException {
    Connection con = Nullness.castNonNull(this.con);
    // Guard against passing for the wrong reason: hstore must not be reachable unqualified here
    assertNotEquals("hstore", ((BaseConnection) con).getTypeInfo().getPGType(hstoreOid),
        "hstore should be reported schema-qualified when its schema is off the search_path");
    PreparedStatement pstmt =
        con.prepareStatement("SELECT val FROM " + TEST_TABLE + " WHERE id = ?");
    pstmt.setInt(1, id);
    ResultSet rs = pstmt.executeQuery();
    assertTrue(rs.next(), "the inserted row should be readable back");
    return rs;
  }

  @Test
  public void testGetObjectPopulated() throws SQLException {
    Map<String, String> value = new LinkedHashMap<>();
    value.put("a", "1");
    value.put("b", "2");
    insert(1, value);

    try (ResultSet rs = select(1)) {
      assertEquals(new HashMap<>(value), rs.getObject(1));
    }
  }

  @Test
  public void testGetObjectEmpty() throws SQLException {
    insert(2, Collections.<String, String>emptyMap());

    try (ResultSet rs = select(2)) {
      assertEquals(Collections.<String, String>emptyMap(), rs.getObject(1));
    }
  }

  @Test
  public void testGetStringPopulated() throws SQLException {
    insert(3, Collections.singletonMap("a", "1"));

    try (ResultSet rs = select(3)) {
      assertEquals("\"a\"=>\"1\"", rs.getString(1));
    }
  }

  @Test
  public void testGetStringEmpty() throws SQLException {
    insert(4, Collections.<String, String>emptyMap());

    try (ResultSet rs = select(4)) {
      assertEquals("", rs.getString(1));
    }
  }

  /** getColumnClassName() reads the same name-keyed table, so it returned java.lang.String. */
  @Test
  public void testGetColumnClassName() throws SQLException {
    insert(7, Collections.singletonMap("a", "1"));

    try (ResultSet rs = select(7)) {
      assertEquals(Map.class.getName(), rs.getMetaData().getColumnClassName(1));
    }
  }

  /**
   * {@code currentSchema} and {@link Connection#setSchema(String)} issue
   * {@code SET SESSION search_path TO '<schema>'}, which replaces the path set on the role or
   * database. That is how hstore in {@code public} usually ends up off the path.
   */
  @Test
  public void testGetObjectWithCurrentSchemaProperty() throws SQLException {
    insert(6, Collections.singletonMap("a", "1"));

    Properties props = new Properties();
    if (binaryMode == BinaryMode.FORCE) {
      forceBinary(props);
      PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.BOOL + "," + hstoreOid);
    }
    PGProperty.CURRENT_SCHEMA.set(props, TEST_SCHEMA);

    try (Connection reader = TestUtil.openDB(props);
         PreparedStatement pstmt =
             reader.prepareStatement("SELECT val FROM " + TEST_TABLE + " WHERE id = 6");
         ResultSet rs = pstmt.executeQuery()) {
      assertTrue(rs.next(), "the inserted row should be readable back");
      assertEquals(Collections.singletonMap("a", "1"), rs.getObject(1));
    }
  }

  /** Writes already worked off the path; check the fix keeps them working. */
  @Test
  public void testWriteWorksOffSearchPath() throws SQLException {
    insert(5, Collections.singletonMap("a", "1"));

    Connection con = Nullness.castNonNull(this.con);
    try (PreparedStatement pstmt =
             con.prepareStatement("SELECT val::text FROM " + TEST_TABLE + " WHERE id = 5");
         ResultSet rs = pstmt.executeQuery()) {
      assertTrue(rs.next(), "the inserted row should be readable back");
      assertEquals("\"a\"=>\"1\"", rs.getString(1));
    }
  }
}
