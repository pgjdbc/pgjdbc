/*
 * Copyright (c) 2009, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.copy.CopyOperation;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

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

  public void cancelCopy() throws SQLException {
    castNonNull(queryExecutor).cancelCopy(this);
  }

  public int getFieldCount() {
    return castNonNull(fieldFormats).length;
  }

  public int getFieldFormat(int field) {
    return castNonNull(fieldFormats)[field];
  }

  public int getFormat() {
    return rowFormat;
  }

  public boolean isActive() {
    synchronized (castNonNull(queryExecutor)) {
      return queryExecutor.hasLock(this);
    }
  }

  public void handleCommandStatus(String status) throws PSQLException {
    if (status.startsWith("COPY")) {
      int i = status.lastIndexOf(' ');
      handledRowCount = i > 3 ? Long.parseLong(status.substring(i + 1)) : -1;
    } else {
      throw new PSQLException(GT.tr("CommandComplete expected COPY but got: " + status),
          PSQLState.COMMUNICATION_ERROR);
    }
  }

  /**
   * Consume received copy data.
   *
   * @param data data that was receive by copy protocol
   * @throws PSQLException if some internal problem occurs
   */
  protected abstract void handleCopydata(byte[] data) throws PSQLException;

  public long getHandledRowCount() {
    return handledRowCount;
  }
}
