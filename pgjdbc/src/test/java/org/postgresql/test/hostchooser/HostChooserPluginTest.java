/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.hostchooser;

import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.postgresql.test.TestUtil.closeDB;

import org.postgresql.PGProperty;
import org.postgresql.hostchooser.CandidateHost;
import org.postgresql.hostchooser.HostChooser;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.test.TestUtil;
import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Properties;

/**
 * This test will check the behaviour of the HostChooser interface. The test will use a custom
 * HostChooser plugin,
 * `PreferredHostChooser` to test the HostChooser Interface behaviour.
 */
class HostChooserPluginTest {

  private static final String user = TestUtil.getUser();
  private static final String password = TestUtil.getPassword();
  private static final String host1 = TestUtil.getServer();
  private static final int port1 = TestUtil.getPort();
  private static final String host2 = getSecondaryServer();
  private static final int port2 = getSecondaryPort();

  private String host1Ip;
  private String host2Ip;
  private Connection con;

  /**
   * Run once before the tests to verify if the database is up and running.
   */
  @BeforeAll
  static void setUpClass() {
    assumeTrue(isReplicationInstanceAvailable());
  }

  /**
   * Run once before each test to setUp `host1Ip` and `host2Ip`.
   */
  @BeforeEach
  void setUp() throws Exception {

    con = TestUtil.openDB();
    this.host1Ip = getHostSpec();
    closeDB(con);

    con = openSecondaryDB();
    this.host2Ip = getHostSpec();
    closeDB(con);
  }

  /**
   * PreferredHostChooser implements the HostChooser interface,
   * returns `host1` as the perpreferred host for the first few connections.
   */
  public static class PreferredHostChooser implements HostChooser {

    String url;
    Properties props;
    HostRequirement targetServerType;
    int host1ConnectionCount = 0;

    /**
     * Returns `host1` as the preferred host for the first `host1ConnectionCount` connections,
     * and `host2` as the preferred host for the rest connections.
     *
     * @return connection hosts in preferred order.
     */
    @Override
    public Iterator<CandidateHost> iterator() {
      ArrayList<CandidateHost> host = new ArrayList<CandidateHost>();
      if (this.host1ConnectionCount > 0) {
        host.add(new CandidateHost(new HostSpec(host1, port1), this.targetServerType));
        host.add(new CandidateHost(new HostSpec(host2, port2), this.targetServerType));
      } else {
        host.add(new CandidateHost(new HostSpec(host2, port2), this.targetServerType));
        host.add(new CandidateHost(new HostSpec(host1, port1), this.targetServerType));
      }
      Iterator<CandidateHost> it = host.iterator();
      return it;
    }

    /**
     * Initialize and setup the custom host chooser
     */
    @Override
    public void init(String url, Properties info, HostRequirement targetServerType) {
      this.url = url;
      this.props = info;
      this.targetServerType = targetServerType;
      if (url.contains("hostChooserImplProperties")) {
        String[] urlParts = url.split("host1ConnectionCount=");
        this.host1ConnectionCount = Integer.parseInt(urlParts[1]);
      } else if (info.containsKey("hostChooserImplProperties")) {
        this.host1ConnectionCount = Integer
            .parseInt((info.getProperty("hostChooserImplProperties")).split("host1ConnectionCount"
                + "=")[1]);
      }
    }

    @Override
    public void registerSuccess(String host) {
      if (host == host1) {
        this.host1ConnectionCount -= 1;
      }
    }

    @Override
    public void registerFailure(String host, Exception ex) {
      // Does Nothing for this test implementation
    }

    @Override
    public void registerDisconnect(String host) {
      // Does Nothing for this test implementation
    }

    @Override
    public IsValidResponse isValid(String host) {
      return IsValidResponse.VALID;
    }

    @Override
    public boolean isInbuilt() {
      return false;
    }
  }

  /**
   * Test if the database is up and running.
   */
  private static boolean isReplicationInstanceAvailable() {
    try {
      Connection connection = openSecondaryDB();
      closeDB(connection);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /**
   * Test connectivity to the second server.
   *
   * @return connection to the second server.
   */
  private static Connection openSecondaryDB() throws Exception {
    TestUtil.initDriver();

    Properties props = userAndPassword();

    return DriverManager.getConnection(TestUtil.getURL(getSecondaryServer(), getSecondaryPort()),
        props);
  }

  /**
   * Add username and password to the properties.
   *
   * @return Properties props.
   */
  private static Properties userAndPassword() {
    Properties props = new Properties();

    PGProperty.USER.set(props, TestUtil.getUser());
    PGProperty.PASSWORD.set(props, TestUtil.getPassword());
    return props;
  }

  /**
   * Get secondary server IP.
   *
   * @return secondary server IP
   */
  private static String getSecondaryServer() {
    return System.getProperty("secondaryServer1", TestUtil.getServer());
  }

  /**
   * Get secondary server PORT.
   *
   * @return secondary server PORT.
   */
  private static int getSecondaryPort() {
    return Integer.parseInt(System.getProperty("secondaryPort1",
        String.valueOf(TestUtil.getPort() + 1)));
  }

  /**
   * assert whether the current connection's inet_server_addr() matches with
   * expectedHost.
   *
   * @param expectedHost to which the connection is expected.
   */
  private void assertHost(String expectedHost) throws SQLException {
    assertEquals(expectedHost, getHostSpec());
  }

  /**
   * get the inet_server_addr() from the current connection `con`.
   *
   * @return inet_server_addr() of the current connection.
   */
  private String getHostSpec() throws SQLException {
    ResultSet rs = con.createStatement()
        .executeQuery("select inet_server_addr() || ':' || inet_server_port()");
    rs.next();
    return rs.getString(1);
  }

  /**
   * Test PreferredHostChooser (implementation of the HostChooser interface), creates 20 connections,
   * and verify whether the inet_server_addr() on each connection is as per expectation.
   */
  @Test
  void testHostChooserPlugin() throws SQLException {

    Properties props = new Properties();
    props.setProperty(PGProperty.HOST_CHOOSER_IMPL.getName(),
        PreferredHostChooser.class.getName());
    props.setProperty(PGProperty.HOST_CHOOSER_IMPL_PROPERTIES.getName(), "host1ConnectionCount=5");
    PGProperty.USER.set(props, user);
    PGProperty.PASSWORD.set(props, password);

    StringBuilder sb = new StringBuilder();
    sb.append("jdbc:postgresql://");
    sb.append(host1);
    sb.append(":");
    sb.append(port1);
    sb.append("/");
    sb.append(TestUtil.getDatabase());

    for (int i = 0; i < 20; i++) {
      con = DriverManager.getConnection(sb.toString(), props);
      if (i < 5) {
        assertHost(host1Ip);
      } else {
        assertHost(host2Ip);
      }
    }

  }

}
