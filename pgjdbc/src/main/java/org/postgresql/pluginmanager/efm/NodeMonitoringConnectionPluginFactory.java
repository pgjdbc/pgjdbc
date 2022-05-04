/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import org.postgresql.pluginmanager.ConnectionPlugin;
import org.postgresql.pluginmanager.ConnectionPluginFactory;
import org.postgresql.pluginmanager.CurrentConnectionProvider;

import java.util.Properties;

/**
 * Class initializing a {@link NodeMonitoringConnectionPlugin}.
 */
public class NodeMonitoringConnectionPluginFactory implements ConnectionPluginFactory {
  @Override
  public ConnectionPlugin getInstance(
      CurrentConnectionProvider currentConnectionProvider,
      Properties props) {
    return new NodeMonitoringConnectionPlugin(currentConnectionProvider, props);
  }
}
