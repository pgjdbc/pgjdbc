/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.copy.CopyDual;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Queue;

public class CopyDualImpl extends CopyOperationImpl implements CopyDual {
  private final Queue<byte[]> received = new ArrayDeque<>();

  @Override
  public void writeToCopy(byte[] data, int off, int siz) throws SQLException {
    getQueryExecutor().writeToCopy(this, data, off, siz);
  }

  @Override
  public void writeToCopy(ByteStreamWriter from) throws SQLException {
    getQueryExecutor().writeToCopy(this, from);
  }

  @Override
  public void flushCopy() throws SQLException {
    getQueryExecutor().flushCopy(this);
  }

  @Override
  public long endCopy() throws SQLException {
    return getQueryExecutor().endCopy(this);
  }

  @Override
  public byte @Nullable [] readFromCopy() throws SQLException {
    return readFromCopy(true);
  }

  @Override
  public byte @Nullable [] readFromCopy(boolean block) throws SQLException {
    if (received.isEmpty()) {
      getQueryExecutor().readFromCopy(this, block);
    }

    return received.poll();
  }

  @Override
  public void handleCommandStatus(String status) throws PSQLException {
  }

  @Override
  protected void handleCopydata(byte[] data) {
    received.add(data);
  }
}
