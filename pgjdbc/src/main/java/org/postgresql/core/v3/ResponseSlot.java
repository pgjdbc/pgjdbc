/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.Field;
import org.postgresql.core.Tuple;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.List;

/**
 * Completion token for a pipelined query. Created before sending,
 * populated during response processing in {@code readResponses()}.
 */
class ResponseSlot {

  /**
   * Sentinel instance used to mark Sync boundaries in the pending queue.
   * When ReadyForQuery is received, slots are drained up to and including
   * the next SYNC_MARKER to handle error cascading.
   */
  static final ResponseSlot SYNC_MARKER = new ResponseSlot();

  @Nullable SimpleQuery query;
  @Nullable Portal portal;
  boolean asSimple;
  int flags;

  // Results — populated during readResponses()
  @Nullable Field[] fields;
  @Nullable List<Tuple> tuples;
  @Nullable String commandStatus;
  long updateCount;
  long insertOID;
  @Nullable SQLWarning warnings;
  @Nullable SQLException error;
  boolean portalSuspended;

  /** Sentinel constructor */
  @SuppressWarnings("initialization.fields.uninitialized")
  private ResponseSlot() {
  }

  @SuppressWarnings("initialization.fields.uninitialized")
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

  void setError(SQLException ex) {
    if (this.error == null) {
      this.error = ex;
    }
  }

  /**
   * Mark this slot as fully populated. In the synchronous pipeline model
   * this is a logical marker only (no thread signaling needed).
   */
  void complete() {
  }

  void completeExceptionally(SQLException ex) {
    setError(ex);
  }
}
