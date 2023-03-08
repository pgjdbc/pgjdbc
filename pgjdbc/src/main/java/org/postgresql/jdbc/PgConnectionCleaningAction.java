/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.Driver;
import org.postgresql.util.GT;
import org.postgresql.util.LazyCleaner;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class segregates the minimal resources required for proper cleanup in case
 * the connection has not been closed by the user code.
 * <p>For now, it has two actions:</p>
 * <ul>
 *   <li>Print stacktrace when the connection has been created, so users can identify the leak</li>
 *   <li>Release shared timer registration</li>
 * </ul>
 */
class PgConnectionCleaningAction implements LazyCleaner.CleaningAction<IOException> {
  private static final Logger LOGGER = Logger.getLogger(PgConnection.class.getName());

  private final ResourceLock lock;

  private @Nullable Throwable openStackTrace;
  private final Closeable queryExecutorCloseAction;

  /**
   * Timer for scheduling TimerTasks for the connection.
   * Only instantiated if a task is actually scheduled.
   * Access should be guarded with {@link #lock}
   */
  private @Nullable Timer cancelTimer;

  PgConnectionCleaningAction(
      ResourceLock lock,
      @Nullable Throwable openStackTrace,
      Closeable queryExecutorCloseAction) {
    this.lock = lock;
    this.openStackTrace = openStackTrace;
    this.queryExecutorCloseAction = queryExecutorCloseAction;
  }

  public Timer getTimer() {
    try (ResourceLock ignore = lock.obtain()) {
      Timer cancelTimer = this.cancelTimer;
      if (cancelTimer == null) {
        cancelTimer = Driver.getSharedTimer().getTimer();
        this.cancelTimer = cancelTimer;
      }
      return cancelTimer;
    }
  }

  public void releaseTimer() {
    try (ResourceLock ignore = lock.obtain()) {
      if (cancelTimer != null) {
        cancelTimer = null;
        Driver.getSharedTimer().releaseTimer();
      }
    }
  }

  public void purgeTimerTasks() {
    try (ResourceLock ignore = lock.obtain()) {
      Timer timer = cancelTimer;
      if (timer != null) {
        timer.purge();
      }
    }
  }

  @Override
  public void onClean(boolean leak) throws IOException {
    if (leak && openStackTrace != null) {
      LOGGER.log(Level.WARNING, GT.tr("Leak detected: Connection.close() was not called"), openStackTrace);
    }
    openStackTrace = null;
    releaseTimer();
    queryExecutorCloseAction.close();
  }
}
