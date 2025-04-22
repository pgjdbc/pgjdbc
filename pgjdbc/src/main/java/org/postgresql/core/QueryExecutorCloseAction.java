/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The action performs connection cleanup, so it is properly terminated from the backend
 * point of view.
 * Implementation note: it should keep only the minimum number of object references
 * to reduce heap usage in case the user abandons connection without closing it first.
 */
public class QueryExecutorCloseAction implements Closeable {
  private static final Logger LOGGER = Logger.getLogger(QueryExecutorBase.class.getName());

  @SuppressWarnings("RedundantCast")
  // Cast is needed for checkerframework to accept the code
  private static final AtomicReferenceFieldUpdater<QueryExecutorCloseAction, @Nullable PGStream> PG_STREAM_UPDATER =
      AtomicReferenceFieldUpdater.newUpdater(
          QueryExecutorCloseAction.class, (Class<@Nullable PGStream>) PGStream.class, "pgStream");

  private volatile @Nullable PGStream pgStream;

  public QueryExecutorCloseAction(PGStream pgStream) {
    this.pgStream = pgStream;
  }

  public boolean isClosed() {
    PGStream pgStream = this.pgStream;
    return pgStream == null || pgStream.isClosed();
  }

  public void abort() {
    PGStream pgStream = this.pgStream;
    if (pgStream == null || !PG_STREAM_UPDATER.compareAndSet(this, pgStream, null)) {
      // The connection has already been closed
      return;
    }
    try {
      LOGGER.log(Level.FINEST, " FE=> close socket");
      pgStream.getSocket().close();
    } catch (IOException e) {
      // ignore
    }
  }

  @Override
  public void close() throws IOException {
    LOGGER.log(Level.FINEST, " FE=> Terminate");
    PGStream pgStream = this.pgStream;
    if (pgStream == null || !PG_STREAM_UPDATER.compareAndSet(this, pgStream, null)) {
      // The connection has already been closed
      return;
    }
    sendCloseMessage(pgStream);

    // Technically speaking, this check should not be needed,
    // however org.postgresql.test.jdbc2.ConnectionTest.testPGStreamSettings
    // closes pgStream reflectively, so here's an extra check to prevent failures
    // when getNetworkTimeout is called on a closed stream
    if (pgStream.isClosed()) {
      return;
    }
    pgStream.flush();
    pgStream.close();
  }

  public void sendCloseMessage(PGStream pgStream) throws IOException {
    // Technically speaking, this check should not be needed,
    // however org.postgresql.test.jdbc2.ConnectionTest.testPGStreamSettings
    // closes pgStream reflectively, so here's an extra check to prevent failures
    // when getNetworkTimeout is called on a closed stream
    if (pgStream.isClosed()) {
      return;
    }
    // Prevent blocking the thread for too long
    // The connection will be discarded anyway, so there's no much sense in waiting long
    int timeout = pgStream.getNetworkTimeout();
    if (timeout == 0 || timeout > 1000) {
      pgStream.setNetworkTimeout(1000);
    }
    pgStream.sendChar(PgMessageType.TERMINATE);
    pgStream.sendInteger4(4);
  }
}
