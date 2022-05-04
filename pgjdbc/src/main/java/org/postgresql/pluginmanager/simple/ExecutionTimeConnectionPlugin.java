/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.simple;

import org.postgresql.pluginmanager.ConnectionPlugin;
import org.postgresql.util.HostSpec;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This connection plugin tracks the execution time of all the given JDBC method throughout
 * the lifespan of the current connection.
 *
 * <p>During the cleanup phase when {@link ExecutionTimeConnectionPlugin#releaseResources()}
 * is called, this plugin logs all the methods executed and time spent on each execution
 * in milliseconds.
 */
public class ExecutionTimeConnectionPlugin implements ConnectionPlugin {

  private static final transient Logger LOGGER =
      Logger.getLogger(ExecutionTimeConnectionPlugin.class.getName());
  private static final Set<String> subscribedMethods =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("*")));

  final long initializeTime;
  private final Map<String, Long> methodExecutionTimes = new HashMap<>();

  public ExecutionTimeConnectionPlugin() {
    initializeTime = System.nanoTime();
  }

  @Override
  public Set<String> getSubscribedMethods() {
    return subscribedMethods;
  }

  @Override
  public Object execute(
      Class<?> methodInvokeOn,
      String methodName,
      Callable<?> executeJdbcMethod, Object[] args)
      throws Exception {

    // This `execute` measures the time it takes for the remaining connection plugins to
    // execute the given method call.
    final long startTime = System.nanoTime();

    final Object executeResult = executeJdbcMethod.call();

    final long elapsedTime = System.nanoTime() - startTime;
    methodExecutionTimes.merge(
        methodName,
        elapsedTime / 1000000,
        Long::sum);

    if (methodName == "Connection.close") {
      printExecutionTimes();
    }
    return executeResult;
  }

  @Override
  public void openInitialConnection(HostSpec[] hostSpecs, Properties props, String url,
      Callable<Void> openInitialConnectionFunc) throws Exception {

    // Intentionally do nothing.
    // This plugin is not subscribed for "openInitialConnection" method so this method won't be
    // ever called.
  }

  @Override
  public void releaseResources() {
    printExecutionTimes();
  }

  protected void printExecutionTimes() {
    // Output the aggregated information from all methods called throughout the lifespan
    // of the current connection.
    final long connectionUptime = System.nanoTime() - initializeTime;
    final String leftAlignFormat = "| %-40s | %10s |\n";
    final StringBuilder logMessage = new StringBuilder();

    logMessage.append("** ExecutionTimeConnectionPlugin Summary **\n");
    logMessage.append(String.format(
        "Connection Uptime: %d ms\n",
        connectionUptime / 1000000
    ));

    logMessage
        .append("** Method Execution Time **\n")
        .append("+------------------------------------------+------------+\n")
        .append("| Method Executed                          | Total Time |\n")
        .append("+------------------------------------------+------------+\n");

    methodExecutionTimes.forEach((key, val) -> logMessage.append(String.format(
        leftAlignFormat,
        key,
        val + " ms")));
    logMessage.append("+------------------------------------------+------------+\n");
    LOGGER.log(Level.INFO, logMessage.toString());

    methodExecutionTimes.clear();
  }
}
