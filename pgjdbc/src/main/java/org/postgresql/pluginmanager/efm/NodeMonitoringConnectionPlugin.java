/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.pluginmanager.ConnectionPlugin;
import org.postgresql.pluginmanager.CurrentConnectionProvider;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Monitor the server while the connection is executing methods for more sophisticated
 * failure detection.
 */
public class NodeMonitoringConnectionPlugin implements ConnectionPlugin {

  private static final String RETRIEVE_HOST_PORT_SQL =
      "SELECT CONCAT(inet_server_addr( ), ':', inet_server_port( ))";
  private static final List<String> METHODS_STARTING_WITH = Arrays.asList("get", "abort");
  private static final List<String> METHODS_EQUAL_TO = Arrays.asList("close", "next");

  private static final transient Logger LOGGER =
      Logger.getLogger(NodeMonitoringConnectionPlugin.class.getName());
  private static final Set<String> subscribedMethods =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("Statement.executeQuery")));
  private final Supplier<IMonitorService> monitorServiceSupplier;
  private final Set<String> nodeKeys = new HashSet<>();
  private final CurrentConnectionProvider currentConnectionProvider;
  protected Properties props;
  private @Nullable IMonitorService monitorService;
  private BaseConnection connection;

  /**
   * Initialize the node monitoring plugin.
   *
   * @param currentConnectionProvider A provider allowing the plugin to retrieve the
   *                                  current active connection and its connection settings.
   * @param props                     The property set used to initialize the active connection.
   */
  public NodeMonitoringConnectionPlugin(
      CurrentConnectionProvider currentConnectionProvider,
      Properties props) {
    this(
        currentConnectionProvider,
        props,
        DefaultMonitorService::new);
  }

  NodeMonitoringConnectionPlugin(
      CurrentConnectionProvider currentConnectionProvider,
      Properties props,
      Supplier<IMonitorService> monitorServiceSupplier) {

    assertArgumentIsNotNull(currentConnectionProvider, "currentConnectionProvider");
    assertArgumentIsNotNull(props, "props");

    this.currentConnectionProvider = currentConnectionProvider;
    this.connection = currentConnectionProvider.getCurrentConnection();
    this.props = props;
    this.monitorServiceSupplier = monitorServiceSupplier;

    generateNodeKeys(this.currentConnectionProvider.getCurrentConnection());
  }

  @Override
  public Set<String> getSubscribedMethods() {
    // TODO: adjust according to the code
    return subscribedMethods;
  }

  /**
   * Executes the given SQL function with {@link Monitor} if connection monitoring is enabled.
   * Otherwise, executes the SQL function directly.
   *
   * @param methodInvokeOn Class of an object that method to monitor to be invoked on.
   * @param methodName     Name of the method to monitor.
   * @param executeSqlFunc {@link Callable} SQL function.
   * @param args           method arguments.
   * @return Results of the {@link Callable} SQL function.
   * @throws Exception if an error occurs.
   */
  @Override
  public Object execute(
      Class<?> methodInvokeOn,
      String methodName,
      Callable<?> executeSqlFunc,
      Object[] args) throws Exception {
    // update config settings since they may change
    final boolean isEnabled = PGProperty.FAILURE_DETECTION_ENABLED.getBoolean(props);

    if (!isEnabled || !this.doesNeedMonitoring(methodInvokeOn, methodName)) {
      // do direct call
      return executeSqlFunc.call();
    }
    // ... otherwise, use a separate thread to execute method

    final int failureDetectionTimeMillis = PGProperty.FAILURE_DETECTION_TIME.getInt(props);
    final int failureDetectionIntervalMillis = PGProperty.FAILURE_DETECTION_INTERVAL.getInt(props);
    final int failureDetectionCount = PGProperty.FAILURE_DETECTION_COUNT.getInt(props);

    initMonitorService();

    Object result;
    MonitorConnectionContext monitorContext = null;

    try {
      LOGGER.fine(String.format(
          "method=%s.%s, monitoring is activated",
          methodInvokeOn.getName(),
          methodName));

      this.checkIfChanged(this.currentConnectionProvider.getCurrentConnection());

      monitorContext = castNonNull(this.monitorService).startMonitoring(
          this.connection, //abort current connection if needed
          this.nodeKeys,
          this.currentConnectionProvider.getCurrentHostSpec(),
          this.props,
          failureDetectionTimeMillis,
          failureDetectionIntervalMillis,
          failureDetectionCount);

      result = executeSqlFunc.call();

    } finally {
      if (monitorContext != null) {
        this.monitorService.stopMonitoring(monitorContext);
        synchronized (monitorContext) {
          if (monitorContext.isNodeUnhealthy() && !this.connection.isClosed()) {
            abortConnection();
            throw new PSQLException("Node is unavailable.", PSQLState.CONNECTION_FAILURE);
          }
        }
      }
      LOGGER.fine(String.format(
          "method=%s.%s, monitoring is deactivated",
          methodInvokeOn.getName(),
          methodName));
    }

    return result;
  }

  void abortConnection() {
    try {
      this.connection.close(); //TODO: should we abort()
    } catch (SQLException sqlEx) {
      // ignore
    }
  }

  /**
   * Checks whether the JDBC method passed to this connection plugin requires monitoring.
   *
   * @param methodInvokeOn The class of the JDBC method.
   * @param methodName     Name of the JDBC method.
   * @return true if the method requires monitoring; false otherwise.
   */
  protected boolean doesNeedMonitoring(Class<?> methodInvokeOn, String methodName) {
    // It's possible to use the following, or similar, expressions to verify method invocation class
    //
    // boolean isJdbcConnection = JdbcConnection.class.isAssignableFrom(methodInvokeOn) ||
    // ClusterAwareConnectionProxy.class.isAssignableFrom(methodInvokeOn);
    // boolean isJdbcStatement = Statement.class.isAssignableFrom(methodInvokeOn);
    // boolean isJdbcResultSet = ResultSet.class.isAssignableFrom(methodInvokeOn);

    for (final String method : METHODS_STARTING_WITH) {
      if (methodName.startsWith(method)) {
        return false;
      }
    }

    for (final String method : METHODS_EQUAL_TO) {
      if (method.equals(methodName)) {
        return false;
      }
    }

    // Monitor all the other methods
    return true;
  }

  private void initMonitorService() {
    if (this.monitorService == null) {
      this.monitorService = this.monitorServiceSupplier.get();
    }
  }

  @Override
  public void openInitialConnection(HostSpec[] hostSpecs, Properties props, String url,
      Callable<Void> openInitialConnectionFunc) throws Exception {
    // Intentionally do nothing.
    // This plugin is not subscribed for "openInitialConnection" method so this method won't be
    // ever called.
  }

  /**
   * Call this plugin's monitor service to release all resources associated with this
   * plugin.
   */
  @Override
  public void releaseResources() {
    if (this.monitorService != null) {
      this.monitorService.releaseResources();
    }

    this.monitorService = null;
  }

  private void assertArgumentIsNotNull(Object param, String paramName) {
    if (param == null) {
      throw new IllegalArgumentException(ErrorMessageUtil.getMessage(paramName));
    }
  }

  /**
   * Check if the connection has changed.
   * If so, remove monitor's references to that node and
   * regenerate the set of node keys referencing the node that requires monitoring.
   *
   * @param newConnection The connection used by {@link CurrentConnectionProvider}.
   */
  private void checkIfChanged(BaseConnection newConnection) {
    final boolean isSameConnection = this.connection.equals(newConnection);
    if (!isSameConnection) {
      castNonNull(this.monitorService).stopMonitoringForAllConnections(this.nodeKeys);
      this.connection = newConnection;
      generateNodeKeys(this.connection);
    }
  }

  /**
   * Generate a set of node keys representing the node to monitor.
   *
   * @param connection the connection to a specific node.
   */
  private void generateNodeKeys(BaseConnection connection) {
    this.nodeKeys.clear();
    try (Statement stmt = connection.createStatement()) {
      try (ResultSet rs = stmt.executeQuery(RETRIEVE_HOST_PORT_SQL)) {
        while (rs.next()) {
          this.nodeKeys.add(rs.getString(1));
        }
      }
    } catch (SQLException sqlException) {
      // log and ignore
      LOGGER.finest("Could not retrieve Host:Port from querying");
    }

    final HostSpec hostSpec = this.currentConnectionProvider.getCurrentHostSpec();
    this.nodeKeys.add(String.format("%s:%s", hostSpec.getHost(), hostSpec.getPort()));
    LOGGER.fine("Node keys: " + this.nodeKeys);
  }
}
