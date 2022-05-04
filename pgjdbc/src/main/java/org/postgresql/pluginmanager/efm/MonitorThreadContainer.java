/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager.efm;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * This singleton class keeps track of all the monitoring threads and handles the creation
 * and clean up of each monitoring thread.
 */
public class MonitorThreadContainer {
  private static final AtomicInteger CLASS_USAGE_COUNT = new AtomicInteger();
  private static final Object LOCK_OBJECT = new Object();
  private static MonitorThreadContainer singleton = null;
  private final Map<String, IMonitor> monitorMap = new ConcurrentHashMap<>();
  private final Map<IMonitor, Future<?>> tasksMap = new ConcurrentHashMap<>();
  private final Queue<IMonitor> availableMonitors = new ConcurrentLinkedDeque<>();
  private final ExecutorService threadPool;

  private MonitorThreadContainer(IExecutorServiceInitializer executorServiceInitializer) {
    this.threadPool = executorServiceInitializer.createExecutorService();
  }

  /**
   * Create an instance of the {@link MonitorThreadContainer}.
   *
   * @return a singleton instance of the {@link MonitorThreadContainer}.
   */
  public static MonitorThreadContainer getInstance() {
    return getInstance(Executors::newCachedThreadPool);
  }

  static MonitorThreadContainer getInstance(
      IExecutorServiceInitializer executorServiceInitializer) {
    if (singleton == null) {
      synchronized (LOCK_OBJECT) {
        singleton = new MonitorThreadContainer(executorServiceInitializer);
      }
      CLASS_USAGE_COUNT.set(0);
    }
    CLASS_USAGE_COUNT.getAndIncrement();
    return singleton;
  }

  /**
   * Release resources held in the {@link MonitorThreadContainer} and clear references
   * to the container.
   */
  public static void releaseInstance() {
    if (singleton == null) {
      return;
    }

    if (CLASS_USAGE_COUNT.decrementAndGet() <= 0) {
      synchronized (LOCK_OBJECT) {
        singleton.releaseResources();
        singleton = null;
      }
    }
  }

  public Map<String, IMonitor> getMonitorMap() {
    return monitorMap;
  }

  public Map<IMonitor, Future<?>> getTasksMap() {
    return tasksMap;
  }

  public ExecutorService getThreadPool() {
    return threadPool;
  }

  String getNode(Set<String> nodeKeys) {
    return getNode(nodeKeys, null);
  }

  String getNode(Set<String> nodeKeys, String defaultValue) {
    return nodeKeys
        .stream().filter(monitorMap::containsKey)
        .findAny()
        .orElse(defaultValue);
  }

  IMonitor getMonitor(String node) {
    return monitorMap.get(node);
  }

  IMonitor getOrCreateMonitor(Set<String> nodeKeys, Supplier<IMonitor> monitorSupplier) {
    final String node = getNode(nodeKeys, nodeKeys.iterator().next());
    final IMonitor monitor = monitorMap.computeIfAbsent(node, k -> {
      if (!availableMonitors.isEmpty()) {
        final IMonitor availableMonitor = availableMonitors.remove();
        if (!availableMonitor.isStopped()) {
          return availableMonitor;
        }
        tasksMap.computeIfPresent(availableMonitor, (key, v) -> {
          v.cancel(true);
          return null;
        });
      }

      return monitorSupplier.get();
    });

    populateMonitorMap(nodeKeys, monitor);
    return monitor;
  }

  private void populateMonitorMap(Set<String> nodeKeys, IMonitor monitor) {
    for (String nodeKey : nodeKeys) {
      monitorMap.putIfAbsent(nodeKey, monitor);
    }
  }

  void addTask(IMonitor monitor) {
    tasksMap.computeIfAbsent(monitor, k -> threadPool.submit(monitor));
  }

  /**
   * Clear all references used by the given monitor.
   * Put the monitor in to a queue waiting to be reused.
   *
   * @param monitor The monitor to reset.
   */
  public void resetResource(IMonitor monitor) {
    if (monitor == null) {
      return;
    }

    final List<IMonitor> monitorList = Collections.singletonList(monitor);
    monitorMap.values().removeAll(monitorList);
    availableMonitors.add(monitor);
  }

  /**
   * Remove references to the given {@link Monitor} object and stop the background monitoring
   * thread.
   *
   * @param monitor The {@link Monitor} representing a monitoring thread.
   */
  public void releaseResource(IMonitor monitor) {
    if (monitor == null) {
      return;
    }

    final List<IMonitor> monitorList = Collections.singletonList(monitor);
    monitorMap.values().removeAll(monitorList);
    tasksMap.computeIfPresent(monitor, (k, v) -> {
      v.cancel(true);
      return null;
    });
  }

  private void releaseResources() {
    monitorMap.clear();
    tasksMap.values().stream()
        .filter(val -> !val.isDone() && !val.isCancelled())
        .forEach(val -> val.cancel(true));

    if (threadPool != null) {
      threadPool.shutdownNow();
    }
  }
}
