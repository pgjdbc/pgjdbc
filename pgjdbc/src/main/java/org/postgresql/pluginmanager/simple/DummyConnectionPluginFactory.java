/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.simple;

import org.postgresql.pluginmanager.ConnectionPlugin;
import org.postgresql.pluginmanager.ConnectionPluginFactory;
import org.postgresql.pluginmanager.CurrentConnectionProvider;

import java.util.Properties;

/**
 * Class initializing a {@link ExecutionTimeConnectionPlugin}.
 */
public class DummyConnectionPluginFactory implements ConnectionPluginFactory {
  @Override
  public ConnectionPlugin getInstance(
      CurrentConnectionProvider currentConnectionProvider,
      Properties props) {
    return new DummyConnectionPlugin();
  }
}
