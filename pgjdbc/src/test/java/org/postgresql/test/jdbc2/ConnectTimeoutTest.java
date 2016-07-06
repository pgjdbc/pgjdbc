package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.net.SocketTimeoutException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class ConnectTimeoutTest extends TestCase {
  // The IP below is non-routable (see http://stackoverflow.com/a/904609/1261287)
  private static final String UNREACHABLE_HOST = "10.255.255.1";
  private static final String UNREACHABLE_URL = "jdbc:postgresql://" + UNREACHABLE_HOST + ":5432/test";
  private static final int CONNECT_TIMEOUT = 5;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    TestUtil.initDriver();
  }

  public void testTimeout() {
    final Properties props = new Properties();
    props.setProperty("user", "test");
    props.setProperty("password", "test");
    // with 0 (default value) it hangs for about 60 seconds (platform dependent)
    props.setProperty("connectTimeout", Integer.toString(CONNECT_TIMEOUT));

    final long startTime = System.currentTimeMillis();
    try {
      DriverManager.getConnection(UNREACHABLE_URL, props);
    } catch (SQLException e) {
      assertTrue("Unexpected " + e.toString(),
          e.getCause() instanceof SocketTimeoutException);
      final long interval = System.currentTimeMillis() - startTime;
      final long connectTimeoutMillis = CONNECT_TIMEOUT * 1000;
      final long maxDeviation = connectTimeoutMillis / 10;
      // check that it was not a default system timeout, an approximate value is used
      assertTrue(Math.abs(interval - connectTimeoutMillis) < maxDeviation);
      return;
    }
    fail("SQLException expected");
  }
}
