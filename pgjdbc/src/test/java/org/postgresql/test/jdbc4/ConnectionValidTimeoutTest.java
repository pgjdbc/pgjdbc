/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.SslMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.ConnectionBreaker;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
@HaveMinimalServerVersion("9.4")
public class ConnectionValidTimeoutTest {

  @Rule
  public Timeout timeout = new Timeout(30, TimeUnit.SECONDS);

  private Connection connection;

  private ConnectionBreaker connectionBreaker;

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

  @Before
  public void setUp() throws Exception {
    connectionBreaker = new ConnectionBreaker(TestUtil.getServer(), TestUtil.getPort());
    connectionBreaker.acceptAsyncConnection();

    final Properties shadowProperties = new Properties();
    shadowProperties.setProperty(TestUtil.SERVER_HOST_PORT_PROP,
        String.format("%s:%s", "localhost", connectionBreaker.getServerPort()));

    // closing an ssl socket can require one more read attempt, which effectively doubles the waiting time
    // so we disable ssl for this test to get more predictable results
    PGProperty.SSL_MODE.set(shadowProperties, SslMode.DISABLE.value);

    connection = TestUtil.openDB(shadowProperties);
  }

  @After
  public void tearDown() throws Exception {
    connectionBreaker.close();
    connection.close();
  }

  @Test
  public void testIsValidRespectsSmallerTimeout() throws SQLException {
    connection.setNetworkTimeout(null, networkTimeoutMillis);
    connectionBreaker.breakConnection();

    long start = System.nanoTime();
    boolean result = connection.isValid(validationTimeoutSeconds);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

    assertFalse("Broken connection should not be valid", result);

    assertTrue(String.format(
            "Connection validation should not take longer than %d ms"
                + " when network timeout is %d ms and validation timeout is %d s"
                + " (actual result: %d ms)",
            expectedMaxValidationTimeMillis,
            networkTimeoutMillis,
            validationTimeoutSeconds,
            elapsedMillis
        ),
        elapsedMillis <= expectedMaxValidationTimeMillis
    );
  }
}
