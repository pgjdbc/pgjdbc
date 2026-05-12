/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.Field;
import org.postgresql.core.Tuple;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Completion token for a pipelined query. Created by the sending thread,
 * populated and completed by the reader thread.
 *
 * <p>Uses LockSupport.park/unpark instead of CompletableFuture to avoid
 * per-query object allocation overhead.</p>
 */
class ResponseSlot {

  /**
   * Sentinel instance used to mark Sync boundaries in the pending queue.
   * When the reader thread encounters ReadyForQuery, it drains slots up to
   * and including the next SYNC_MARKER to handle error cascading.
   */
  static final ResponseSlot SYNC_MARKER = new ResponseSlot();

  @Nullable SimpleQuery query;
  @Nullable Portal portal;
  boolean asSimple;
  int flags;

  // Results — written only by reader thread
  @Nullable Field[] fields;
  @Nullable List<Tuple> tuples;
  @Nullable String commandStatus;
  long updateCount;
  long insertOID;
  @Nullable SQLWarning warnings;
  @Nullable SQLException error;
  boolean portalSuspended;

  // Signaling: reader thread sets done=true then unparks the waiter
  private volatile boolean done;
  private volatile @Nullable Thread waiter;

  /** Sentinel constructor */
  @SuppressWarnings("initialization.fields.uninitialized")
  private ResponseSlot() {
  }

  ResponseSlot(@Nullable SimpleQuery query, @Nullable Portal portal, boolean asSimple, int flags) {
    this.query = query;
    this.portal = portal;
    this.asSimple = asSimple;
    this.flags = flags;
  }

  void addTuple(Tuple t) {
    if (tuples == null) {
      tuples = new ArrayList<>();
    }
    tuples.add(t);
  }

  void addWarning(SQLWarning w) {
    if (warnings == null) {
      warnings = w;
    } else {
      warnings.setNextWarning(w);
    }
  }

  /**
   * Called by the sending thread to block until the reader thread completes this slot.
   *
   * @param readerThread the pipeline reader thread; used for liveness checks
   * @throws SQLException if the reader thread dies before completing this slot
   */
  void await(Thread readerThread) throws SQLException {
    waiter = Thread.currentThread();
    while (!done) {
      LockSupport.parkNanos(this, 1_000_000_000L); // 1 second
      if (!done && !readerThread.isAlive()) {
        throw new PSQLException(
            "Pipeline reader thread died unexpectedly",
            PSQLState.CONNECTION_FAILURE);
      }
    }
  }

  /**
   * Called by the sending thread to block until the reader thread completes this slot.
   * No liveness check — use only when reader thread reference is unavailable.
   */
  void await() {
    waiter = Thread.currentThread();
    while (!done) {
      LockSupport.park(this);
    }
  }

  /**
   * Called by the reader thread to signal completion.
   */
  void complete() {
    done = true;
    Thread w = waiter;
    if (w != null) {
      LockSupport.unpark(w);
    }
  }

  void completeExceptionally(SQLException ex) {
    if (this.error == null) {
      this.error = ex;
    }
    complete();
  }
}
