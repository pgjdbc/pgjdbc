/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import org.postgresql.core.BaseConnection;

import java.sql.SQLException;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Monitoring context for each connection. This contains each connection's criteria for
 * whether a server should be considered unhealthy.
 */
public class MonitorConnectionContext {

  private static final transient Logger LOGGER =
      Logger.getLogger(MonitorConnectionContext.class.getName());

  private final int failureDetectionIntervalMillis;
  private final int failureDetectionTimeMillis;
  private final int failureDetectionCount;

  private final Set<String> nodeKeys;
  private final BaseConnection connectionToAbort;

  private long startMonitorTime;
  private long invalidNodeStartTime;
  private int failureCount;
  private boolean nodeUnhealthy;
  private boolean activeContext = true;

  /**
   * Constructor.
   *
   * @param connectionToAbort              A reference to the connection associated with this
   *                                       context
   *                                       that will be aborted in case of server failure.
   * @param nodeKeys                       All valid references to the server.
   * @param failureDetectionTimeMillis     Grace period after which node monitoring starts.
   * @param failureDetectionIntervalMillis Interval between each failed connection check.
   * @param failureDetectionCount          Number of failed connection checks before considering
   *                                       database node as unhealthy.
   */
  public MonitorConnectionContext(
      BaseConnection connectionToAbort,
      Set<String> nodeKeys,
      int failureDetectionTimeMillis,
      int failureDetectionIntervalMillis,
      int failureDetectionCount) {
    this.connectionToAbort = connectionToAbort;
    this.nodeKeys = nodeKeys;
    this.failureDetectionTimeMillis = failureDetectionTimeMillis;
    this.failureDetectionIntervalMillis = failureDetectionIntervalMillis;
    this.failureDetectionCount = failureDetectionCount;
  }

  void setStartMonitorTime(long startMonitorTime) {
    this.startMonitorTime = startMonitorTime;
  }

  Set<String> getNodeKeys() {
    return this.nodeKeys;
  }

  public int getFailureDetectionTimeMillis() {
    return failureDetectionTimeMillis;
  }

  public int getFailureDetectionIntervalMillis() {
    return failureDetectionIntervalMillis;
  }

  public int getFailureDetectionCount() {
    return failureDetectionCount;
  }

  public int getFailureCount() {
    return this.failureCount;
  }

  void setFailureCount(int failureCount) {
    this.failureCount = failureCount;
  }

  void resetInvalidNodeStartTime() {
    this.invalidNodeStartTime = 0;
  }

  boolean isInvalidNodeStartTimeDefined() {
    return this.invalidNodeStartTime > 0;
  }

  public long getInvalidNodeStartTime() {
    return this.invalidNodeStartTime;
  }

  void setInvalidNodeStartTime(long invalidNodeStartTimeMillis) {
    this.invalidNodeStartTime = invalidNodeStartTimeMillis;
  }

  public boolean isNodeUnhealthy() {
    return this.nodeUnhealthy;
  }

  void setNodeUnhealthy(boolean nodeUnhealthy) {
    this.nodeUnhealthy = nodeUnhealthy;
  }

  public boolean isActiveContext() {
    return this.activeContext;
  }

  public void invalidate() {
    this.activeContext = false;
  }

  synchronized void abortConnection() {
    if (this.connectionToAbort == null || !this.activeContext) {
      return;
    }

    try {
      this.connectionToAbort.close(); // TODO: should we do abort()?
    } catch (SQLException sqlEx) {
      // ignore
      LOGGER.finest(String.format("Exception during aborting connection: %s", sqlEx.getMessage()));
    }
  }

  /**
   * Update whether the connection is still valid if the total elapsed time has passed the
   * grace period.
   *
   * @param currentTime The time when this method is called.
   * @param isValid     Whether the connection is valid.
   */
  public void updateConnectionStatus(
      long currentTime,
      boolean isValid) {
    if (!this.activeContext) {
      return;
    }

    final long totalElapsedTimeMillis = currentTime - this.startMonitorTime;

    if (totalElapsedTimeMillis > this.failureDetectionTimeMillis) {
      this.setConnectionValid(isValid, currentTime);
    }
  }

  /**
   * Set whether the connection to the server is still valid based on the monitoring
   * settings set in the {@link BaseConnection}.
   *
   * <p>These monitoring settings include:
   * <ul>
   *   <li>{@code failureDetectionInterval}</li>
   *   <li>{@code failureDetectionTime}</li>
   *   <li>{@code failureDetectionCount}</li>
   * </ul>
   *
   * @param connectionValid Boolean indicating whether the server is still responsive.
   * @param currentTime     The time when this method is invoked in milliseconds.
   */
  void setConnectionValid(
      boolean connectionValid,
      long currentTime) {
    if (!connectionValid) {
      this.failureCount++;

      if (!this.isInvalidNodeStartTimeDefined()) {
        this.setInvalidNodeStartTime(currentTime);
      }

      final long invalidNodeDurationMillis = currentTime - this.getInvalidNodeStartTime();
      final long maxInvalidNodeDurationMillis =
          (long) this.getFailureDetectionIntervalMillis() * this.getFailureDetectionCount();

      if (invalidNodeDurationMillis >= maxInvalidNodeDurationMillis) {
        LOGGER.fine(String.format("Node '%s' is *dead*.", nodeKeys));
        this.setNodeUnhealthy(true);
        this.abortConnection();
        return;
      }

      LOGGER.fine(String.format("Node '%s' is not *responding* (%d).", nodeKeys,
          this.getFailureCount()));
      return;
    }

    this.setFailureCount(0);
    this.resetInvalidNodeStartTime();
    this.setNodeUnhealthy(false);

    LOGGER.fine(String.format("Node '%s' is *alive*.", nodeKeys));
  }
}
