/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.v3.ConnectionFactoryImpl.InitialSessionParameters;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Each of {@code extra_float_digits} and {@code application_name} reaches the server through at
 * most one channel: the startup packet, decided before connecting from
 * {@code assumeMinServerVersion}, or a post-authentication {@code SET}, decided from the version
 * the server reports. A parameter goes through neither when the connected server needs no value
 * or has no such parameter. Only {@code application_name} takes the packet;
 * {@code extra_float_digits} takes the {@code SET} whenever the server needs it.
 *
 * <p>{@link #matrix()} states that placement as a table. A row fixes an assumed version, a server
 * version, and an {@code application_name}, and gives both outcomes: what the startup packet
 * carries, and the exact {@code SET}. The rows cover the two versions the decision turns on, 9.0
 * and 12, from the assumed side and from the real side, each with its neighbor on either side.</p>
 *
 * <p>Add a row when a change introduces a boundary. Editing an expected value is how a change to
 * what the driver puts on the wire becomes visible in review.</p>
 */
class InitialSessionParametersTest {

  static Stream<Arguments> matrix() {
    int pg83 = ServerVersion.v8_3.getVersionNum();
    int pg84 = ServerVersion.v8_4.getVersionNum();
    int pg90 = ServerVersion.v9_0.getVersionNum();
    int pg94 = ServerVersion.v9_4.getVersionNum();
    int pg11 = ServerVersion.v11.getVersionNum();
    int pg12 = ServerVersion.v12.getVersionNum();
    int pg14 = ServerVersion.v14.getVersionNum();

    return Stream.of(
        // --- no application_name: extra_float_digits is decided from the real server version alone
        argumentSet("no assumed version, server 8.4", null, pg84, null,
            "[]", "SET extra_float_digits = 2"),
        argumentSet("no assumed version, server 9.0", null, pg90, null,
            "[]", "SET extra_float_digits = 3"),
        argumentSet("no assumed version, server 11", null, pg11, null,
            "[]", "SET extra_float_digits = 3"),
        // An empty assumeMinServerVersion parses to version 0, the same as an absent property
        argumentSet("empty assumed version, server 11", "", pg11, null,
            "[]", "SET extra_float_digits = 3"),
        argumentSet("no assumed version, server 12", null, pg12, null,
            "[]", ""),
        argumentSet("no assumed version, server 14", null, pg14, null,
            "[]", ""),

        // Discussion #4306. An assumeMinServerVersion between 9.0 and 12 tells the driver before it
        // connects that extra_float_digits will be needed, and the value still goes out as a
        // post-authentication SET, which a restricted session such as Greenplum retrieve mode
        // rejects. The next commit moves it into the packet, and this row becomes a packet of
        // "[extra_float_digits=3]" with an empty SET.
        argumentSet("#4306: assumed 9.3, server 9.4", "9.3", pg94, null,
            "[]", "SET extra_float_digits = 3"),

        // --- application_name goes in the startup packet from an assumed 9.0 up
        argumentSet("assumed 9.0, server 11", "9.0", pg11, "myapp",
            "[application_name=myapp]", "SET extra_float_digits = 3"),
        argumentSet("assumed 9.0, server 14", "9.0", pg14, "myapp",
            "[application_name=myapp]", ""),
        argumentSet("assumed 12, server 11", "12", pg11, "myapp",
            "[application_name=myapp]", "SET extra_float_digits = 3"),
        argumentSet("assumed 14, server 14", "14", pg14, "myapp",
            "[application_name=myapp]", ""),
        // The startup packet carries a value verbatim, so a quote and a backslash in
        // application_name reach the server as typed.
        argumentSet("assumed 9.0, server 14, quoted application_name", "9.0", pg14, "a'\\b",
            "[application_name=a'\\b]", ""),
        // The assumed version can overstate the real one, and the packet is built before the real
        // one is known. The packet still carries application_name, while the real 8.x version
        // drives extra_float_digits.
        argumentSet("assumed 9.4, server 8.4", "9.4", pg84, "myapp",
            "[application_name=myapp]", "SET extra_float_digits = 2"),

        // --- application_name goes in the SET below an assumed 9.0, where the real server has it
        argumentSet("no assumed version, server 14, application_name", null, pg14, "myapp",
            "[]", "SET application_name = 'myapp'"),
        argumentSet("no assumed version, server 11, application_name", null, pg11, "myapp",
            "[]", "SET extra_float_digits = 3;SET application_name = 'myapp'"),
        argumentSet("assumed 8.4, server 9.0", "8.4", pg90, "myapp",
            "[]", "SET extra_float_digits = 3;SET application_name = 'myapp'"),
        // A pre-9.0 server has no application_name, so only extra_float_digits is sent
        argumentSet("no assumed version, server 8.4, application_name", null, pg84, "myapp",
            "[]", "SET extra_float_digits = 2"),
        argumentSet("assumed 8.4, server 8.3", "8.4", pg83, "myapp",
            "[]", "SET extra_float_digits = 2")
    );
  }

  /**
   * Compares the startup parameters through {@code List.toString()}, so a row pins the order they
   * are added in as well as their content.
   */
  @ParameterizedTest
  @MethodSource("matrix")
  void sendsEachParameterThroughAtMostOneChannel(String assumeMinServerVersion, int serverVersionNum,
      String applicationName, String expectedStartupPacket, String expectedInitialQuery) {
    InitialSessionParameters params =
        InitialSessionParameters.of(ServerVersion.from(assumeMinServerVersion), applicationName);

    assertAll(
        () -> assertEquals(expectedStartupPacket, params.startupPacketParameters().toString(),
            "startup packet"),
        () -> assertEquals(expectedInitialQuery, params.initialQuerySql(serverVersionNum, true),
            "initial query"));
  }

  static Stream<Arguments> escapingCases() {
    return Stream.of(
        argumentSet("standard_conforming_strings on", true,
            "SET application_name = 'a''\\b'"),
        argumentSet("standard_conforming_strings off", false,
            "SET application_name = 'a''\\\\b'"));
  }

  /**
   * The {@code SET} is the only channel that escapes {@code application_name}, so the value here
   * carries both characters the escaping treats differently: a single quote, doubled either way,
   * and a backslash, doubled only while {@code standard_conforming_strings} is off. An unset
   * assumed version keeps the parameter out of the startup packet and onto that channel.
   */
  @ParameterizedTest
  @MethodSource("escapingCases")
  void escapesApplicationNameInTheInitialQuery(boolean standardConformingStrings,
      String expectedInitialQuery) throws Exception {
    InitialSessionParameters params =
        InitialSessionParameters.of(ServerVersion.from(null), "a'\\b");

    assertEquals(expectedInitialQuery,
        params.initialQuerySql(ServerVersion.v14.getVersionNum(), standardConformingStrings));
  }
}
