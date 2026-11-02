/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.RecordSleepAndTimeoutSocketFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.SocketTimeoutException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

class ConnectTimeoutTest {
  private static final int CONNECT_TIMEOUT = 1;

  @BeforeEach
  void setUp() throws Exception {
    TestUtil.initDriver();
    RecordSleepAndTimeoutSocketFactory.TIMEOUTS.clear();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "10.255.255.1:5432",
      "10.255.255.2:5432,10.255.255.3:5432,10.255.255.4:5432"
  })
  void eachHostGetsTheFullConnectTimeout(String hostList) {
    final Properties props = new Properties();
    PGProperty.USER.set(props, TestUtil.getUser());
    PGProperty.PASSWORD.set(props, TestUtil.getPassword());
    PGProperty.CONNECT_TIMEOUT.set(props, CONNECT_TIMEOUT);
    PGProperty.HOST_RECHECK_SECONDS.set(props, 0);
    PGProperty.SOCKET_FACTORY.set(props, RecordSleepAndTimeoutSocketFactory.class.getName());
    PGProperty.SSL_MODE.set(props, "disable");

    SQLException e = assertThrows(SQLException.class,
        () -> DriverManager.getConnection("jdbc:postgresql://" + hostList + "/test", props));

    assertInstanceOf(
        SocketTimeoutException.class,
        e.getCause(),
        () -> "Unexpected " + e + " with cause " + e.getCause());

    final long connectTimeoutMillis = CONNECT_TIMEOUT * 1000;
    final long maxDeviation = connectTimeoutMillis / 10;

    assertAll(
        Arrays.stream(hostList.split(",")).map(host -> () -> {
          final List<Integer> timeouts = RecordSleepAndTimeoutSocketFactory.TIMEOUTS.get(host);
          assertNotNull(timeouts,
              () -> "Connection should be tried for all given hosts " + hostList + ", but "
                  + "socket connection was not tried for " + host + " host");
          assertEquals(1, timeouts.size(),
              () -> "Exactly one connection attempt is expected per each host, but "
                  + host + " was tried " + timeouts.size() + " times");
          final int actualTimeout = timeouts.get(0);
          // check that it was not a default system timeout, an approximate value is used
          assertTrue(connectTimeoutMillis - actualTimeout < maxDeviation,
              () -> "Connection timeout for " + host + " host should be ~"
                  + connectTimeoutMillis + " ms, but was " + actualTimeout
                  + " ms (deviation " + (connectTimeoutMillis - actualTimeout)
                  + " ms exceeds maximum allowed " + maxDeviation + " ms)");
        })
    );
  }
}
