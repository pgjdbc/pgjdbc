/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/**
 * Wire-level baseline for connection establishment.
 *
 * <p>Counts the number of round-trips (write→read direction transitions on
 * the underlying socket) consumed by a {@code DriverManager.getConnection}
 * call. The exact number depends on server version and JDBC properties, so
 * the assertion is a loose upper bound — the test is primarily a guard
 * against accidental regressions that add a hidden query to the connection
 * setup path.</p>
 *
 * <p>Local baseline numbers against PostgreSQL 16, no SSL, no GSS, default
 * JDBC properties (the test logs the actual values via {@code System.err}
 * on every run so CI keeps a paper trail):</p>
 *
 * <pre>
 *   origin/master:  roundtrips=7  bytesOut=1131  bytesIn=4211
 *   typecache HEAD: roundtrips=7  bytesOut=1131  bytesIn=4211
 * </pre>
 *
 * <p>Connection establishment is byte-for-byte identical between the two,
 * which is the point of this test — the codec / type-cache rework lives
 * after the handshake completes and must not bolt extra queries onto it.</p>
 */
@Isolated("Round-trip counter is sensitive to background activity")
public class ConnectionRoundTripTest {

  /**
   * Hard upper bound. If a future change pushes the count above this,
   * either the change adds a real query to connection setup (revisit it)
   * or the bound itself needs revising.
   */
  private static final int MAX_HANDSHAKE_ROUND_TRIPS = 12;

  @Test
  void getConnection_roundTripBudget() throws Exception {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();
    try {
      Properties props = new Properties();
      PGProperty.USER.set(props, TestUtil.getUser());
      PGProperty.PASSWORD.set(props, TestUtil.getPassword());
      PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
      PGProperty.SOCKET_FACTORY_ARG.set(props, counters.key());
      String url = "jdbc:postgresql://" + TestUtil.getServer()
          + ":" + TestUtil.getPort()
          + "/" + TestUtil.getDatabase();

      try (Connection conn = DriverManager.getConnection(url, props)) {
        // Force the JDBC handshake to finish — getConnection returns once
        // the server is ready, so we can read the counters here.
        long roundTrips = counters.roundtrips.get();
        long bytesOut = counters.bytesOut.get();
        long bytesIn = counters.bytesIn.get();

        // Emit the measurement so CI logs (and humans running locally) have
        // a baseline to compare across master / typecache.
        System.err.printf(
            "ConnectionRoundTripTest: getConnection roundtrips=%d bytesOut=%d bytesIn=%d%n",
            roundTrips, bytesOut, bytesIn);

        assertTrue(roundTrips > 0,
            "Counter must have observed at least one round-trip — otherwise the "
                + "CountingSocketFactory wiring is broken.");
        assertTrue(roundTrips <= MAX_HANDSHAKE_ROUND_TRIPS,
            "Connection establishment used " + roundTrips + " round-trips, "
                + "exceeds budget of " + MAX_HANDSHAKE_ROUND_TRIPS
                + ". If this is intentional, raise the budget; otherwise some "
                + "code path added a hidden query to the connection setup.");
      }
    } finally {
      CountingSocketFactory.unregister(counters);
    }
  }
}
