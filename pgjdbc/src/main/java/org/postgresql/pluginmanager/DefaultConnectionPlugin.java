/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager;

import org.postgresql.core.BaseConnection;
import org.postgresql.util.HostSpec;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This connection plugin will always be the last plugin in the connection plugin chain,
 * and will invoke the JDBC method passed down the chain.
 */
public final class DefaultConnectionPlugin implements ConnectionPlugin {

  private static final transient Logger LOGGER =
      Logger.getLogger(DefaultConnectionPlugin.class.getName());
  private static final Set<String> subscribedMethods =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("*", "openInitialConnection")));

  protected final ConnectionProvider connectionProvider;
  protected final CurrentConnectionProvider currentConnectionProvider;

  public DefaultConnectionPlugin(CurrentConnectionProvider currentConnectionProvider) {
    this(currentConnectionProvider, new BasicConnectionProvider());
  }

  public DefaultConnectionPlugin(CurrentConnectionProvider currentConnectionProvider,
      ConnectionProvider connectionProvider) {
    if (currentConnectionProvider == null) {
      throw new IllegalArgumentException("currentConnectionProvider");
    }
    if (connectionProvider == null) {
      throw new IllegalArgumentException("connectionProvider");
    }

    this.currentConnectionProvider = currentConnectionProvider;
    this.connectionProvider = connectionProvider;
  }

  @Override
  public Set<String> getSubscribedMethods() {
    return subscribedMethods;
  }

  @Override
  public Object execute(Class<?> methodInvokeOn, String methodName, Callable<?> executeSqlFunc,
      Object[] args) throws Exception {
    try {
      return executeSqlFunc.call();
    } catch (InvocationTargetException invocationTargetException) {
      Throwable targetException = invocationTargetException.getTargetException();
      LOGGER.log(
          Level.FINEST,
          String.format("method=%s, exception: ", methodName),
          targetException);

      if (targetException instanceof Error) {
        throw (Error) targetException;
      }
      throw (Exception) targetException;
    } catch (Exception ex) {
      LOGGER.log(
          Level.FINEST,
          String.format("method=%s, exception: ", methodName),
          ex);
      throw ex;
    }
  }

  @Override
  public void openInitialConnection(HostSpec[] hostSpecs, Properties props, String url,
      Callable<Void> openInitialConnectionFunc) throws Exception {

    if (hostSpecs == null || hostSpecs.length == 0) {
      throw new IllegalArgumentException("hostSpecs");
    }

    if (this.currentConnectionProvider.getCurrentConnection() != null) {
      // Connection has already opened by a prior plugin in a plugin chain
      // Execution of openInitialConnectionFunc can be skipped since this plugin is guaranteed
      // the last one in the plugin chain
      return;
    }

    BaseConnection conn = this.connectionProvider.connect(hostSpecs, props, url);
    this.currentConnectionProvider.setCurrentConnection(conn, hostSpecs[0]);

    // Execution of openInitialConnectionFunc can be skipped since this plugin is guaranteed the
    // last one in the plugin chain
  }

  @Override
  public void releaseResources() {
    // do nothing
  }
}
