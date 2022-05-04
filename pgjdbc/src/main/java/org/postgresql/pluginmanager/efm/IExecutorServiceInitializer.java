/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import java.util.concurrent.ExecutorService;

/**
 * Interface for passing a specific {@link ExecutorService} to use by the
 * {@link MonitorThreadContainer}.
 */
@FunctionalInterface
public interface IExecutorServiceInitializer {
  ExecutorService createExecutorService();
}
