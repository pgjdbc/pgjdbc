/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.HostSpec;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.initialization.qual.Initialized;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps track of HostSpec targets in a global map.
 */
public class GlobalHostStatusTracker {
  private static final Map<HostSpec, HostSpecStatus> hostStatusMap =
      new HashMap<>();
  private static final ResourceLock lock = new ResourceLock();
  private static boolean changed;
  private static Map<HostSpec, HostStatusInfo> cachedReadOnlyMap = Collections.emptyMap();

  /**
   * Store the actual observed host status.
   *
   * @param hostSpec The host whose status is known.
   * @param hostStatus Latest known status for the host.
   */
  public static void reportHostStatus(HostSpec hostSpec, HostStatus hostStatus) {
    long now = System.nanoTime() / 1000000;
    try (ResourceLock ignore = lock.obtain()) {
      HostSpecStatus hostSpecStatus = hostStatusMap.get(hostSpec);
      if (hostSpecStatus == null) {
        hostSpecStatus = new HostSpecStatus(hostSpec);
        hostStatusMap.put(hostSpec, hostSpecStatus);
      }
      HostStatus previousStatus = hostSpecStatus.status;
      hostSpecStatus.status = hostStatus;
      hostSpecStatus.lastUpdated = now;

      if (previousStatus != hostStatus) {
        changed = true;
      }
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
    List<HostSpec> candidates = new ArrayList<>(hostSpecs.length);
    long latestAllowedUpdate = System.nanoTime() / 1000000 - hostRecheckMillis;
    try (ResourceLock ignore = lock.obtain()) {
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

  /**
   * Returns a "read-only" card (host -> HostStatus).
   * If the card has not been changed since the last time, the previous cache will be returned.
   * If it has changed, create a new unmodifiableMap and reset the changed flag.
   */
  public static Map<HostSpec, HostStatusInfo> getHostStatusMap() {
    try (ResourceLock ignore = lock.obtain()) {
      if (changed) {
        Map<HostSpec, HostStatusInfo> tempMap = new HashMap<>();
        for (Map.Entry<HostSpec, HostSpecStatus> e : hostStatusMap.entrySet()) {
          HostStatus status = e.getValue().status;
          long lastUpdated = e.getValue().lastUpdated;
          if (status == null) {
            status = HostStatus.ConnectFail;
          }
          tempMap.put(e.getKey(), new HostStatusInfo(status, lastUpdated));
        }
        cachedReadOnlyMap = Collections.unmodifiableMap(tempMap);
        changed = false;
      }
      return cachedReadOnlyMap;
    }
  }

  static class HostSpecStatus {
    final HostSpec host;
    @Nullable HostStatus status;
    long lastUpdated;

    HostSpecStatus(HostSpec host) {
      this.host = host;
    }

    @Override
    public String toString() {
      return host.toString() + '=' + status;
    }
  }

  public static class HostStatusInfo {
    private final @NonNull HostStatus status;
    private final long lastUpdated;

    public HostStatusInfo(HostStatus status, long lastUpdated) {
      this.status = status;
      this.lastUpdated = lastUpdated;
    }

    public HostStatus getStatus() {
      return status;
    }

    public long getLastUpdated() {
      return lastUpdated;
    }
  }
}
