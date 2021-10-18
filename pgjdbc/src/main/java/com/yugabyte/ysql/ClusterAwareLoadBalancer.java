// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package com.yugabyte.ysql;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClusterAwareLoadBalancer {
  protected static final String GET_SERVERS_QUERY = "select * from yb_servers()";
  protected static final Logger LOGGER = Logger.getLogger("org.postgresql.Driver");

  private static volatile ClusterAwareLoadBalancer instance;
  private long lastServerListFetchTime = 0L;
  private volatile ArrayList<String> servers = null;
  Map<String, Integer> hostToNumConnMap = new HashMap<>();
  Set<String> unreachableHosts = new HashSet<>();
  protected Map<String, String> hostPortMap = new HashMap<>();
  protected Map<String, String> hostPortMapPublic = new HashMap<>();

  public static ClusterAwareLoadBalancer instance() {
    return instance;
  }

  public ClusterAwareLoadBalancer() {
  }

  public static ClusterAwareLoadBalancer getInstance() {
    if (instance == null) {
      synchronized (ClusterAwareLoadBalancer.class) {
        if (instance == null) {
          instance = new ClusterAwareLoadBalancer();
        }
      }
    }
    return instance;
  }

  public String getPort(String host) {
    String port = hostPortMap.get(host);
    if (port == null) {
      port = hostPortMapPublic.get(host);
    }
    return port;
  }

  public synchronized String getLeastLoadedServer(List<String> failedHosts) {
    int min = Integer.MAX_VALUE;
    ArrayList<String> minConnectionsHostList = new ArrayList<>();
    for (String h : hostToNumConnMap.keySet()) {
      if (failedHosts.contains(h)) {
        continue;
      }
      int currLoad = hostToNumConnMap.get(h);
      // System.out.println("Current load for host: " + h + " is " + currLoad);
      if (currLoad < min) {
        min = currLoad;
        minConnectionsHostList.clear();
        minConnectionsHostList.add(h);
      } else if (currLoad == min) {
        minConnectionsHostList.add(h);
      }
    }
    // Choose a random from the minimum list
    String chosenHost = null;
    if (minConnectionsHostList.size() > 0) {
      int idx = ThreadLocalRandom.current().nextInt(0, minConnectionsHostList.size());
      chosenHost = minConnectionsHostList.get(idx);
    }
    if (chosenHost != null) {
      updateConnectionMap(chosenHost, 1);
    }
    LOGGER.log(Level.FINE,
        getLoadBalancerType() + ": Host chosen for new connection: " + chosenHost);
    return chosenHost;
  }

  public static int refreshListSeconds = 300;

  public static boolean forceRefresh = false;

  public boolean needsRefresh() {
    if (forceRefresh) {
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": Force Refresh is set to true");
      return true;
    }
    long currentTimeInMillis = System.currentTimeMillis();
    long diff = (currentTimeInMillis - lastServerListFetchTime) / 1000;
    boolean firstTime = servers == null;
    if (firstTime || diff > refreshListSeconds) {
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": "
          + "Needs refresh as list of servers may be stale or being fetched for the first time");
      return true;
    }
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": Refresh not required.");
    return false;
  }

  protected static String columnToUseForHost = null;

  protected ArrayList<String> getCurrentServers(Connection conn) throws SQLException {
    Statement st = conn.createStatement();
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": Executing query: "
        + GET_SERVERS_QUERY + " to fetch list of servers");
    ResultSet rs = st.executeQuery(GET_SERVERS_QUERY);
    ArrayList<String> currentPrivateIps = new ArrayList<>();
    ArrayList<String> currentPublicIps = new ArrayList<>();
    String hostConnectedTo = ((PgConnection) conn).getQueryExecutor().getHostSpec().getHost();
    InetAddress hostConnectedInetAddr;

    Boolean useHostColumn = null;
    boolean isIpv6Addresses = hostConnectedTo.contains(":");
    if (isIpv6Addresses) {
      hostConnectedTo = hostConnectedTo.replace("[", "").replace("]", "");
    }

    try {
      hostConnectedInetAddr = InetAddress.getByName(hostConnectedTo);
    } catch (UnknownHostException e) {
      // This is totally unexpected. As the connection is already created on this host
      throw new PSQLException(GT.tr("Unexpected UnknownHostException for ${0} ", hostConnectedTo),
          PSQLState.UNKNOWN_STATE, e);
    }

    while (rs.next()) {
      String host = rs.getString("host");
      String publicHost = rs.getString("public_ip");
      String port = rs.getString("port");
      hostPortMap.put(host, port);
      hostPortMapPublic.put(publicHost, port);
      currentPrivateIps.add(host);
      currentPublicIps.add(publicHost);
      InetAddress hostInetAddr;
      InetAddress publicHostInetAddr;
      try {
        hostInetAddr = InetAddress.getByName(host);
      } catch (UnknownHostException e) {
        // set the hostInet to null
        hostInetAddr = null;
      }
      try {
        publicHostInetAddr = !publicHost.isEmpty()
            ? InetAddress.getByName(publicHost) : null;
      } catch (UnknownHostException e) {
        // set the publicHostInetAddr to null
        publicHostInetAddr = null;
      }
      if (useHostColumn == null) {
        if (hostConnectedInetAddr.equals(hostInetAddr)) {
          useHostColumn = Boolean.TRUE;
        } else if (hostConnectedInetAddr.equals(publicHostInetAddr)) {
          useHostColumn = Boolean.FALSE;
        }
      }
    }
    return getPrivateOrPublicServers(useHostColumn, currentPrivateIps, currentPublicIps);
  }

  protected ArrayList<String> getPrivateOrPublicServers(
      Boolean useHostColumn, ArrayList<String> privateHosts, ArrayList<String> publicHosts) {
    if (useHostColumn == null) {
      LOGGER.log(Level.WARNING, getLoadBalancerType() + ": Either private or public should have "
          + "matched with one of the servers");
      return null;
    }
    ArrayList<String> currentHosts = useHostColumn ? privateHosts : publicHosts;
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": List of servers got {0}", currentHosts);
    return currentHosts;
  }

  protected String getLoadBalancerType() {
    return "ClusterAwareLoadBalancer";
  }

  public synchronized boolean refresh(Connection conn) throws SQLException {
    if (!needsRefresh()) {
      return true;
    }
    // else clear server list
    long currTime = System.currentTimeMillis();
    servers = getCurrentServers(conn);
    if (servers == null) {
      return false;
    }
    lastServerListFetchTime = currTime;
    unreachableHosts.clear();
    for (String h : servers) {
      if (!hostToNumConnMap.containsKey(h)) {
        hostToNumConnMap.put(h, 0);
      }
    }
    return true;
  }

  public List<String> getServers() {
    return Collections.unmodifiableList(servers);
  }

  public synchronized void updateConnectionMap(String host, int incDec) {
    LOGGER.log(Level.FINE, getLoadBalancerType() + ": updating connection count for {0} by {1}",
        new String[]{host, String.valueOf(incDec)});
    Integer currentCount = hostToNumConnMap.get(host);
    if (currentCount == 0 && incDec < 0) {
      return;
    }
    if (currentCount == null && incDec > 0) {
      hostToNumConnMap.put(host, incDec);
    } else if (currentCount != null) {
      hostToNumConnMap.put(host, currentCount + incDec);
    }
  }

  public Set<String> getUnreachableHosts() {
    return unreachableHosts;
  }

  public synchronized void updateFailedHosts(String chosenHost) {
    unreachableHosts.add(chosenHost);
    hostToNumConnMap.remove(chosenHost);
  }

  protected String loadBalancingNodes() {
    return "all";
  }

  public void setForRefresh() {
    lastServerListFetchTime = 0L;
  }

  public void printHostToConnMap() {
    System.out.println("Current load on " + loadBalancingNodes() + " servers");
    System.out.println("-------------------");
    for (Map.Entry<String, Integer> e : hostToNumConnMap.entrySet()) {
      System.out.println(e.getKey() + " - " + e.getValue());
    }
  }
}
