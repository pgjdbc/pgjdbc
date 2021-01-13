/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;
import org.postgresql.test.util.StrangeProxyServer;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
@HaveMinimalServerVersion("9.4")
public class ConnectionValidTimeoutTest {

  @Rule
  public Timeout timeout = new Timeout(30, TimeUnit.SECONDS);

  @Parameterized.Parameter(0)
  public int networkTimeoutMillis;
  @Parameterized.Parameter(1)
  public int validationTimeoutSeconds;
  @Parameterized.Parameter(2)
  public int expectedMaxValidationTimeMillis;

  @Parameterized.Parameters(name = "networkTimeoutMillis={0}, validationTimeoutSeconds={1}, expectedMaxValidationTimeMillis={2}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
      {500, 1, 600},
      {1500, 1, 1100},
      {0, 1, 1100},
      {500, 0, 600},
    });
  }

  @Test
  public void testIsValidRespectsSmallerTimeout() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(), TestUtil.getPort())) {
      final Properties props = new Properties();
      props.setProperty(TestUtil.SERVER_HOST_PORT_PROP, String.format("%s:%s", "localhost", proxyServer.getServerPort()));
      try (Connection conn = TestUtil.openDB(props)) {
        assertTrue("Connection through proxy should be valid", conn.isValid(validationTimeoutSeconds));

        conn.setNetworkTimeout(null, networkTimeoutMillis);
        assertTrue("Connection through proxy should still be valid", conn.isValid(validationTimeoutSeconds));

        proxyServer.stopForwardingOlderClients();

        long start = System.currentTimeMillis();
        boolean result = conn.isValid(validationTimeoutSeconds);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse("Broken connection should not be valid", result);

        assertTrue(String.format(
            "Connection validation should not take longer than %d ms"
                + " when network timeout is %d ms and validation timeout is %d s"
                + " (actual result: %d ms)",
            expectedMaxValidationTimeMillis,
            networkTimeoutMillis,
            validationTimeoutSeconds,
            elapsed),
            elapsed <= expectedMaxValidationTimeMillis
        );
      }
    }
  }
}
