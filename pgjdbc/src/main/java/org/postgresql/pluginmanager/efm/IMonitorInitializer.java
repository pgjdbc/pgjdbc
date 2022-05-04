/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import org.postgresql.util.HostSpec;

import java.util.Properties;

/**
 * Interface for initialize a new {@link Monitor}.
 */
@FunctionalInterface
public interface IMonitorInitializer {
  IMonitor createMonitor(HostSpec hostSpec, Properties props, IMonitorService monitorService);
}
