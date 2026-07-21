/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

/**
 * Server-observable checks for how the driver delivers {@code extra_float_digits} when connecting
 * to a pre-v12 server (issue #4306). The pure placement decision is unit-tested in
 * {@code org.postgresql.core.v3.InitialSessionParametersTest}; these tests exercise the two
 * delivery channels against a live server.
 *
 * <p>The CI matrix runs the whole suite against many server versions, each with a randomly chosen
 * {@code assumeMinServerVersion} (see {@code .github/workflows/matrix.mjs}), so these tests set the
 * property explicitly rather than relying on whatever the job picked.</p>
 */
class ExtraFloatDigitsStartupTest {

  /**
   * Whichever channel delivers it, {@code extra_float_digits} must end up at 3 on a pre-v12 server
   * so float text round-trips. {@code assumeMinServerVersion=9.0} puts the value in the startup
   * packet; an empty value forces the post-authentication {@code SET}. The server default before
   * v12 is 0, so a value of 3 proves the driver set it.
   */
  @ParameterizedTest(name = "assumeMinServerVersion=''{0}''")
  @ValueSource(strings = {"", "9.0"})
  @EnabledForServerVersionRange(lt = "12")
  void extraFloatDigitsIsThreeOnPre12(String assumeMinServerVersion) throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName(), assumeMinServerVersion);

    try (Connection con = TestUtil.openDB(props);
         Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SHOW extra_float_digits")) {
      assertTrue(rs.next(), "SHOW extra_float_digits returned no row");
      assertEquals("3", rs.getString(1),
          "extra_float_digits must be 3 on a pre-v12 server (assumeMinServerVersion='"
              + assumeMinServerVersion + "')");
    }
  }

  /**
   * Delivering {@code extra_float_digits} in the startup packet instead of a post-authentication
   * {@code SET} saves one connection round trip on a pre-v12 server. Both connections use identical
   * parameters apart from {@code assumeMinServerVersion}, so the handshake traffic cancels and the
   * only difference is the {@code SET} that the packet path avoids.
   */
  @Test
  @EnabledForServerVersionRange(lt = "12")
  void startupPacketSavesOneRoundTripOnPre12() throws Exception {
    long viaPacket = handshakeRoundTrips("9.0"); // 9.0 <= assumeMinServerVersion < 12: startup packet
    long viaSet = handshakeRoundTrips("");        // no assumed version: post-authentication SET

    assertTrue(viaPacket > 0 && viaSet > 0,
        "CountingSocketFactory observed no round trips (viaPacket=" + viaPacket
            + ", viaSet=" + viaSet + "); the socket-factory wiring is broken");
    assertEquals(viaSet - 1, viaPacket,
        "startup-packet delivery should use exactly one fewer round trip than the SET path"
            + " (viaSet=" + viaSet + ", viaPacket=" + viaPacket + ")");
  }

  private static long handshakeRoundTrips(String assumeMinServerVersion) throws Exception {
    CountingSocketFactory.Counters counters = CountingSocketFactory.register();
    try {
      Properties props = new Properties();
      props.setProperty(PGProperty.SOCKET_FACTORY.getName(), CountingSocketFactory.class.getName());
      props.setProperty(PGProperty.SOCKET_FACTORY_ARG.getName(), counters.key());
      props.setProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName(), assumeMinServerVersion);
      // getConnection returns once the handshake has finished, so the counter is stable here.
      try (Connection con = TestUtil.openDB(props)) {
        return counters.roundtrips.get();
      }
    } finally {
      CountingSocketFactory.unregister(counters);
    }
  }
}
