/*-------------------------------------------------------------------------
*
* Copyright (c) 2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.core.v3;

/**
 * Information for "pending execute queue"
 *
 * @see QueryExecutorImpl#pendingExecuteQueue
 */
class ExecuteRequest {
  public final SimpleQuery query;
  public final Portal portal;

  public ExecuteRequest(SimpleQuery query, Portal portal) {
    this.query = query;
    this.portal = portal;
  }
}
