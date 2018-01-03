/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import static java.lang.System.currentTimeMillis;

import org.postgresql.util.HostSpec;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of HostSpec targets in a global map.
 */
public class GlobalHostStatusTracker {
  private static final Map<HostSpec, HostSpecStatus> hostStatusMap =
      new HashMap<HostSpec, HostSpecStatus>();

  /**
   * Store the actual observed host status.
   *
   * @param hostSpec The host whose status is known.
   * @param hostStatus Latest known status for the host.
   */
  public static void reportHostStatus(HostSpec hostSpec, HostStatus hostStatus) {
    long now = currentTimeMillis();
    synchronized (hostStatusMap) {
      HostSpecStatus hostSpecStatus = hostStatusMap.get(hostSpec);
      if (hostSpecStatus == null) {
        hostSpecStatus = new HostSpecStatus(hostSpec);
        hostStatusMap.put(hostSpec, hostSpecStatus);
      }
      hostSpecStatus.status = hostStatus;
      hostSpecStatus.lastUpdated = now;
    }
  }

  /**
   * Returns a list of candidate hosts that have the required targetServerType.
   *
   * @param hostSpecs The potential list of hosts.
   * @param targetServerType The required target server type.
   * @param hostRecheckMillis How stale information is allowed.
   * @return candidate hosts to connect to.
   */
  static List<HostSpec> getCandidateHosts(HostSpec[] hostSpecs,
      HostRequirement targetServerType, long hostRecheckMillis) {
    List<HostSpec> candidates = new ArrayList<HostSpec>(hostSpecs.length);
    long latestAllowedUpdate = currentTimeMillis() - hostRecheckMillis;
    synchronized (hostStatusMap) {
      for (HostSpec hostSpec : hostSpecs) {
        HostSpecStatus hostInfo = hostStatusMap.get(hostSpec);
        // candidates are nodes we do not know about and the nodes with correct type
        if (hostInfo == null
            || hostInfo.lastUpdated < latestAllowedUpdate
            || targetServerType.allowConnectingTo(hostInfo.status)) {
          candidates.add(hostSpec);
        }
      }
    }
    return candidates;
  }

  static class HostSpecStatus {
    final HostSpec host;
    HostStatus status;
    long lastUpdated;

    HostSpecStatus(HostSpec host) {
      this.host = host;
    }

    @Override
    public String toString() {
      return host.toString() + '=' + status;
    }
  }
}
