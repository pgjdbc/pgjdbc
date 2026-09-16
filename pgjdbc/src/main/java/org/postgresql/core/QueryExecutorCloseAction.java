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

  /**
   * Terminates the connection and releases the socket.
   *
   * <p>Sends a Terminate message first, unless {@link PGStream#isClosed()} already returns
   * {@code true}, in which case the socket is released without flushing. Only the first call to
   * this method or to {@link #abort()} releases anything; every later call returns without
   * touching the socket.</p>
   *
   * @throws IOException if the Terminate message cannot be sent, or the socket cannot be closed
   */
  @Override
  public void close() throws IOException {
    LOGGER.log(Level.FINEST, " FE=> Terminate");
    PGStream pgStream = this.pgStream;
    if (pgStream == null || !PG_STREAM_UPDATER.compareAndSet(this, pgStream, null)) {
      // The connection has already been closed
      return;
    }
    sendCloseMessage(pgStream);

    // isClosed() is true for a stream that markBroken() flagged, and also after
    // org.postgresql.test.jdbc2.ConnectionTest.testPGStreamSettings closes pgStream reflectively.
    if (pgStream.isClosed()) {
      // markBroken closes the socket only on a best-effort basis, so the descriptor may still be
      // open. Release it directly rather than through PGStream.close(): that closes pgOutput
      // first, and
      // FilterOutputStream.close() flushes. The flush pushes the tail of a half-written request
      // at a server that is already discarding it. Where markBroken could not close the socket,
      // that flush most likely fails, and its exception escapes from Connection.close().
      if (!pgStream.isSocketClosed()) {
        pgStream.getSocket().close();
      }
      return;
    }
    pgStream.flush();
    pgStream.close();
  }

  public void sendCloseMessage(PGStream pgStream) throws IOException {
    // Nothing is written to a stream that reports isClosed(): one that markBroken() flagged,
    // whose next bytes the server cannot trust, or one whose socket is closed, where
    // getNetworkTimeout below would fail. close() calls this method before its own isClosed()
    // check.
    if (pgStream.isClosed()) {
      return;
    }
    // Prevent blocking the thread for too long
    // The connection will be discarded anyway, so there's no much sense in waiting long
    int timeout = pgStream.getNetworkTimeout();
    if (timeout == 0 || timeout > 1000) {
      pgStream.setNetworkTimeout(1000);
    }
    pgStream.sendChar(PgMessageType.TERMINATE_REQUEST);
    pgStream.sendInteger4(4);
  }
}
