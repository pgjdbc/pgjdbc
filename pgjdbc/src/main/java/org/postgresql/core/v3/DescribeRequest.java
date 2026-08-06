/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Information for "pending describe queue".
 *
 * <p>{@code handle} is the server statement the Describe was sent for, snapshotted at send time,
 * and {@code statementName} is the name that statement had at that moment: the statement might be
 * re-prepared under a different name before the response arrives, in which case the response must
 * not be applied.</p>
 *
 * @see QueryExecutorImpl#pendingDescribeStatementQueue
 */
class DescribeRequest {
  public final SimpleQuery query;
  public final ServerHandle handle;
  public final SimpleParameterList parameterList;
  public final boolean describeOnly;
  public final @Nullable String statementName;
  /**
   * The parameter types sent in Parse. The response overwrites the parameter list with the types
   * the server resolved, so the types that produced the response are kept here to key them with.
   */
  public final int[] requestedParameterTypes;

  DescribeRequest(SimpleQuery query, ServerHandle handle, SimpleParameterList parameterList,
      boolean describeOnly, @Nullable String statementName, int[] requestedParameterTypes) {
    this.query = query;
    this.handle = handle;
    this.parameterList = parameterList;
    this.describeOnly = describeOnly;
    this.statementName = statementName;
    this.requestedParameterTypes = requestedParameterTypes;
  }
}
