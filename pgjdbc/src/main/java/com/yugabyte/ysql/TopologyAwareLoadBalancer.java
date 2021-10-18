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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.logging.Level;

public class TopologyAwareLoadBalancer extends ClusterAwareLoadBalancer {
  private final String placements;
  private final Set<CloudPlacement> allowedPlacements;

  public TopologyAwareLoadBalancer(String placementvalues) {
    placements = placementvalues;
    allowedPlacements = new HashSet<>();
    populatePlacementSet();
  }

  protected String loadBalancingNodes() {
    return placements;
  }

  private void populatePlacementSet() {
    String[] placementstrings = placements.split(",");
    for (String pl : placementstrings) {
      String[] placementParts = pl.split("\\.");
      if (placementParts.length != 3) {
        // Log a warning and return.
        LOGGER.log(Level.WARNING, getLoadBalancerType() + ": "
            + "Ignoring malformed topology-key property value: " + pl);
        continue;
      }
      CloudPlacement cp = new CloudPlacement(
          placementParts[0], placementParts[1], placementParts[2]);
      LOGGER.log(Level.FINE, getLoadBalancerType() + ": Adding placement " + cp + " to allowed "
          + "list");
      allowedPlacements.add(cp);
    }
  }

  @Override
  protected ArrayList<String> getCurrentServers(Connection conn) throws SQLException {
    Statement st = conn.createStatement();
    LOGGER.log(Level.FINE,
        getLoadBalancerType() + ": Getting the list of servers in: " + placements);
    ResultSet rs = st.executeQuery(ClusterAwareLoadBalancer.GET_SERVERS_QUERY);
    ArrayList<String> currentPrivateIps = new ArrayList<>();
    ArrayList<String> currentPublicIps = new ArrayList<>();
    String hostConnectedTo = ((PgConnection) conn).getQueryExecutor().getHostSpec().getHost();
    Boolean useHostColumn = null;
    while (rs.next()) {
      String host = rs.getString("host");
      String publicIp = rs.getString("public_ip");
      String cloud = rs.getString("cloud");
      String region = rs.getString("region");
      String zone = rs.getString("zone");
      String port = rs.getString("port");
      hostPortMap.put(host, port);
      hostPortMapPublic.put(publicIp, port);
      if (hostConnectedTo.equals(host)) {
        useHostColumn = Boolean.TRUE;
      } else if (hostConnectedTo.equals(publicIp)) {
        useHostColumn = Boolean.FALSE;
      }
      CloudPlacement cp = new CloudPlacement(cloud, region, zone);
      if (allowedPlacements.contains(cp)) {
        LOGGER.log(Level.FINE,
            getLoadBalancerType() + ": allowedPlacements set: "
                + allowedPlacements + " returned contains true for cp: " + cp);
        currentPrivateIps.add(host);
        currentPublicIps.add(publicIp);
      } else {
        LOGGER.log(Level.FINE,
            getLoadBalancerType() + ": allowedPlacements set: " + allowedPlacements
                + " returned contains false for cp: " + cp);

      }
    }
    return getPrivateOrPublicServers(useHostColumn, currentPrivateIps, currentPublicIps);
  }

  protected String getLoadBalancerType() {
    return "TopologyAwareLoadBalancer";
  }

  static class CloudPlacement {
    private final String cloud;
    private final String region;
    private final String zone;

    CloudPlacement(String cloud, String region, String zone) {
      this.cloud = cloud;
      this.region = region;
      this.zone = zone;
    }

    public int hashCode() {
      return cloud.hashCode() ^ region.hashCode() ^ zone.hashCode();
    }

    public boolean equals(Object other) {
      boolean equal = false;
      LOGGER.log(Level.FINE, "equals called for this: " + this + " and other = " + other);
      if (other instanceof CloudPlacement) {
        CloudPlacement o = (CloudPlacement) other;
        equal = this.cloud.equalsIgnoreCase(o.cloud) &&
            this.region.equalsIgnoreCase(o.region) &&
            this.zone.equalsIgnoreCase(o.zone);
      }
      LOGGER.log(Level.FINE, "equals returning: " + equal);
      return equal;
    }

    public String toString() {
      return "Placement: " + cloud + "." + region + "." + zone;
    }
  }
}
