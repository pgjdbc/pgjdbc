/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.jdbc;

import org.postgresql.core.SqlCommandType;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.dataflow.qual.Pure;

import java.sql.ResultSet;

/**
 * Helper class that storing result info. This handles both the ResultSet and no-ResultSet result
 * cases with a single interface for inspecting and stepping through them.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public class ResultWrapper {
  public ResultWrapper(@Nullable ResultSet rs, SqlCommandType commandType, PgStatement statement) {
    this.rs = rs;
    this.commandType = commandType;
    this.updateCount = -1;
    this.insertOID = -1;
    this.fromPreparedStatement = isPreparedStatement(statement);
  }

  public ResultWrapper(long updateCount, long insertOID, SqlCommandType commandType, PgStatement statement) {
    this.commandType = commandType;
    this.rs = null;
    this.updateCount = updateCount;
    this.insertOID = insertOID;
    this.fromPreparedStatement = isPreparedStatement(statement);
  }

  @Pure
  public @Nullable ResultSet getResultSet() {
    return rs;
  }

  public long getUpdateCount() {
    return updateCount;
  }

  public long getInsertOID() {
    return insertOID;
  }

  public @Nullable ResultWrapper getNext() {
    return next;
  }

  /**
   * Append a result to its internal chain of results.
   * It has a special behavior for {@code SET} commands as {@code SET} is discarded if there are
   * other results in the chain.
   * If this is a {@code SET} command, the {@code newResult} is returned has the new head of
   * the chain.
   * If the newResult is a {@code SET} command, it's not appended and this is returned.
   *
   * @param newResult the result to append
   * @return the head of the chain
   */
  public ResultWrapper append(ResultWrapper newResult) {
    if (fromPreparedStatement && commandType == SqlCommandType.SET) {
      return newResult;
    }
    if (fromPreparedStatement && newResult.commandType == SqlCommandType.SET) {
      return this;
    }
    ResultWrapper tail = this;
    while (tail.next != null) {
      tail = tail.next;
    }
    tail.next = newResult;
    return this;
  }

  private static  boolean isPreparedStatement(PgStatement statement) {
    return statement instanceof PgPreparedStatement;
  }

  private final @Nullable ResultSet rs;
  private final long updateCount;
  private final long insertOID;
  private final SqlCommandType commandType;
  private @Nullable ResultWrapper next;

  private final boolean fromPreparedStatement;
}
