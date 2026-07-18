/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Information for "pending execute queue".
 *
 * <p>{@code handle} is the server statement the Execute was sent for, snapshotted at send time:
 * responses must update the statement they were requested for even if the query has moved on to
 * another handle by the time they arrive.</p>
 *
 * @see QueryExecutorImpl#pendingExecuteQueue
 */
class ExecuteRequest {
  public final SimpleQuery query;
  public final ServerHandle handle;
  public final @Nullable Portal portal;
  public final boolean asSimple;

  ExecuteRequest(SimpleQuery query, ServerHandle handle, @Nullable Portal portal,
      boolean asSimple) {
    this.query = query;
    this.handle = handle;
    this.portal = portal;
    this.asSimple = asSimple;
  }
}
