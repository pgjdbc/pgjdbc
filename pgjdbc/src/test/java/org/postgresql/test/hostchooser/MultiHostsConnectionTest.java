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
import static org.postgresql.hostchooser.HostRequirement.any;
import static org.postgresql.hostchooser.HostRequirement.master;
import static org.postgresql.hostchooser.HostRequirement.preferSlave;
import static org.postgresql.hostchooser.HostRequirement.slave;
import static org.postgresql.hostchooser.HostStatus.Master;
import static org.postgresql.hostchooser.HostStatus.Slave;
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
  private static final String slave1 = getSlaveServer() + ":" + getSlavePort();
  private static final String slave2 = getSlaveServer2() + ":" + getSlavePort2();
  private static final String fake1 = "127.127.217.217:1";

  private String masterIp;
  private String slaveIp;
  private String slaveIp2;
  private Connection con;
  private Map<HostSpec, Object> hostStatusMap;

  @BeforeClass
  public static void setUpClass() throws Exception {
    assertTrue(isReplicationInstanceAvailable());
  }

  @Before
  public void setUp() throws Exception {
    Field field = GlobalHostStatusTracker.class.getDeclaredField("hostStatusMap");
    field.setAccessible(true);
    hostStatusMap = (Map<HostSpec, Object>) field.get(null);

    con = TestUtil.openDB();
    masterIp = getRemoteHostSpec();
    closeDB(con);

    con = openSlaveDB();
    slaveIp = getRemoteHostSpec();
    closeDB(con);

    con = openSlaveDB2();
    slaveIp2 = getRemoteHostSpec();
    closeDB(con);
  }

  private static boolean isReplicationInstanceAvailable() {
    try {
      Connection connection = openSlaveDB();
      closeDB(connection);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static Connection openSlaveDB() throws Exception {
    TestUtil.initDriver();

    Properties props = userAndPassword();

    return DriverManager.getConnection(TestUtil.getURL(getSlaveServer(), getSlavePort()), props);
  }

  private static Properties userAndPassword() {
    Properties props = new Properties();

    props.setProperty("user", TestUtil.getUser());
    props.setProperty("password", TestUtil.getPassword());
    return props;
  }

  private static String getSlaveServer() {
    return System.getProperty("slaveServer", TestUtil.getServer());
  }

  private static int getSlavePort() {
    return Integer.parseInt(System.getProperty("slavePort", String.valueOf(TestUtil.getPort() + 1)));
  }

  private static Connection openSlaveDB2() throws Exception {
    TestUtil.initDriver();

    Properties props = userAndPassword();
    return DriverManager.getConnection(TestUtil.getURL(getSlaveServer2(), getSlavePort2()), props);
  }

  private static String getSlaveServer2() {
    return System.getProperty("slaveServer2", TestUtil.getServer());
  }

  private static int getSlavePort2() {
    return Integer.parseInt(System.getProperty("slavePort2", String.valueOf(TestUtil.getPort() + 2)));
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

    getConnection(any, fake1, slave1);
    assertRemote(slaveIp);
    assertGlobalState(slave1, "ConnectOK");

    getConnection(any, fake1, master1);
    assertRemote(masterIp);
    assertGlobalState(master1, "ConnectOK");
    assertGlobalState(fake1, "ConnectFail");
  }

  @Test
  public void testConnectToMaster() throws SQLException {
    getConnection(master, true, fake1, master1, slave1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(master1, "Master");
    assertGlobalState(slave1, null);

    getConnection(master, false, fake1, slave1, master1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail"); // cached
    assertGlobalState(master1, "Master"); // connected to master
    assertGlobalState(slave1, "Slave"); // was unknown, so tried to connect in order
  }

  @Test
  public void testConnectToSlave() throws SQLException {
    getConnection(slave, true, fake1, slave1, master1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, null);

    getConnection(slave, false, fake1, master1, slave1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail"); // cached
    assertGlobalState(slave1, "Slave"); // connected
    assertGlobalState(master1, "Master"); // tried as it was unknown
  }

  @Test
  public void testConnectToSlaveFirst() throws SQLException {
    getConnection(preferSlave, true, fake1, slave1, master1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, null);

    getConnection(slave, false, fake1, master1, slave1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, "Master"); // tried as it was unknown

    getConnection(preferSlave, true, fake1, master1, slave1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
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
      getConnection(any, true, true, fake1, master1, slave1);
      connectedHosts.add(getRemoteHostSpec());
      fake1FoundTried |= hostStatusMap.containsKey(hostSpec(fake1));
      if (connectedHosts.size() == 2 && fake1FoundTried) {
        break;
      }
    }
    assertEquals("Never connected to all hosts", new HashSet<String>(asList(masterIp, slaveIp)),
        connectedHosts);
    assertTrue("Never tried to connect to fake node", fake1FoundTried);
  }

  @Test
  public void testLoadBalancing_preferSlave() throws SQLException {
    Set<String> connectedHosts = new HashSet<String>();
    Set<HostSpec> tryConnectedHosts = new HashSet<HostSpec>();
    for (int i = 0; i < 20; ++i) {
      getConnection(preferSlave, true, true, fake1, master1, slave1, slave2);
      connectedHosts.add(getRemoteHostSpec());
      tryConnectedHosts.addAll(hostStatusMap.keySet());
      if (tryConnectedHosts.size() == 4) {
        break;
      }
    }
    assertEquals("Never connected to all slave hosts", new HashSet<String>(asList(slaveIp, slaveIp2)),
        connectedHosts);
    assertEquals("Never tried to connect to fake node",4, tryConnectedHosts.size());

    getConnection(preferSlave, false, true, fake1, master1, slave1);
    assertRemote(slaveIp);
    connectedHosts.clear();
    for (int i = 0; i < 20; ++i) {
      getConnection(preferSlave, false, true, fake1, master1, slave1, slave2);
      connectedHosts.add(getRemoteHostSpec());
      if (connectedHosts.size() == 2) {
        break;
      }
    }
    assertEquals("Never connected to all slave hosts", new HashSet<String>(asList(slaveIp, slaveIp2)),
        connectedHosts);

    // connect to master when there's no slave
    getConnection(preferSlave, true, true, fake1, master1);
    assertRemote(masterIp);

    getConnection(preferSlave, false, true, fake1, master1);
    assertRemote(masterIp);
  }

  @Test
  public void testLoadBalancing_slave() throws SQLException {
    Set<String> connectedHosts = new HashSet<String>();
    Set<HostSpec> tryConnectedHosts = new HashSet<HostSpec>();
    for (int i = 0; i < 20; ++i) {
      getConnection(slave, true, true, fake1, master1, slave1, slave2);
      connectedHosts.add(getRemoteHostSpec());
      tryConnectedHosts.addAll(hostStatusMap.keySet());
      if (tryConnectedHosts.size() == 4) {
        break;
      }
    }
    assertEquals("Never connected to all salve hosts", new HashSet<String>(asList(slaveIp, slaveIp2)),
        connectedHosts);
    assertEquals("Never tried to connect to maste and fake node", 4, tryConnectedHosts.size());

    getConnection(preferSlave, false, true, fake1, master1, slave1);
    assertRemote(slaveIp);
    connectedHosts.clear();
    for (int i = 0; i < 20; ++i) {
      getConnection(slave, false, true, fake1, master1, slave1, slave2);
      connectedHosts.add(getRemoteHostSpec());
      if (connectedHosts.size() == 2) {
        break;
      }
    }
    assertEquals("Never connected to all slave hosts", new HashSet<String>(asList(slaveIp, slaveIp2)),
        connectedHosts);
  }

  @Test
  public void testHostRechecks() throws SQLException, InterruptedException {
    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), Slave);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(slave1), Master);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(fake1), Slave);

    try {
      getConnection(master, false, fake1, slave1, master1);
      fail();
    } catch (SQLException ex) {
    }

    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), Slave);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(slave1), Master);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(fake1), Slave);

    SECONDS.sleep(3);

    getConnection(master, false, slave1, fake1, master1);
    assertRemote(masterIp);
  }

  @Test
  public void testNoGoodHostsRechecksEverything() throws SQLException, InterruptedException {
    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), Slave);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(slave1), Slave);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(fake1), Slave);

    getConnection(master, false, slave1, fake1, master1);
    assertRemote(masterIp);
  }
}
