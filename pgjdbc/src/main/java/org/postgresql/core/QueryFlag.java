/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

/**
 * Type safe QueryExecutor flags, for use with <code>EnumSet&lt;QueryFlag&gt;</code>. Enum sets are
 * represented internally as bit vectors. This representation is extremely compact and efficient.
 */
public enum QueryFlag {

  /**
   * Flag for query execution that indicates the given Query object is unlikely to be reused.
   */
  ONESHOT,
  /**
   * Flag for query execution that indicates that resultset metadata isn't needed and can be safely
   * omitted.
   */
  NO_METADATA,
  /**
   * Flag for query execution that indicates that a resultset isn't expected and the query executor
   * can safely discard any rows (although the resultset should still appear to be from a
   * resultset-returning query).
   */
  NO_RESULTS,
  /**
   * Flag for query execution that indicates a forward-fetch-capable cursor should be used if
   * possible.
   */
  FORWARD_CURSOR,
  /**
   * Flag for query execution that indicates the automatic BEGIN on the first statement when outside
   * a transaction should not be done.
   */
  SUPPRESS_BEGIN,
  /**
   * Flag for query execution when we don't really want to execute, we just want to get the
   * parameter metadata for the statement.
   */
  DESCRIBE_ONLY,
  /**
   * Flag for query execution used by generated keys where we want to receive both the ResultSet and
   * associated update count from the command status.
   */
  BOTH_ROWS_AND_STATUS,
  /**
   * Force this query to be described at each execution. This is done in pipelined batches where we
   * might need to detect mismatched result types.
   */
  FORCE_DESCRIBE_PORTAL,
  /**
   * Flag to disable batch execution when we expect results (generated keys) from a statement.
   *
   * @deprecated in PgJDBC 9.4 as we now auto-size batches.
   */
  @Deprecated
  DISALLOW_BATCHING,
  /**
   * Flag for query execution to avoid using binary transfer.
   */
  NO_BINARY_TRANSFER,
  /**
   * Execute the query via simple 'Q' command (not parse, bind, exec, but simple execute). This
   * sends query text on each execution, however it supports sending multiple queries separated with
   * ';' as a single command.
   */
  EXECUTE_AS_SIMPLE

}
