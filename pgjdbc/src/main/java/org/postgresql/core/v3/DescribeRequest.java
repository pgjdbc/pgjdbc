/*
 * Copyright (c) 2015, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Information for "pending describe queue".
 *
 * @see QueryExecutorImpl#pendingDescribeStatementQueue
 */
class DescribeRequest {
  public final SimpleQuery query;
  public final SimpleParameterList parameterList;
  public final boolean describeOnly;
  public final @Nullable String statementName;
  /**
   * The parameter types sent in Parse. The response overwrites the parameter list with the types
   * the server resolved, so the types that produced the response are kept here to key them with.
   */
  public final int[] requestedParameterTypes;

  DescribeRequest(SimpleQuery query, SimpleParameterList parameterList,
      boolean describeOnly, @Nullable String statementName, int[] requestedParameterTypes) {
    this.query = query;
    this.parameterList = parameterList;
    this.describeOnly = describeOnly;
    this.statementName = statementName;
    this.requestedParameterTypes = requestedParameterTypes;
  }
}
