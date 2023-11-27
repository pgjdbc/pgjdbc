/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser;

import static java.lang.Integer.parseInt;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.postgresql.hostchooser.HostRequirement.primary;
import static org.postgresql.test.TestUtil.closeDB;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.GlobalHostStatusTracker;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.test.TestUtil;
import org.postgresql.util.HostSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

public class MultiHostsConnectionSingleHostNameTest {
  private static final String user = TestUtil.getUser();
  private static final String password = TestUtil.getPassword();
  private static final String fake1 = "127.127.217.217:" + TestUtil.getPort();

  private String primaryIp;
  private Connection con;
  private Map<HostSpec, Object> hostStatusMap;
  private Map<String, Object> addressCache;

  @Before
  @SuppressWarnings("unchecked")
  public void setUp() throws Exception {
    Field field = GlobalHostStatusTracker.class.getDeclaredField("hostStatusMap");
    field.setAccessible(true);
    hostStatusMap = (Map<HostSpec, Object>) field.get(null);

    con = TestUtil.openDB();
    primaryIp = getRemoteHostSpec();
    closeDB(con);

    String[] parts = primaryIp.split("/")[0].split("\\.");
    byte[] address = new byte[]{
        (byte) Integer.parseInt(parts[0]),
        (byte) Integer.parseInt(parts[1]),
        (byte) Integer.parseInt(parts[2]),
        (byte) Integer.parseInt(parts[3]),
    };

    Field addressCacheField = InetAddress.class.getDeclaredField("cache");
    addressCacheField.setAccessible(true);
    addressCache = (Map<String, Object>) addressCacheField.get(null);
    for (Class<?> declaredClass : InetAddress.class.getDeclaredClasses()) {
      if (declaredClass.getSimpleName().equals("CachedAddresses")) {
        Constructor<?> constructor = declaredClass.getDeclaredConstructor(String.class,
            InetAddress[].class, long.class);
        constructor.setAccessible(true);
        Object cachedTestAddress = constructor.newInstance(
            "test-host",
            new InetAddress[]{
                InetAddress.getByAddress("test-host", new byte[]{127, 127, (byte) 217, (byte) 217}),
                InetAddress.getByAddress("test-host", address),
            },
            10000000
        );
        addressCache.put("test-host", cachedTestAddress);
      }
    }
  }

  @After
  public void tearDown() throws Exception {
    addressCache.remove("test-host");
  }

  @Test
  public void testConnectToAnySingleHostName() throws SQLException {
    getConnection(primary, "test-host");
    assertRemote(primaryIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(primaryIp.split("/")[0] + ":" + TestUtil.getPort(), "Primary");
  }

  private Connection getConnection(HostRequirement hostType, String... targets) throws SQLException {
    TestUtil.closeDB(con);

    Properties props = new Properties();
    PGProperty.USER.set(props, user);
    PGProperty.PASSWORD.set(props, password);
    PGProperty.TARGET_SERVER_TYPE.set(props, hostType.name());
    PGProperty.HOST_RECHECK_SECONDS.set(props, 2);

    StringBuilder sb = new StringBuilder();
    sb.append("jdbc:postgresql://");
    for (String target : targets) {
      sb.append(target).append(',');
    }
    sb.setLength(sb.length() - 1);
    sb.append("/");
    sb.append(TestUtil.getDatabase());

    return con = DriverManager.getConnection(sb.toString(), props);
  }

  private void assertRemote(String expectedHost) throws SQLException {
    assertEquals(expectedHost, getRemoteHostSpec());
  }

  private static HostSpec hostSpec(String host) {
    int split = host.indexOf(':');
    return new HostSpec(host.substring(0, split), parseInt(host.substring(split + 1)));
  }

  private void assertGlobalState(String host, String status) {
    HostSpec spec = hostSpec(host);
    if (status == null) {
      assertNull(hostStatusMap.get(spec));
    } else {
      assertEquals(host + "=" + status, hostStatusMap.get(spec).toString());
    }
  }

  private String getRemoteHostSpec() throws SQLException {
    ResultSet rs = con.createStatement()
        .executeQuery("select inet_server_addr() || ':' || inet_server_port()");
    rs.next();
    return rs.getString(1);
  }
}
