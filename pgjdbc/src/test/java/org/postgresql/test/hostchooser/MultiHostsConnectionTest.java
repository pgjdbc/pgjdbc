package org.postgresql.test.hostchooser;

import static java.lang.Integer.parseInt;
import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.postgresql.hostchooser.HostRequirement.any;
import static org.postgresql.hostchooser.HostRequirement.master;
import static org.postgresql.hostchooser.HostRequirement.preferSlave;
import static org.postgresql.hostchooser.HostRequirement.slave;
import static org.postgresql.hostchooser.HostStatus.ConnectFail;
import static org.postgresql.hostchooser.HostStatus.Slave;
import static org.postgresql.test.TestUtil.closeDB;

import org.postgresql.hostchooser.GlobalHostStatusTracker;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.test.TestUtil;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import junit.framework.TestCase;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public class MultiHostsConnectionTest extends TestCase {

  static final String user = TestUtil.getUser();
  static final String password = TestUtil.getPassword();
  static final String master1 = TestUtil.getServer() + ":" + TestUtil.getPort();
  static final String slave1 =
      MultiHostTestSuite.getSlaveServer() + ":" + MultiHostTestSuite.getSlavePort();
  static final String fake1 = "127.127.217.217:1";
  static String masterIp;
  static String slaveIp;
  static String fakeIp = fake1;

  static Connection con;
  private static Map<HostSpec, Object> hostStatusMap;

  static {
    Field field;
    try {
      field = GlobalHostStatusTracker.class.getDeclaredField("hostStatusMap");
      field.setAccessible(true);
      hostStatusMap = (Map<HostSpec, Object>) field.get(null);

      con = TestUtil.openDB();
      masterIp = getRemoteHostSpec();
      closeDB(con);

      con = MultiHostTestSuite.openSlaveDB();
      slaveIp = getRemoteHostSpec();
      closeDB(con);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static Connection getConnection(HostRequirement hostType, String... targets)
      throws SQLException {
    return getConnection(hostType, true, targets);
  }

  private static HostSpec hostSpec(String host) {
    int split = host.indexOf(':');
    return new HostSpec(host.substring(0, split), parseInt(host.substring(split + 1)));
  }

  private static Connection getConnection(HostRequirement hostType, boolean reset,
      String... targets) throws SQLException {
    return getConnection(hostType, reset, false, targets);
  }

  private static Connection getConnection(HostRequirement hostType, boolean reset, boolean lb,
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
    sb.append("/test");

    return con = DriverManager.getConnection(sb.toString(), props);
  }

  private static void assertRemote(String expectedHost) throws SQLException {
    assertEquals(expectedHost, getRemoteHostSpec());
  }

  private static String getRemoteHostSpec() throws SQLException {
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

  private static void assertGlobalState(String host, String status) {
    HostSpec spec = hostSpec(host);
    if (status == null) {
      assertNull(hostStatusMap.get(spec));
    } else {
      assertEquals(host + "=" + status, hostStatusMap.get(spec).toString());
    }
  }

  private static void resetGlobalState() {
    hostStatusMap.clear();
  }

  public static void testConnectToAny() throws SQLException {
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

  public static void testConnectToMaster() throws SQLException {
    getConnection(master, true, fake1, master1, slave1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(master1, "Master");
    assertGlobalState(slave1, null);

    getConnection(master, false, fake1, slave1, master1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(master1, "Master");
    assertGlobalState(slave1, "Slave");
  }

  public static void testConnectToSlave() throws SQLException {
    getConnection(slave, true, fake1, slave1, master1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, null);

    getConnection(slave, false, fake1, master1, slave1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, "Master");
  }

  public static void testConnectToSlaveFirst() throws SQLException {
    getConnection(preferSlave, true, fake1, slave1, master1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, null);

    getConnection(preferSlave, false, fake1, master1, slave1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, "Master");

    getConnection(preferSlave, false, fake1, master1, slave1);
    assertRemote(slaveIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, "Slave");
    assertGlobalState(master1, "Master");
  }

  public static void testFailedConnection() throws SQLException {
    try {
      getConnection(any, true, fake1);
      fail();
    } catch (PSQLException ex) {
    }
  }

  public static void testLoadBalancing() throws SQLException {
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

  public static void testHostRechecks() throws SQLException, InterruptedException {
    getConnection(master, true, fake1, master1, slave1);
    assertRemote(masterIp);
    assertGlobalState(fake1, "ConnectFail");
    assertGlobalState(slave1, null);

    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), ConnectFail);
    assertGlobalState(master1, "ConnectFail");

    try {
      getConnection(master, false, fake1, slave1, master1);
      fail();
    } catch (SQLException ex) {
    }

    SECONDS.sleep(3);

    getConnection(master, false, slave1, fake1, master1);
    assertRemote(masterIp);
  }

  public static void testNoGoodHostsRechecksEverything() throws SQLException, InterruptedException {
    GlobalHostStatusTracker.reportHostStatus(hostSpec(master1), Slave);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(slave1), Slave);
    GlobalHostStatusTracker.reportHostStatus(hostSpec(fake1), Slave);

    getConnection(master, false, slave1, fake1, master1);
    assertRemote(masterIp);
  }
}