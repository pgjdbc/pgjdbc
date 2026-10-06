/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.util.CountingSocketFactory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * {@code extra_float_digits} reaches a live server with the value the assumed version calls for,
 * and delivering it in the startup packet spares the setup query the {@code SET} costs
 * (discussion #4306).
 *
 * <p>Which channel carries which parameter is pinned in
 * {@code org.postgresql.core.v3.InitialSessionParametersTest}; these tests read the outcome back
 * from a server. The server version splits them: before v12 the driver has to raise the setting
 * either way, and from v12 it sends nothing unless {@code assumeMinServerVersion} asks for the
 * packet.</p>
 *
 * <p>Each CI job picks a random {@code assumeMinServerVersion} and passes it to every test in the
 * run as a system property (see {@code .github/workflows/matrix.mjs}), which
 * {@code TestUtil.mergeDefaultProperties} ranks below a value the test sets itself. Every test here
 * therefore sets the property explicitly rather than inheriting whatever the job chose.</p>
 */
class ExtraFloatDigitsStartupTest {

  static Stream<Arguments> deliveryChannels() {
    // An empty assumeMinServerVersion parses to version 0, the same as an absent property, so it
    // leaves extra_float_digits to the post-authentication SET.
    return Stream.of(
        argumentSet("post-authentication SET", ""),
        argumentSet("startup packet", "9.0"));
  }

  /**
   * A pre-v12 server starts at {@code extra_float_digits} 0, which prints floats at a precision
   * that does not read back exactly, so a value of 3 is the driver's doing and not the server's
   * default.
   */
  @ParameterizedTest
  @MethodSource("deliveryChannels")
  @EnabledForServerVersionRange(lt = "12")
  void extraFloatDigitsIsThreeOnPre12(String assumeMinServerVersion) throws Exception {
    assertEquals("3", showExtraFloatDigits(assumeMinServerVersion), "SHOW extra_float_digits");
  }

  static Stream<Arguments> assumedVersionsOn12Plus() {
    return Stream.of(
        argumentSet("no assumed version", "", "1"),
        argumentSet("assumed 9.0", "9.0", "3"));
  }

  /**
   * A v12 server already prints floats in the shortest form that reads back exactly under its own
   * default of 1, so the driver leaves the setting alone. An {@code assumeMinServerVersion} from
   * 9.0 to 11.x still puts 3 in the startup packet, because the packet is built before the real
   * version is known, and the setting then reads 3.
   */
  @ParameterizedTest
  @MethodSource("assumedVersionsOn12Plus")
  @EnabledForServerVersionRange(gte = "12")
  void extraFloatDigitsFollowsTheAssumedVersionOn12Plus(String assumeMinServerVersion,
      String expected) throws Exception {
    assertEquals(expected, showExtraFloatDigits(assumeMinServerVersion),
        "SHOW extra_float_digits");
  }

  /**
   * Raising {@code extra_float_digits} from a v12 server's default of 1 to 3 changes no float text,
   * because every value above 0 selects the same shortest form that reads back exactly.
   */
  @Test
  @EnabledForServerVersionRange(gte = "12")
  void floatTextIsUnchangedByTheAssumedVersionOn12Plus() throws Exception {
    assertEquals(selectFloatText(""), selectFloatText("9.0"),
        "float text with assumeMinServerVersion=9.0");
  }

  /**
   * Delivering {@code extra_float_digits} in the startup packet spares the whole setup query, and
   * with it one round trip. The two connections differ only in {@code assumeMinServerVersion}, so
   * the rest of the handshake is identical. The test URL always carries {@code ApplicationName}, so
   * both parameters take the same channel here: the {@code SET} path runs one setup query holding
   * both statements, and the packet path runs none.
   *
   * <p>A session that rejects arbitrary SQL, such as the Greenplum retrieve mode in discussion
   * #4306, needs that query not to be sent at all. No test server here rejects SQL, so the test
   * observes the saving through the round-trip count instead.</p>
   */
  @Test
  @EnabledForServerVersionRange(lt = "12")
  void startupPacketSavesOneRoundTripOnPre12() throws Exception {
    long viaPacket = handshakeRoundTrips("9.0");
    long viaSet = handshakeRoundTrips("");

    assertAll(
        () -> assertNotEquals(0, viaSet,
            "handshakeRoundTrips(\"\"); zero means the connection bypassed CountingSocketFactory"),
        () -> assertEquals(viaSet - 1, viaPacket, "handshakeRoundTrips(\"9.0\")"));
  }

  private static String showExtraFloatDigits(String assumeMinServerVersion) throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName(), assumeMinServerVersion);

    try (Connection con = TestUtil.openDB(props);
         Statement st = con.createStatement();
         ResultSet rs = st.executeQuery("SHOW extra_float_digits")) {
      assertTrue(rs.next(), "SHOW extra_float_digits must return a row");
      return rs.getString(1);
    }
  }

  /**
   * Opens a connection with the given {@code assumeMinServerVersion} and returns the text the
   * server renders for four {@code float4} and {@code float8} values. Each renders differently at
   * {@code extra_float_digits} 1 and 3 under the digit-count rule that servers before v12 apply,
   * so the comparison catches a server that still honors the setting that way.
   */
  private static String selectFloatText(String assumeMinServerVersion) throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.ASSUME_MIN_SERVER_VERSION.getName(), assumeMinServerVersion);

    try (Connection con = TestUtil.openDB(props);
         Statement st = con.createStatement();
         ResultSet rs = st.executeQuery(
             "select (1.0/3)::float8, 0.1::float4, 1e-7::float8, 3.14159265358979::float8")) {
      assertTrue(rs.next(), "the float query must return a row");
      return rs.getString(1) + ' ' + rs.getString(2) + ' ' + rs.getString(3) + ' '
          + rs.getString(4);
    }
  }

  /**
   * Opens a connection with the given {@code assumeMinServerVersion} through
   * {@link CountingSocketFactory} and returns how many write-then-read turns the handshake took.
   */
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
