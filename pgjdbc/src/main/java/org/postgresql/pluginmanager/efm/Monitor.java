/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.pluginmanager.ConnectionProvider;
import org.postgresql.util.HostSpec;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Properties;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * This class uses a background thread to monitor a particular server with one or more
 * active {@link Connection}.
 */
public class Monitor implements IMonitor {

  private static final transient Logger LOGGER = Logger.getLogger(Monitor.class.getName());
  private static final int THREAD_SLEEP_WHEN_INACTIVE_MILLIS = 100;
  private static final String MONITORING_PROPERTY_PREFIX = "monitoring-";
  private final Queue<MonitorConnectionContext> contexts = new ConcurrentLinkedQueue<>();
  private final ConnectionProvider connectionProvider;
  private final HostSpec hostSpec;
  private final AtomicLong lastContextUsedTimestamp = new AtomicLong();
  private final int monitorDisposalTime;
  private final IMonitorService monitorService;
  private final AtomicBoolean stopped = new AtomicBoolean(true);
  private Properties props;
  private @Nullable BaseConnection monitoringConn = null;
  private int connectionCheckIntervalMillis = Integer.MAX_VALUE;

  /**
   * Store the monitoring configuration for a connection.
   *
   * @param connectionProvider  A provider for creating new connections.
   * @param hostSpec            The {@link HostSpec} of the server this {@link Monitor} instance is
   *                            monitoring.
   * @param props               The {@link Properties} containing additional monitoring
   *                            configuration.
   * @param monitorDisposalTime Time before stopping the monitoring thread where there are
   *                            no active connection to the server this {@link Monitor}
   *                            instance is monitoring.
   * @param monitorService      A reference to the {@link DefaultMonitorService} implementation
   *                            that initialized this class.
   */
  public Monitor(
      ConnectionProvider connectionProvider,
      HostSpec hostSpec,
      Properties props,
      int monitorDisposalTime,
      IMonitorService monitorService) {
    this.connectionProvider = connectionProvider;
    this.hostSpec = hostSpec;
    this.props = props;
    this.monitorDisposalTime = monitorDisposalTime;
    this.monitorService = monitorService;
  }

  @Override
  public synchronized void startMonitoring(MonitorConnectionContext context) {
    this.connectionCheckIntervalMillis = Math.min(
        this.connectionCheckIntervalMillis,
        context.getFailureDetectionIntervalMillis());
    final long currentTime = this.getCurrentTimeMillis();
    context.setStartMonitorTime(currentTime);
    this.lastContextUsedTimestamp.set(currentTime);
    this.contexts.add(context);
  }

  @Override
  public synchronized void stopMonitoring(@Nullable MonitorConnectionContext context) {
    if (context == null) {
      LOGGER.warning(ErrorMessageUtil.getMessage("context"));
      return;
    }
    synchronized (context) {
      this.contexts.remove(context);
      context.invalidate();
    }
    this.connectionCheckIntervalMillis = findShortestIntervalMillis();
  }

  public synchronized void clearContexts() {
    this.contexts.clear();
    this.connectionCheckIntervalMillis = findShortestIntervalMillis();
  }

  @Override
  public void run() {
    try {
      this.stopped.set(false);
      while (true) {
        if (!this.contexts.isEmpty()) {
          final long currentTime = this.getCurrentTimeMillis();
          this.lastContextUsedTimestamp.set(currentTime);

          final ConnectionStatus status =
              checkConnectionStatus(this.getConnectionCheckIntervalMillis());

          for (MonitorConnectionContext monitorContext : this.contexts) {
            monitorContext.updateConnectionStatus(
                currentTime,
                status.isValid);
          }

          TimeUnit.MILLISECONDS.sleep(
              Math.max(0, this.getConnectionCheckIntervalMillis() - status.elapsedTime));
        } else {
          if ((this.getCurrentTimeMillis() - this.lastContextUsedTimestamp.get())
              >= this.monitorDisposalTime) {
            monitorService.notifyUnused(this);
            break;
          }
          TimeUnit.MILLISECONDS.sleep(THREAD_SLEEP_WHEN_INACTIVE_MILLIS);
        }
      }
    } catch (InterruptedException intEx) {
      // do nothing; exit thread
    } finally {
      if (this.monitoringConn != null) {
        try {
          this.monitoringConn.close();
        } catch (SQLException ex) {
          // ignore
        }
      }
      this.stopped.set(true);
    }
  }

  /**
   * Check the status of the monitored server by sending a ping.
   *
   * @param shortestFailureDetectionIntervalMillis The shortest failure detection interval
   *                                               used by all the connections to this server.
   *                                               This value is used as the maximum time
   *                                               to wait for a response from the server.
   * @return whether the server is still alive and the elapsed time spent checking.
   */
  ConnectionStatus checkConnectionStatus(final int shortestFailureDetectionIntervalMillis) {
    long start = this.getCurrentTimeMillis();
    try {
      if (this.monitoringConn == null || this.monitoringConn.isClosed()) {
        // open a new connection
        Properties monitoringProps = new Properties();
        monitoringProps.setProperty(PGProperty.USER.getName(), PGProperty.USER.get(props));
        monitoringProps.setProperty(PGProperty.PASSWORD.getName(), PGProperty.PASSWORD.get(props));
        monitoringProps.setProperty("PGDBNAME", props.getProperty("PGDBNAME"));

        this.props.stringPropertyNames().stream()
            .filter(p -> p.startsWith(MONITORING_PROPERTY_PREFIX))
            .forEach(p -> monitoringProps.setProperty(
                p.substring(MONITORING_PROPERTY_PREFIX.length()),
                this.props.getProperty(p)));

        start = this.getCurrentTimeMillis();
        this.monitoringConn = this.connectionProvider.connect(
            new HostSpec[]{new HostSpec(this.hostSpec.getHost(), this.hostSpec.getPort())},
            monitoringProps,
            null);
        return new ConnectionStatus(true, this.getCurrentTimeMillis() - start);
      }

      start = this.getCurrentTimeMillis();
      boolean isValid =
          this.monitoringConn.isValid(shortestFailureDetectionIntervalMillis / 1000);
      return new ConnectionStatus(isValid, this.getCurrentTimeMillis() - start);
    } catch (SQLException sqlEx) {
      return new ConnectionStatus(false, this.getCurrentTimeMillis() - start);
    }
  }

  // This method helps to organize unit tests.
  long getCurrentTimeMillis() {
    return System.currentTimeMillis();
  }

  int getConnectionCheckIntervalMillis() {
    if (this.connectionCheckIntervalMillis == Integer.MAX_VALUE) {
      // connectionCheckIntervalMillis is at Integer.MAX_VALUE because there are no contexts
      // available.
      return 0;
    }
    return this.connectionCheckIntervalMillis;
  }

  @Override
  public boolean isStopped() {
    return this.stopped.get();
  }

  private int findShortestIntervalMillis() {
    if (this.contexts.isEmpty()) {
      return Integer.MAX_VALUE;
    }

    return this.contexts.stream()
        .min(Comparator.comparing(MonitorConnectionContext::getFailureDetectionIntervalMillis))
        .map(MonitorConnectionContext::getFailureDetectionIntervalMillis)
        .orElse(0);
  }

  static class ConnectionStatus {
    boolean isValid;
    long elapsedTime;

    ConnectionStatus(boolean isValid, long elapsedTime) {
      this.isValid = isValid;
      this.elapsedTime = elapsedTime;
    }
  }
}
