/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;

import java.lang.reflect.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Shared machinery for the codec text/binary parity regression net.
 *
 * <p>The net pins the current decode/encode behaviour in place before the Phase 2 SPI refactor by
 * sending each value through two connections that differ only in wire format:</p>
 *
 * <ul>
 *   <li>a <b>text</b> connection ({@code binaryTransfer=false}) that forces every value over the
 *       text protocol in both directions, and</li>
 *   <li>a <b>binary</b> connection ({@code prepareThreshold=-1}) that issues a standalone Describe
 *       before the first Bind, so the server returns binary for every OID in the driver's binary set
 *       (and any extra OIDs opted in for the run).</li>
 * </ul>
 *
 * <p>A value decoded over both connections must come back equal, and equal to the original — that is
 * the {@code binary-decode == text-decode == original} invariant. In the simple query protocol the
 * binary connection silently falls back to text, so the comparison still holds (both sides text);
 * it just exercises a narrower path.</p>
 */
final class ParityHarness {

  private ParityHarness() {
  }

  /** Binds the parameters of a prepared statement; a no-op for literal {@code SELECT}s. */
  @FunctionalInterface
  interface Binder {
    void bind(PreparedStatement ps) throws SQLException;
  }

  /** A binder that sets no parameters, for queries that carry their value as a literal. */
  static final Binder NO_PARAMS = ps -> {
  };

  /**
   * Opens a connection that receives and sends every value over the text protocol.
   */
  static Connection openText() throws Exception {
    Properties props = new Properties();
    // false disables the whole built-in binary set, so nothing negotiates a binary format code.
    PGProperty.BINARY_TRANSFER.set(props, false);
    return TestUtil.openDB(props);
  }

  /**
   * Opens a connection that receives binary for every OID the driver supports, plus any OIDs in
   * {@code extraEnabledOids} (a comma-separated OID list, or {@code null} for none). The standalone
   * Describe forced by {@code prepareThreshold=-1} lets the very first execute request binary.
   */
  static Connection openBinary(String extraEnabledOids) throws Exception {
    Properties props = new Properties();
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    if (extraEnabledOids != null && !extraEnabledOids.isEmpty()) {
      PGProperty.BINARY_TRANSFER_ENABLE.set(props, extraEnabledOids);
    }
    return TestUtil.openDB(props);
  }

  /**
   * Whether {@code oid} is forced into binary receive on this connection. Only OIDs passed to
   * {@link #openBinary} via {@code binaryTransferEnable} report {@code true} here: the driver's
   * capability-driven binary receive ({@code shouldReceiveBinary}) defers user types to text until a
   * result set first materializes their {@code PgType}, so a single execute would not exercise binary
   * for a named composite unless it is explicitly enabled.
   */
  static boolean binaryActiveFor(Connection con, int oid) throws SQLException {
    return con.unwrap(BaseConnection.class).getQueryExecutor().useBinaryForReceive(oid);
  }

  /** Joins OIDs into the comma-separated form expected by {@code binaryTransferEnable}. */
  static String oids(long... values) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(values[i]);
    }
    return sb.toString();
  }

  /** Looks up {@code {oid, typarray}} for a type by its (unqualified) {@code typname}. */
  static long[] oidAndArray(Connection con, String typeName) throws SQLException {
    try (PreparedStatement ps =
             con.prepareStatement("SELECT oid, typarray FROM pg_type WHERE typname = ?")) {
      ps.setString(1, typeName);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), () -> "pg_type has no row for typname=" + typeName);
        return new long[]{rs.getLong(1), rs.getLong(2)};
      }
    }
  }

  /** Runs {@code sql} on {@code con}, binds the parameters, and returns {@code getObject(1)}. */
  static Object decodeFirst(Connection con, String sql, Binder binder) throws SQLException {
    try (PreparedStatement ps = con.prepareStatement(sql)) {
      binder.bind(ps);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), () -> "no row returned for [" + sql + "]");
        return rs.getObject(1);
      }
    }
  }

  /**
   * Asserts that {@code sql}/{@code binder} decodes identically over the text and binary
   * connections. Use when the canonical value is awkward to spell in Java (e.g. temporal types,
   * whose default mapping depends on the connection's codec context).
   */
  static void assertParity(Connection text, Connection binary, String sql, Binder binder)
      throws SQLException {
    Object t = decodeFirst(text, sql, binder);
    Object b = decodeFirst(binary, sql, binder);
    assertTrue(deepEquals(t, b),
        () -> "text/binary parity for [" + sql + "]: text=" + render(t) + " binary=" + render(b));
  }

  /**
   * Asserts that {@code sql}/{@code binder} decodes to {@code expected} over the text connection and
   * identically over the binary connection — the {@code binary == text == original} invariant.
   * {@code expected} may be {@code null} (e.g. a SQL NULL value).
   */
  static void assertParityEquals(Connection text, Connection binary, String sql, Binder binder,
      Object expected) throws SQLException {
    Object t = decodeFirst(text, sql, binder);
    Object b = decodeFirst(binary, sql, binder);
    assertTrue(deepEquals(expected, t),
        () -> "text roundtrip for [" + sql + "]: expected=" + render(expected) + " got=" + render(t));
    assertTrue(deepEquals(t, b),
        () -> "text/binary parity for [" + sql + "]: text=" + render(t) + " binary=" + render(b));
  }

  /**
   * Structural equality that unwraps {@link java.sql.Array} (to its element array) and
   * {@link java.sql.Struct} (to its attribute array), then compares arrays element-by-element. This
   * lets a decoded {@code Array}/{@code Struct} be matched against a plain Java array of expected
   * values, and a multidimensional array against nested Java arrays. Scalars fall back to
   * {@link Object#equals}, so {@code Double.NaN} matches {@code Double.NaN}.
   */
  static boolean deepEquals(Object a, Object b) throws SQLException {
    if (a == b) {
      return true;
    }
    if (a == null || b == null) {
      return false;
    }
    Object ua = unwrap(a);
    Object ub = unwrap(b);
    boolean aArray = ua.getClass().isArray();
    boolean bArray = ub.getClass().isArray();
    if (aArray || bArray) {
      if (!aArray || !bArray) {
        return false;
      }
      int n = Array.getLength(ua);
      if (n != Array.getLength(ub)) {
        return false;
      }
      for (int i = 0; i < n; i++) {
        if (!deepEquals(Array.get(ua, i), Array.get(ub, i))) {
          return false;
        }
      }
      return true;
    }
    return ua.equals(ub);
  }

  private static Object unwrap(Object o) throws SQLException {
    if (o instanceof java.sql.Array) {
      return ((java.sql.Array) o).getArray();
    }
    if (o instanceof java.sql.Struct) {
      return ((java.sql.Struct) o).getAttributes();
    }
    return o;
  }

  /** Renders a value (unwrapping Array/Struct) for assertion messages. */
  static String render(Object o) {
    Object u;
    try {
      u = unwrap(o);
    } catch (SQLException e) {
      return String.valueOf(o);
    }
    if (u == null) {
      return "null";
    }
    if (u.getClass().isArray()) {
      StringBuilder sb = new StringBuilder("[");
      int n = Array.getLength(u);
      for (int i = 0; i < n; i++) {
        if (i > 0) {
          sb.append(", ");
        }
        sb.append(render(Array.get(u, i)));
      }
      return sb.append(']').toString();
    }
    return u.getClass().getSimpleName() + "(" + u + ")";
  }
}
