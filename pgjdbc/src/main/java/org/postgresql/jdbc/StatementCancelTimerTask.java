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

  public void run() {
    PgStatement statement = this.statement;
    if (statement != null) {
      statement.cancelIfStillNeeded(this);
    }
    // Help GC to avoid keeping reference via TimerThread -> TimerTask -> statement -> connection
    this.statement = null;
  }
}
