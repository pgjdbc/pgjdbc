/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Asserts the typecache refactor doesn't add hidden round-trips for the
 * built-in types whose metadata is preloaded into
 * {@code TypeInfoCache.DEFAULT_TYPES_BY_OID} / {@code BASE_TYPES}.
 *
 * <p>Uses {@link CountingSocketFactory} (a wire-level counter that
 * registers a write→read direction transition as one round-trip). Each
 * test snapshots the counter before and after the code under test and
 * asserts on the delta.</p>
 */
@Isolated("The roundtrip counter is sensitive to background connection activity")
public class TypeCacheRoundTripTest {

  private static Connection openWithCounter(CountingSocketFactory.Counters counters)
      throws SQLException {
    Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getUser());
    PGProperty.PASSWORD.set(props, TestUtil.getPassword());
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, counters.key());
    String url = "jdbc:postgresql://" + TestUtil.getServer()
        + ":" + TestUtil.getPort()
        + "/" + TestUtil.getDatabase();
    return DriverManager.getConnection(url, props);
  }

  /**
   * Repeated execution of a server-prepared {@code SELECT 1::int4} keeps
   * exactly one round-trip per execute. If the driver were doing a lazy
   * {@code SELECT FROM pg_type} for the int4 column the first time it
   * saw the OID, the first execute would cost one extra round-trip
   * compared to the second.
   */
  @Test
  void int4SelectHasNoLazyMetadataLookup() throws Exception {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();
    try (Connection conn = openWithCounter(counters)) {
      try (PreparedStatement ps = conn.prepareStatement("SELECT 1::int4")) {
        long beforeFirst = counters.roundtrips.get();
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1));
        }
        long firstExecuteRoundTrips = counters.roundtrips.get() - beforeFirst;

        long beforeSecond = counters.roundtrips.get();
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getInt(1));
        }
        long secondExecuteRoundTrips = counters.roundtrips.get() - beforeSecond;

        assertEquals(
            firstExecuteRoundTrips,
            secondExecuteRoundTrips,
            "Repeated executeQuery() of `SELECT 1::int4` must cost the same number "
                + "of round-trips on every call. A larger first-call cost means the "
                + "driver is doing a lazy metadata lookup for the built-in int4 OID; "
                + "int4 is preloaded into TypeInfoCache.DEFAULT_TYPES_BY_OID and must "
                + "not trigger a pg_type lookup.");
        assertTrue(firstExecuteRoundTrips >= 1,
            "Expected at least one round-trip per executeQuery, got "
                + firstExecuteRoundTrips);
      }
    } finally {
      CountingSocketFactory.unregister(counters);
    }
  }

  /**
   * A plain {@link Statement} query selecting an {@code int4} literal
   * must not add extra round-trips for type metadata even on first use.
   * {@code SELECT 1::int4} is a single simple-query round-trip.
   */
  @Test
  void int4SelectDoesNotAddRoundTrips() throws Exception {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();
    try (Connection conn = openWithCounter(counters)) {
      long before = counters.roundtrips.get();
      try (Statement st = conn.createStatement();
           ResultSet rs = st.executeQuery("SELECT 1::int4")) {
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
      }
      long delta = counters.roundtrips.get() - before;
      assertEquals(1, delta,
          "SELECT 1::int4 should take exactly one round-trip — no extra "
              + "metadata lookup for the built-in int4 OID. Observed: " + delta);
    } finally {
      CountingSocketFactory.unregister(counters);
    }
  }

  /**
   * Positive-control test: a user-defined composite type *should* require
   * an extra round-trip on first use, because the driver has to fetch the
   * field layout from {@code pg_attribute}. Asserting that this scenario
   * registers more than one round-trip confirms
   * {@link CountingSocketFactory} actually detects extra traffic —
   * without this control the other two tests in this class could pass
   * vacuously if the counter were silently broken.
   */
  @Test
  void userCompositeSelectAddsLookupRoundTrip() throws Exception {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();
    try (Connection conn = openWithCounter(counters)) {
      try (Statement st = conn.createStatement()) {
        st.execute("DROP TYPE IF EXISTS rt_test_composite");
        st.execute("CREATE TYPE rt_test_composite AS (id int, name text)");
      }
      try {
        long before = counters.roundtrips.get();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                 "SELECT ROW(1, 'hello')::rt_test_composite")) {
          assertTrue(rs.next());
          // Force the codec to materialize the struct: this triggers
          // the per-OID pg_attribute lookup for the composite's fields.
          rs.getObject(1);
        }
        long delta = counters.roundtrips.get() - before;
        assertTrue(delta >= 2,
            "First SELECT of a user-defined composite type should cost at "
                + "least two round-trips (the SELECT plus a pg_type/pg_attribute "
                + "lookup). Observed " + delta + " round-trip(s); if this is "
                + "exactly 1 the counter is broken and the other tests in this "
                + "class are vacuously passing.");
      } finally {
        try (Statement st = conn.createStatement()) {
          st.execute("DROP TYPE IF EXISTS rt_test_composite");
        }
      }
    } finally {
      CountingSocketFactory.unregister(counters);
    }
  }
}
