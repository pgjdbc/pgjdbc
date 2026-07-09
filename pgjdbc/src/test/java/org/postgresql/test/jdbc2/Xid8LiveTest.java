/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PGSQLType;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Live round-trip coverage for {@code xid8} (PostgreSQL 13+, an unsigned 64-bit transaction ID),
 * through an actual table column so both the encode (parameter) and decode (result column) side
 * of {@code Xid8Codec} are exercised over the wire, not just the codec's unit tests.
 *
 * <p>{@code Xid8Codec} represents the value by its raw bit pattern, so a value at or above
 * 2<sup>63</sup> comes back from {@code getLong()} as a negative number; this test drives that
 * value through the server to confirm the bit pattern that {@code xid8send}/{@code xid8recv} put
 * on the wire round-trips exactly, and that {@code getString()} recovers PostgreSQL's own
 * unsigned decimal text. See {@link Oid8LiveTest} for the identical rationale over {@code oid8}.</p>
 */
class Xid8LiveTest extends BaseTest4 {

  private static final String TABLE = "xid8_live_test";
  private static final long MAX_UNSIGNED = -1L; // bit pattern of 2^64 - 1

  @Override
  public void setUp() throws Exception {
    super.setUp();
    // xid8 does not exist before PostgreSQL 13.
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v13));
    TestUtil.createTable(con, TABLE, "id int4 primary key, val xid8, vals xid8[]");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, TABLE);
    super.tearDown();
  }

  /**
   * Targets tried for the parameterized tests below: {@link JDBCType#OTHER} binds the
   * parameter unspecified-typed and lets the server infer {@code xid8} from context -- the only
   * option available to a caller, since there is no {@code java.sql.Types} constant for
   * {@code xid8} (and, unlike {@code oid8}, PostgreSQL defines no cast from bigint to {@code xid8}
   * at all -- a bare {@code setObject(Long)} would bind bigint and the insert would fail).
   * {@link PGSQLType#XID8} instead carries a concrete OID and binds directly through the codec
   * registry, exercising the routing added to setObject/updateObject/registerOutParameter for a
   * vendor-specific {@link SQLType}; PGSQLTypeCompletenessTest guards that this constant (and the
   * codec behind it) stays in sync with Oid.java as new built-in types land. Testing only
   * {@code Types.OTHER} would exercise little beyond "an unspecified parameter can be inferred",
   * so both are covered here. See {@link Oid8LiveTest} for the identical rationale over
   * {@code oid8}.
   */
  static Iterable<SQLType> targetSqlTypes() {
    return Arrays.asList(JDBCType.OTHER, PGSQLType.XID8);
  }

  @ParameterizedTest
  @MethodSource("targetSqlTypes")
  void scalarRoundTrip(SQLType targetSqlType) throws SQLException {
    long[] values = {0L, 42L, Long.MAX_VALUE, Long.MIN_VALUE, MAX_UNSIGNED};
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)")) {
      for (int i = 0; i < values.length; i++) {
        insert.setInt(1, i);
        insert.setObject(2, values[i], targetSqlType);
        assertEquals(1, insert.executeUpdate());
      }
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT val FROM " + TABLE + " WHERE id = ?")) {
      for (int i = 0; i < values.length; i++) {
        select.setInt(1, i);
        try (ResultSet rs = select.executeQuery()) {
          assertTrue(rs.next(), "one row expected for index " + i);
          assertEquals(values[i], rs.getLong(1), "bit pattern for " + values[i]);
          assertEquals(Long.toUnsignedString(values[i]), rs.getString(1),
              "unsigned text for " + values[i]);
        }
      }
    }
  }

  @Test
  void updateObject_withPGSQLType() throws SQLException {
    // Same PGSQLType.XID8 routing as scalarRoundTrip()'s PGSQLType.XID8 case, but through an
    // updatable ResultSet: PgResultSet#updateObject(int, Object, SQLType) stashes the target type
    // and insertRow()/updateRow() bind it via PreparedStatement#setObject(int, Object, SQLType)
    // instead of the type-inferring overload. See Oid8LiveTest for the identical rationale.
    // xid8 has no cast from integer at all, not even an explicit one (see scalarRoundTrip), so
    // the seed row goes through its text input function instead.
    TestUtil.execute(con, "INSERT INTO " + TABLE + " (id, val) VALUES (0, '0'::xid8)");
    try (Statement stmt = con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE,
        ResultSet.CONCUR_UPDATABLE);
        ResultSet rs = stmt.executeQuery("SELECT id, val FROM " + TABLE + " WHERE id = 0")) {
      assertTrue(rs.next());
      rs.updateObject("val", MAX_UNSIGNED, PGSQLType.XID8);
      rs.updateRow();
      assertEquals(MAX_UNSIGNED, rs.getLong("val"));
    }

    try (PreparedStatement select = con.prepareStatement("SELECT val FROM " + TABLE + " WHERE id = 0");
        ResultSet rs = select.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(MAX_UNSIGNED, rs.getLong(1));
    }
  }

  @Test
  void registerOutParameter_withPGSQLType() throws SQLException {
    // Same PGSQLType.XID8 routing as scalarRoundTrip()'s PGSQLType.XID8 case, but for an OUT
    // parameter: PgCallableStatement#registerOutParameter(int, SQLType) resolves the exact java.sql.Types
    // code the OID maps to via the type registry, instead of guessing it from the type's name,
    // so it matches whatever ResultSetMetaData#getColumnType() reports for the real xid8 result
    // column and the post-execute type check in executeWithFlags() does not spuriously mismatch.
    TestUtil.execute(con,
        "CREATE OR REPLACE FUNCTION xid8_live_test_identity(val xid8) RETURNS xid8 AS "
            + "'SELECT val' LANGUAGE SQL");
    try {
      try (CallableStatement call = con.prepareCall("{ ? = call xid8_live_test_identity(?) }")) {
        call.registerOutParameter(1, PGSQLType.XID8);
        call.setObject(2, MAX_UNSIGNED, PGSQLType.XID8);
        call.execute();
        assertEquals(MAX_UNSIGNED, ((Long) call.getObject(1)).longValue());
      }
    } finally {
      TestUtil.dropFunction(con, "xid8_live_test_identity", "xid8");
    }
  }

  @ParameterizedTest
  @MethodSource("targetSqlTypes")
  void narrowingRefusesOutOfUnsignedRange(SQLType targetSqlType) throws SQLException {
    long fitsUnsignedInt = 0xFFFFFFFFL; // 2^32 - 1, the top of the unsigned int range
    long beyondUnsignedInt = 0x1_0000_0000L; // 2^32, one past it
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO " + TABLE + " (id, val) VALUES (?, ?)")) {
      insert.setInt(1, 0);
      insert.setObject(2, fitsUnsignedInt, targetSqlType);
      insert.executeUpdate();
      insert.setInt(1, 1);
      insert.setObject(2, beyondUnsignedInt, targetSqlType);
      insert.executeUpdate();
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT val FROM " + TABLE + " WHERE id = ?")) {
      select.setInt(1, 0);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        // 2^32 - 1 fits the unsigned int range; getInt() returns its bit pattern.
        assertEquals(-1, rs.getInt(1));
      }

      select.setInt(1, 1);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next());
        assertThrows(PSQLException.class, () -> rs.getInt(1),
            "2^32 does not fit the unsigned int range");
      }
    }
  }

  @Test
  void arrayRoundTrip() throws SQLException {
    Long[] values = {0L, 42L, Long.MAX_VALUE, Long.MIN_VALUE, MAX_UNSIGNED};
    try (PreparedStatement insert = con.prepareStatement(
        "INSERT INTO " + TABLE + " (id, vals) VALUES (?, ?)")) {
      insert.setInt(1, 0);
      insert.setArray(2, con.createArrayOf("xid8", values));
      assertEquals(1, insert.executeUpdate());
    }

    try (PreparedStatement select = con.prepareStatement(
        "SELECT vals FROM " + TABLE + " WHERE id = ?")) {
      select.setInt(1, 0);
      try (ResultSet rs = select.executeQuery()) {
        assertTrue(rs.next(), "one row expected");
        Array array = rs.getArray(1);
        assertArrayEquals(values, (Object[]) array.getArray());
      }
    }
  }
}
