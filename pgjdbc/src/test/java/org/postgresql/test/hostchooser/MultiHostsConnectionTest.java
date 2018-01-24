/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser;

import static java.lang.Integer.parseInt;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;
import static org.postgresql.hostchooser.HostRequirement.any;
import static org.postgresql.hostchooser.HostRequirement.master;
import static org.postgresql.hostchooser.HostRequirement.preferSecondary;
import static org.postgresql.hostchooser.HostRequirement.secondary;
import static org.postgresql.hostchooser.HostStatus.Master;
import static org.postgresql.hostchooser.HostStatus.Secondary;
import static org.postgresql.test.TestUtil.closeDB;

import org.postgresql.hostchooser.GlobalHostStatusTracker;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.test.TestUtil;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class MultiHostsConnectionTest {

  private static final String user = TestUtil.getUser();
  private static final String password = TestUtil.getPassword();
  private static final String master1 = TestUtil.getServer() + ":" + TestUtil.getPort();
  private static final String secondary1 = getSecondaryServer() + ":" + getSecondaryPort();
  private static final String secondary2 = getSecondaryServer2() + ":" + getSecondaryPort2();
  private static final String fake1 = "127.127.217.217:1";

  private String masterIp;
  private String secondaryIP;
  private String secondaryIP2;
  private Connection con;
  private Map<HostSpec, Object> hostStatusMap;

  @BeforeClass
  public static void setUpClass() {
    assumeTrue(isReplicationInstanceAvailable());
  }

  @Before
  public void setUp() throws Exception {
    Field field = GlobalHostStatusTracker.class.getDeclaredField("hostStatusMap");
    field.setAccessible(true);
    hostStatusMap = (Map<HostSpec, Object>) field.get(null);

    con = TestUtil.openDB();
    masterIp = getRemoteHostSpec();
    closeDB(con);

    con = openSecondaryDB();
    secondaryIP = getRemoteHostSpec();
    closeDB(con);

    con = openSecondaryDB2();
    secondaryIP2 = getRemoteHostSpec();
    closeDB(con);
  }

  private static boolean isReplicationInstanceAvailable() {
    try {
      Connection connection = openSecondaryDB();
      closeDB(connection);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static Connection openSecondaryDB() throws Exception {
    TestUtil.initDriver();

    Properties props = userAndPassword();

    return DriverManager.getConnection(TestUtil.getURL(getSecondaryServer(), getSecondaryPort()), props);
  }

  private static Properties userAndPassword() {
    Properties props = new Properties();

    props.setProperty("user", TestUtil.getUser());
    props.setProperty("password", TestUtil.getPassword());
    return props;
  }

  private static String getSecondaryServer() {
    return System.getProperty("secondaryServer", TestUtil.getServer());
  }

  private static int getSecondaryPort() {
    return Integer.parseInt(System.getProperty("secondaryPort", String.valueOf(TestUtil.getPort() + 1)));
  }

  private static Connection openSecondaryDB2() throws Exception {
    TestUtil.initDriver();

    Properties props = userAndPassword();
    return DriverManager.getConnection(TestUtil.getURL(getSecondaryServer2(), getSecondaryPort2()), props);
  }

  private static String getSecondaryServer2() {
    return System.getProperty("secondaryServer2", TestUtil.getServer());
  }

  private static int getSecondaryPort2() {
    return Integer.parseInt(System.getProperty("secondaryPort2", String.valueOf(TestUtil.getPort() + 2)));
  }

  private Connection getConnection(HostRequirement hostType, String... targets)
      throws SQLException {
    return getConnection(hostType, true, targets);
  }

  private static HostSpec hostSpec(String host) {
    int split = host.indexOf(':');
    return new HostSpec(host.substring(0, split), parseInt(host.substring(split + 1)));
  }

  private Connection getConnection(HostRequirement hostType, boolean reset,
      String... targets) throws SQLException {
    return getConnection(hostType, reset, false, targets);
  }

  private Connection getConnection(HostRequirement hostType, boolean reset, boolean lb,
      String... targets) throws SQLException {
    TestUtil.closeDB(con);

    if (reset) {
      resetGlobalState();
    }

    Properties props = new Properties();
    props.setProperty("user", user);
    props.setProperty("password", password);
    props.setProperty("targetServerType", hostType.name());
    props.setProperty("hostRecheckSeconds", "2");
    if (lb) {
      props.setProperty("loadBalanceHosts", "true");
    }

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

  private String getRemoteHostSpec() throws SQLException {
    ResultSet rs = con.createStatement()
        .executeQuery("select inet_server_addr() || ':' || inet_server_port()");
    rs.next();
    return rs.getString(1);
  }

  public static boolean isMaster(Connection con) throws SQLException {
    ResultSet rs = con.createStatement().executeQuery("show transaction_read_only");
    rs.next();
    return "off".equals(rs.getString(1));
  }

  private void assertGlobalState(String host, String status) {
    HostSpec spec = hostSpec(host);
    if (status == null) {
      assertNull(hostStatusMap.get(spec));
    } else {
      assertEquals(host + "=" + status, hostStatusMap.get(spec).toString());
    }
  }

  private void resetGlobalState() {
    hostStatusMap.clear();
  }

  @Test
  public void testConnectToAny() throws SQLException {
    getConnection(any, fake1, master1);
    assertRemote(masterIp);
    assertGlobalState(master1, "ConnectOK");
    assertGlobalState(fake1, "ConnectFail");

    getConnection(any, fake1, secondary1);
    assertRemote(secondaryIP);
    assertGlobalState(secondary1, "ConnectOK");

    getConnection(any, fake1, master1);
    assertRemote(masterIp);
    assertGlobalState(master1, "ConnectOK");
    assertGlobalState(fake1, "ConnectFail");
  }

  @Test
  public void testConnectToMaster() throws SQLException {
    getConnection(master, true, fake1, master1, secondary1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(master1, "Master");
    assertGlobalState(secondary1, null);

    getConnection(master, false, fake1, secondary1, master1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail"); // cached
    assertGlobalState(master1, "Master"); // connected to master
    assertGlobalState(secondary1, "Secondary"); // was unknown, so tried to connect in order
  }

  @Test
  public void testConnectToSecondary() throws SQLException {
    getConnection(secondary, true, fake1, secondary1, master1);
    assertRemote(secondaryIP);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(secondary1, "Secondary");
    assertGlobalState(master1, null);

    getConnection(secondary, false, fake1, master1, secondary1);
    assertRemote(secondaryIP);
    assertGlobalState(fake1, "ConnectFail"); // cached
    assertGlobalState(secondary1, "Secondary"); // connected
    assertGlobalState(master1, "Master"); // tried as it was unknown
  }

  @Test
  public void testConnectToSecondaryFirst() throws SQLException {
    getConnection(preferSecondary, true, fake1, secondary1, master1);
    assertRemote(secondaryIP);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(secondary1, "Secondary");
    assertGlobalState(master1, null);

    getConnection(secondary, false, fake1, master1, secondary1);
    assertRemote(secondaryIP);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(secondary1, "Secondary");
    assertGlobalState(master1, "Master"); // tried as it was unknown

    getConnection(preferSecondary, true, fake1, master1, secondary1);
    assertRemote(secondaryIP);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(secondary1, "Secondary");
    assertGlobalState(master1, "Master");
  }

  @Test
  public void testFailedConnection() throws SQLException {
    try {
      getConnection(any, true, fake1);
      fail();
    } catch (PSQLException ex) {
    }
  }

  @Test
  public void testLoadBalancing() throws SQLException {
    Set<String> connectedHosts = new HashSet<String>();
    boolean fake1FoundTried = false;
    for (int i = 0; i < 20; ++i) {
      getConnection(any, true, true, fake1, master1, secondary1);
      connectedHosts.add(getRemoteHostSpec());
      fake1FoundTried |= hostStatusMap.containsKey(hostSpec(fake1));
      if (connectedHosts.size() == 2 && fake1FoundTried) {
        break;
      }
    }
    assertEquals("Never connected to all hosts", new HashSet<String>(asList(masterIp, secondaryIP)),
        connectedHosts);
    assertTrue("Never tried to connect to fake node", fake1FoundTried);
  }

  @Test
  public void testLoadBalancing_preferSecondary() throws SQLException {
    Set<String> connectedHosts = new HashSet<String>();
    Set<HostSpec> tryConnectedHosts = new HashSet<HostSpec>();
    for (int i = 0; i < 20; ++i) {
      getConnection(preferSecondary, true, true, fake1, master1, secondary1, secondary2);
      connectedHosts.add(getRemoteHostSpec());
      tryConnectedHosts.addAll(hostStatusMap.keySet());
      if (tryConnectedHosts.size() == 4) {
        break;
      }
    }
    assertEquals("Never connected to all secondary hosts", new HashSet<String>(asList(secondaryIP, secondaryIP2)),
        connectedHosts);
    assertEquals("Never tried to connect to fake node",4, tryConnectedHosts.size());

    getConnection(preferSecondary, false, true, fake1, master1, secondary1);
    assertRemote(secondaryIP);
    connectedHosts.clear();
    for (int i = 0; i < 20; ++i) {
      getConnection(preferSecondary, false, true, fake1, master1, secondary1, secondary2);
      connectedHosts.add(getRemoteHostSpec());
      if (connectedHosts.size() == 2) {
        break;
      }
    }
    assertEquals("Never connected to all secondary hosts", new HashSet<String>(asList(secondaryIP, secondaryIP2)),
        connectedHosts);

    // connect to master when there's no secondary
    getConnection(preferSecondary, true, true, fake1, master1);
    assertRemote(masterIp);

    getConnection(preferSecondary, false, true, fake1, master1);
    assertRemote(masterIp);
  }

  @Test
  public void testLoadBalancing_secondary() throws SQLException {
    Set<String> connectedHosts = new HashSet<String>();
    Set<HostSpec> tryConnectedHosts = new HashSet<HostSpec>();
    for (int i = 0; i < 20; ++i) {
      getConnection(secondary, true, true, fake1, master1, secondary1, secondary2);
      connectedHosts.add(getRemoteHostSpec());
      tryConnectedHosts.addAll(hostStatusMap.keySet());
      if (tryConnectedHosts.size() == 4) {
        break;
      }
    }
    assertEquals("Did not attempt to connect to all salve hosts", new HashSet<String>(asList(secondaryIP, secondaryIP2)),
        connectedHosts);
    assertEquals("Did not attempt to connect to master and fake node", 4, tryConnectedHosts.size());

    getConnection(preferSecondary, false, true, fake1, master1, secondary1);
    assertRemote(secondaryIP);
    connectedHosts.clear();
    for (int i = 0; i < 20; ++i) {
      getConnection(secondary, false, true, fake1, master1, secondary1, secondary2);
      connectedHosts.add(getRemoteHostSpec());
      if (connectedHosts.size() == 2) {
        break;
      }
    }
    assertEquals("Did not connect to all secondary hosts", new HashSet<String>(asList(secondaryIP, secondaryIP2)),
        connectedHosts);
  }

  @Test
  public void testHostRechecks() throws SQLException, InterruptedException {
    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), Secondary);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(secondary1), Master);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(fake1), Secondary);

    try {
      getConnection(master, false, fake1, secondary1, master1);
      fail();
    } catch (SQLException ex) {
    }

    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), Secondary);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(secondary1), Master);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(fake1), Secondary);

    SECONDS.sleep(3);

    getConnection(master, false, secondary1, fake1, master1);
    assertRemote(masterIp);
  }

  @Test
  public void testNoGoodHostsRechecksEverything() throws SQLException, InterruptedException {
    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), Secondary);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(secondary1), Secondary);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(fake1), Secondary);

    getConnection(master, false, secondary1, fake1, master1);
    assertRemote(masterIp);
  }
}
