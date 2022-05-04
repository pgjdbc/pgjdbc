/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager;

import java.util.Properties;

/**
 * Initialize a {@link DefaultConnectionPlugin}.
 */
public final class DefaultConnectionPluginFactory implements ConnectionPluginFactory {
  @Override
  public ConnectionPlugin getInstance(
      CurrentConnectionProvider currentConnectionProvider,
      Properties props) {
    return new DefaultConnectionPlugin(currentConnectionProvider);
  }
}
