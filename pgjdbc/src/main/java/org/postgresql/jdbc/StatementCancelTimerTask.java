/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.TimerTask;

/**
 * Timer task that sends {@code statement.cancel()} signal to support {@link java.sql.Statement#setQueryTimeout(int)}.
 * We explicitly nullify the reference to statement to help GC since {@code java.util.TimerThread}
 * might keep reference to the latest executed task in its local variable.
 */
class StatementCancelTimerTask extends TimerTask {
  private @Nullable PgStatement statement;

  StatementCancelTimerTask(PgStatement statement) {
    this.statement = statement;
  }

  @Override
  public boolean cancel() {
    boolean result = super.cancel();
    // Help GC to avoid keeping reference via TimerThread -> TimerTask -> statement -> connection
    statement = null;
    return result;
  }

  @Override
  public void run() {
    PgStatement statement = this.statement;
    if (statement != null) {
      try {
        statement.cancelIfStillNeeded(this);
      } catch (Throwable ignore) {
        // java.util.Timer cancels if a task throws an exception
        // We don't want to cancel the timer, so we ignore the exception
        // The exception might be something like OutOfMemoryError or StackOverflowError, so
        // we can't even log the exception as a mere attempt to log the exception might throw a new
        // StackOverflowError or OutOfMemoryError.
        // At the same time, if we rethrow the exception, it will cancel the timer, it will cleanup
        // the list of the timer tasks, so it would affect the application as well
        //
        // We can't reliably cancel the query at the database side anyways, so let's pretend that
        // we tried our best to cancel the query, and let the application decide what to do with it.
      }
    }
    // Help GC to avoid keeping reference via TimerThread -> TimerTask -> statement -> connection
    this.statement = null;
  }
}
