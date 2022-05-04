/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.pluginmanager.BasicConnectionProvider;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * This class handles the creation and clean up of monitoring threads to servers with one
 * or more active connections.
 */
public class DefaultMonitorService implements IMonitorService {

  private static final transient Logger LOGGER =
      Logger.getLogger(DefaultMonitorService.class.getName());
  final IMonitorInitializer monitorInitializer;
  MonitorThreadContainer threadContainer;

  public DefaultMonitorService() {
    this((hostInfo, props, monitorService) -> {
      int monitorDisposalTime =
          Integer.parseInt(PGProperty.MONITOR_DISPOSAL_TIME.getDefaultValue());
      try {
        monitorDisposalTime = PGProperty.MONITOR_DISPOSAL_TIME.getInt(props);
      } catch (PSQLException ex) {
        LOGGER.warning(
            "Invalid value for property ''monitorDisposalTime''. Use default value instead.");
      }

      return new Monitor(
          new BasicConnectionProvider(),
          hostInfo,
          props,
          monitorDisposalTime,
          monitorService);
    }, Executors::newCachedThreadPool);
  }

  DefaultMonitorService(
      IMonitorInitializer monitorInitializer,
      IExecutorServiceInitializer executorServiceInitializer) {

    this.monitorInitializer = monitorInitializer;
    this.threadContainer = MonitorThreadContainer.getInstance(executorServiceInitializer);
  }

  @Override
  public MonitorConnectionContext startMonitoring(
      BaseConnection connectionToAbort,
      Set<String> nodeKeys,
      HostSpec hostSpec,
      Properties props,
      int failureDetectionTimeMillis,
      int failureDetectionIntervalMillis,
      int failureDetectionCount) {

    if (nodeKeys.isEmpty()) {
      throw new IllegalArgumentException(
          "Empty NodeKey set passed into DefaultMonitorService. Set should not be empty.");
    }

    final IMonitor monitor = getMonitor(nodeKeys, hostSpec, props);

    final MonitorConnectionContext context = new MonitorConnectionContext(
        connectionToAbort,
        nodeKeys,
        failureDetectionTimeMillis,
        failureDetectionIntervalMillis,
        failureDetectionCount);

    monitor.startMonitoring(context);
    this.threadContainer.addTask(monitor);

    return context;
  }

  @Override
  public void stopMonitoring(MonitorConnectionContext context) {
    if (context == null) {
      LOGGER.warning(ErrorMessageUtil.getMessage("context"));
      return;
    }

    // Any 1 node is enough to find the monitor containing the context
    // All nodes will map to the same monitor
    final String node = this.threadContainer.getNode(context.getNodeKeys());

    if (node == null) {
      LOGGER.warning(
          "Invalid context passed into DefaultMonitorService. Could not find any NodeKey from "
              + "context.");
      return;
    }

    this.threadContainer.getMonitor(node).stopMonitoring(context);
  }

  @Override
  public void stopMonitoringForAllConnections(Set<String> nodeKeys) {
    final String node = this.threadContainer.getNode(nodeKeys);
    if (node == null) {
      LOGGER.warning(
          "Invalid node key passed into DefaultMonitorService. No existing monitor for the given "
              + "set of node keys.");
      return;
    }
    final IMonitor monitor = this.threadContainer.getMonitor(node);
    monitor.clearContexts();
    this.threadContainer.resetResource(monitor);
  }

  @Override
  public void releaseResources() {
    this.threadContainer = null;
    MonitorThreadContainer.releaseInstance();
  }

  @Override
  public synchronized void notifyUnused(IMonitor monitor) {
    if (monitor == null) {
      LOGGER.warning(ErrorMessageUtil.getMessage("monitor"));
      return;
    }

    // Remove monitor from the maps
    this.threadContainer.releaseResource(monitor);
  }

  /**
   * Get or create a {@link IMonitor} for a server.
   *
   * @param nodeKeys All references to the server requiring monitoring.
   * @param hostSpec Information such as hostname of the server.
   * @param props    The user configuration for the current connection.
   * @return A {@link IMonitor} object associated with a specific server.
   */
  protected IMonitor getMonitor(Set<String> nodeKeys, HostSpec hostSpec, Properties props) {
    return this.threadContainer.getOrCreateMonitor(nodeKeys,
        () -> monitorInitializer.createMonitor(hostSpec, props, this));
  }
}
