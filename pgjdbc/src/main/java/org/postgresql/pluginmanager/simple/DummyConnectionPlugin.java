/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.simple;

import org.postgresql.pluginmanager.ConnectionPlugin;
import org.postgresql.util.HostSpec;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

public class DummyConnectionPlugin implements ConnectionPlugin {

  private static final transient Logger LOGGER =
      Logger.getLogger(DummyConnectionPlugin.class.getName());
  private static final Set<String> subscribedMethods =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("*")));

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

    return executeJdbcMethod.call();
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
    // no resources to release
  }
}
