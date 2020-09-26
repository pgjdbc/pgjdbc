/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.copy.CopyOperation;
import org.postgresql.exception.PgSqlState;
import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;

public abstract class CopyOperationImpl implements CopyOperation {
  @Nullable QueryExecutorImpl queryExecutor;
  int rowFormat;
  int @Nullable [] fieldFormats;
  long handledRowCount = -1;

  void init(QueryExecutorImpl q, int fmt, int[] fmts) {
    queryExecutor = q;
    rowFormat = fmt;
    fieldFormats = fmts;
  }

  protected QueryExecutorImpl getQueryExecutor() {
    return castNonNull(queryExecutor);
  }

  @Override
  public void cancelCopy() throws SQLException {
    castNonNull(queryExecutor).cancelCopy(this);
  }

  @Override
  public int getFieldCount() {
    return castNonNull(fieldFormats).length;
  }

  @Override
  public int getFieldFormat(int field) {
    return castNonNull(fieldFormats)[field];
  }

  @Override
  public int getFormat() {
    return rowFormat;
  }

  @Override
  public boolean isActive() {
    synchronized (castNonNull(queryExecutor)) {
      return queryExecutor.hasLock(this);
    }
  }

  public void handleCommandStatus(String status) throws SQLException {
    if (status.startsWith("COPY")) {
      int i = status.lastIndexOf(' ');
      handledRowCount = i > 3 ? Long.parseLong(status.substring(i + 1)) : -1;
    } else {
      throw new SQLException(GT.tr("CommandComplete expected COPY but got: " + status),
          PgSqlState.PROTOCOL_VIOLATION);
    }
  }

  /**
   * Consume received copy data.
   *
   * @param data data that was receive by copy protocol
   * @throws SQLException if some internal problem occurs
   */
  protected abstract void handleCopydata(byte[] data) throws SQLException;

  @Override
  public long getHandledRowCount() {
    return handledRowCount;
  }
}
