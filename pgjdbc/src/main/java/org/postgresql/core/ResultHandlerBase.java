/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;

/**
 * Empty implementation of {@link ResultHandler} interface.
 * {@link SQLException#setNextException(SQLException)} has {@code O(N)} complexity,
 * so this class tracks the last exception object to speedup {@code setNextException}.
 */
public class ResultHandlerBase implements ResultHandler {
  // Last exception is tracked to avoid O(N) SQLException#setNextException just in case there
  // will be lots of exceptions (e.g. all batch rows fail with constraint violation or so)
  private @Nullable SQLException firstException;
  private @Nullable SQLException lastException;

  private @Nullable SQLWarning firstWarning;
  private @Nullable SQLWarning lastWarning;

  @Override
  public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
      @Nullable ResultCursor cursor) {
  }

  @Override
  public void handleCommandStatus(String status, long updateCount, long insertOID) {
  }

  @Override
  public void secureProgress() {
  }

  @Override
  public void handleWarning(SQLWarning warning) {
    if (firstWarning == null) {
      firstWarning = lastWarning = warning;
      return;
    }
    SQLWarning lastWarning = castNonNull(this.lastWarning);
    lastWarning.setNextException(warning);
    this.lastWarning = warning;
  }

  @Override
  public void handleError(SQLException error) {
    if (firstException == null) {
      firstException = lastException = error;
      return;
    }
    castNonNull(lastException).setNextException(error);
    this.lastException = error;
  }

  @Override
  public void handleCompletion() throws SQLException {
    SQLException firstException = this.firstException;
    if (firstException != null) {
      throw firstException;
    }
  }

  @Override
  public @Nullable SQLException getException() {
    return firstException;
  }

  @Override
  public @Nullable SQLWarning getWarning() {
    return firstWarning;
  }
}
