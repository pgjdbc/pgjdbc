/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import org.postgresql.core.BaseConnection;
import org.postgresql.util.HostSpec;

import java.util.Properties;
import java.util.Set;

/**
 * Interface for monitor services. This class implements ways to start and stop monitoring
 * servers when connections are created.
 */
public interface IMonitorService {
  MonitorConnectionContext startMonitoring(
      BaseConnection connectionToAbort,
      Set<String> nodeKeys,
      HostSpec hostSpec,
      Properties props,
      int failureDetectionTimeMillis,
      int failureDetectionIntervalMillis,
      int failureDetectionCount);

  /**
   * Stop monitoring for a connection represented by the given
   * {@link MonitorConnectionContext}. Removes the context from the {@link Monitor}.
   *
   * @param context The {@link MonitorConnectionContext} representing a connection.
   */
  void stopMonitoring(MonitorConnectionContext context);

  /**
   * Stop monitoring the node for all connections represented by the given set of node keys.
   *
   * @param nodeKeys All known references to a server.
   */
  void stopMonitoringForAllConnections(Set<String> nodeKeys);

  void releaseResources();

  /**
   * Handle unused {@link IMonitor}.
   *
   * @param monitor The {@link IMonitor} in idle.
   */
  void notifyUnused(IMonitor monitor);
}
