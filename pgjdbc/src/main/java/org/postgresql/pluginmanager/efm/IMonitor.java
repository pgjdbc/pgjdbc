/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

/**
 * Interface for monitors. This class uses background threads to monitor servers with one
 * or more connections for more efficient failure detection during method execution.
 */
public interface IMonitor extends Runnable {
  void startMonitoring(MonitorConnectionContext context);

  void stopMonitoring(MonitorConnectionContext context);

  /**
   * Clear all {@link MonitorConnectionContext} associated with this {@link IMonitor} instance.
   */
  void clearContexts();

  /**
   * Whether this {@link IMonitor} has stopped monitoring a particular server.
   *
   * @return true if the monitoring has stopped; false otherwise.
   */
  boolean isStopped();
}
