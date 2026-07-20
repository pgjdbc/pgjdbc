/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import org.postgresql.core.ServerVersion;
import org.postgresql.core.v3.ConnectionFactoryImpl.InitialSessionParameters;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

/**
 * Golden truth table for {@link InitialSessionParameters}: for every combination of assumed version,
 * real server version, and {@code application_name}, it pins which parameters land in the startup
 * packet and the exact post-authentication {@code SET} statement.
 *
 * <p>The delivery of {@code extra_float_digits} and {@code application_name} has regressed repeatedly
 * (issues #3446, #3475, #3491, #3509, #3678) because the decision was implicit and split across two
 * methods. This test is the executable specification: any future edit either keeps the matrix green
 * or forces a visible, reviewable change to the expected values below.</p>
 */
class InitialSessionParametersTest {

  static Stream<Arguments> matrix() {
    int v8_3 = ServerVersion.v8_3.getVersionNum();
    int v8_4 = ServerVersion.v8_4.getVersionNum();
    int v9_0 = ServerVersion.v9_0.getVersionNum();
    int v9_4 = ServerVersion.v9_4.getVersionNum();
    int v11 = ServerVersion.v11.getVersionNum();
    int v12 = ServerVersion.v12.getVersionNum();
    int v14 = ServerVersion.v14.getVersionNum();

    return Stream.of(
        // --- no application_name: extra_float_digits is decided from the real server version alone
        arguments("no-app, server 8.4", null, v8_4, null, "[]",
            "SET extra_float_digits = 2"),
        arguments("no-app, server 9.0", null, v9_0, null, "[]",
            "SET extra_float_digits = 3"),
        arguments("no-app, server 11", null, v11, null, "[]",
            "SET extra_float_digits = 3"),
        arguments("no-app, server 12", null, v12, null, "[]", ""),
        arguments("no-app, server 14", null, v14, null, "[]", ""),

        // #4306, current behavior. With 9.0 <= assumeMinServerVersion < 12 the driver knows before
        // connecting that extra_float_digits belongs in the startup packet, yet it still sends the
        // value as a post-authentication SET, which a restricted session (Greenplum retrieve mode)
        // rejects. The fix will move it into the packet: this row then becomes packet
        // "[extra_float_digits=3]" and an empty SET.
        arguments("#4306: assume 9.3, server 9.4", "9.3", v9_4, null, "[]",
            "SET extra_float_digits = 3"),

        // --- application_name delivered in the startup packet (assumed version >= 9.0)
        arguments("app in packet, server 14", "9.0", v14, "myapp",
            "[application_name=myapp]", ""),
        arguments("app in packet, server 11", "9.0", v11, "myapp",
            "[application_name=myapp]", "SET extra_float_digits = 3"),
        arguments("app in packet, assume 14, server 14", "14", v14, "myapp",
            "[application_name=myapp]", ""),
        // assumed version above the real one: the packet still carries application_name, and the
        // real 8.x version drives extra_float_digits. Documents current (asymmetric) behavior.
        arguments("app in packet, assume 9.4, server 8.4", "9.4", v8_4, "myapp",
            "[application_name=myapp]", "SET extra_float_digits = 2"),

        // --- application_name delivered via SET (assumed version < 9.0 but real server supports it)
        arguments("app via set, server 14", null, v14, "myapp", "[]",
            "SET application_name = 'myapp'"),
        arguments("app via set + efd, server 11", null, v11, "myapp", "[]",
            "SET extra_float_digits = 3;SET application_name = 'myapp'"),
        arguments("app via set + efd, assume 8.4, server 9.0", "8.4", v9_0, "myapp", "[]",
            "SET extra_float_digits = 3;SET application_name = 'myapp'"),
        // real server too old for application_name: only extra_float_digits is sent
        arguments("app dropped, server 8.4", null, v8_4, "myapp", "[]",
            "SET extra_float_digits = 2"),
        arguments("app dropped, assume 8.4, server 8.3", "8.4", v8_3, "myapp", "[]",
            "SET extra_float_digits = 2")
    );
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("matrix")
  void placement(String name, String assumeMinServerVersion, int serverVersionNum,
      String applicationName, String expectedStartupPacket, String expectedInitialQuery)
      throws Exception {
    InitialSessionParameters params =
        InitialSessionParameters.of(ServerVersion.from(assumeMinServerVersion), applicationName);

    assertEquals(expectedStartupPacket, params.startupPacketParameters().toString(),
        name + " [startup packet]");
    assertEquals(expectedInitialQuery,
        params.initialQuerySql(serverVersionNum, true),
        name + " [initial query]");
  }

  @ParameterizedTest(name = "scs={0}")
  @MethodSource("escapingCases")
  void applicationNameIsEscaped(boolean standardConformingStrings, String expectedInitialQuery)
      throws Exception {
    // assumed version < 9.0 forces application_name onto the post-authentication SET, where it is
    // escaped for the current standard_conforming_strings setting. The value carries a single quote
    // (doubled either way) and a backslash (doubled only when standard_conforming_strings is off),
    // so the two settings produce different SQL.
    InitialSessionParameters params =
        InitialSessionParameters.of(ServerVersion.from(null), "a'\\b");

    assertEquals(expectedInitialQuery,
        params.initialQuerySql(ServerVersion.v14.getVersionNum(), standardConformingStrings));
  }

  static Stream<Arguments> escapingCases() {
    return Stream.of(
        arguments(true, "SET application_name = 'a''\\b'"),
        arguments(false, "SET application_name = 'a''\\\\b'"));
  }
}
