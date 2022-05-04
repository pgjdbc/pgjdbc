/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager;

import org.postgresql.util.HostSpec;

import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Interface for connection plugins. This class implements ways to execute a JDBC method
 * and to clean up resources used before closing the plugin.
 */
public interface ConnectionPlugin {
  Set<String> getSubscribedMethods();

  Object execute(Class<?> methodInvokeOn, String methodName, Callable<?> executeSqlFunc,
      Object[] args)
      throws Exception;

  void openInitialConnection(HostSpec[] hostSpecs, Properties props, String url,
      Callable<Void> openInitialConnectionFunc) throws Exception;

  void releaseResources();
}
