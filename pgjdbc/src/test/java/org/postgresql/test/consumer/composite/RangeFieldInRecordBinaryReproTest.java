/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.consumer.composite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLData;
import java.sql.SQLException;
import java.sql.SQLInput;
import java.sql.SQLOutput;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reproduces the reviewer report on PR #3062: a range field nested in a composite
 * type, read through {@link SQLData#readSQL} as a string with binary transfer
 * enabled, used to fail with {@code "No results were returned by the query"} from
 * {@code RangeCodec.decodeBinary}. The cause is that {@code pg_type.typelem} is
 * {@code 0} for ranges, so the subtype must come from {@code pg_range.rngsubtype}
 * instead.
 *
 * @see <a href="https://github.com/pgjdbc/pgjdbc/pull/3062#issuecomment-4840951538">PR #3062 review comment</a>
 */
public class RangeFieldInRecordBinaryReproTest {

  private static final String TYPE = "range_in_record_t";
  private static final int INT4RANGE_OID = 3904;

  @BeforeAll
  static void setUp() throws SQLException {
    // int4range was introduced in PostgreSQL 9.2; older servers reject the composite definition
    // below with "type int4range does not exist".
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v9_2);
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createCompositeType(con, TYPE, "i int4range");
    }
  }

  @AfterAll
  static void tearDown() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropType(con, TYPE);
    }
  }

  /**
   * Mirrors the reviewer's repro: register the composite OID (and its range
   * subtype) for binary transfer, then read {@code row('[1,2]'::int4range)::t}
   * via {@code getObject(1, Map)} into an {@link SQLData} that pulls the range
   * field out as a string. {@code int4range} canonicalises {@code [1,2]} to
   * {@code [1,3)}.
   */
  @Test
  void rangeFieldReadAsStringViaSqlDataInBinary() throws SQLException {
    int typeOid;
    try (Connection con = TestUtil.openDB()) {
      typeOid = lookupOid(con, TYPE);
    }

    Properties props = new Properties();
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props,
        Oid.RECORD + "," + INT4RANGE_OID + "," + typeOid);

    try (Connection con = TestUtil.openDB(props)) {
      // The simple query protocol cannot negotiate binary format codes.
      assumeTrue(con.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE);
      Map<String, Class<?>> map = new HashMap<>();
      map.put(TYPE, RangeHolder.class);
      try (PreparedStatement ps = con.prepareStatement(
              "select row('[1,2]'::int4range)::" + TYPE);
          ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "one row expected");
        Object value = rs.getObject(1, map);
        RangeHolder holder = assertInstanceOf(RangeHolder.class, value,
            "composite must decode into the SQLData target");
        assertEquals("[1,3)", holder.span,
            "the range subtype must resolve via pg_range.rngsubtype, not pg_type.typelem");
      }
    }
  }

  private static int lookupOid(Connection con, String typeName) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(
        "select oid from pg_type where typname = ?")) {
      ps.setString(1, typeName);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "type " + typeName + " must exist");
        return rs.getInt(1);
      }
    }
  }

  /** SQLData target mirroring the reviewer's repro: reads the range field as a string. */
  public static final class RangeHolder implements SQLData {
    String typeName = "";
    String span = "";

    @Override
    public String getSQLTypeName() {
      return typeName;
    }

    @Override
    public void readSQL(SQLInput stream, String typeName) throws SQLException {
      this.typeName = typeName;
      this.span = stream.readString();
    }

    @Override
    public void writeSQL(SQLOutput stream) throws SQLException {
      stream.writeString(span);
    }
  }
}
