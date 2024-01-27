/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.DisabledIfServerVersionBelow;
import org.postgresql.test.util.StrangeProxyServer;

import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Properties;

@DisabledIfServerVersionBelow("9.4")
public class ConnectionValidTimeoutTest {

  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
      {500, 1, 600},
      {1500, 1, 1100},
      {0, 1, 1100},
      {500, 0, 600},
    });
  }

  @MethodSource("data")
  @ParameterizedTest(name = "networkTimeoutMillis={0}, validationTimeoutSeconds={1}, expectedMaxValidationTimeMillis={2}")
  @Timeout(30)
  void isValidRespectsSmallerTimeout(int networkTimeoutMillis, int validationTimeoutSeconds, int expectedMaxValidationTimeMillis) throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(), TestUtil.getPort())) {
      final Properties props = new Properties();
      props.setProperty(TestUtil.SERVER_HOST_PORT_PROP, String.format("%s:%s", "localhost", proxyServer.getServerPort()));
      try (Connection conn = TestUtil.openDB(props)) {
        assertTrue(conn.isValid(validationTimeoutSeconds), "Connection through proxy should be valid");

        conn.setNetworkTimeout(null, networkTimeoutMillis);
        assertTrue(conn.isValid(validationTimeoutSeconds), "Connection through proxy should still be valid");

        proxyServer.stopForwardingOlderClients();

        long start = System.currentTimeMillis();
        boolean result = conn.isValid(validationTimeoutSeconds);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(result, "Broken connection should not be valid");

        assertTrue(elapsed <= expectedMaxValidationTimeMillis,
            String.format(
            "Connection validation should not take longer than %d ms"
                + " when network timeout is %d ms and validation timeout is %d s"
                + " (actual result: %d ms)",
            expectedMaxValidationTimeMillis,
            networkTimeoutMillis,
            validationTimeoutSeconds,
            elapsed)
        );
      }
    }
  }
}
