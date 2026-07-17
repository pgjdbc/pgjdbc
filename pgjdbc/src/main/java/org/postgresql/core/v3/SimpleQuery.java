/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import org.postgresql.core.Field;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.SqlCommand;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.PhantomReference;
import java.util.Arrays;
import java.util.Map;

/**
 * V3 Query implementation for a single-statement query. The state of the associated server-side
 * named statement lives in a {@link ServerHandle}; a PhantomReference managed by the QueryExecutor
 * handles statement cleanup.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class SimpleQuery implements Query {

  SimpleQuery(SimpleQuery src) {
    this(src.nativeQuery, src.transferModeRegistry, src.sanitiserDisabled);
  }

  SimpleQuery(NativeQuery query, @Nullable TypeTransferModeRegistry transferModeRegistry,
      boolean sanitiserDisabled) {
    this.nativeQuery = query;
    this.transferModeRegistry = transferModeRegistry;
    this.sanitiserDisabled = sanitiserDisabled;
  }

  @Override
  public ParameterList createParameterList() {
    if (nativeQuery.bindPositions.length == 0) {
      return NO_PARAMETERS;
    }

    return new SimpleParameterList(getBindCount(), transferModeRegistry);
  }

  @Override
  public String toString(@Nullable ParameterList parameters) {
    return toString(parameters, DefaultSqlSerializationContext.STDSTR_IDEMPOTENT);
  }

  @Override
  public String toString(@Nullable ParameterList parameters, SqlSerializationContext context) {
    return nativeQuery.toString(parameters, context);
  }

  @Override
  public String toString() {
    return toString(null);
  }

  @Override
  public void close() {
    unprepare();
  }

  @Override
  public SimpleQuery @Nullable [] getSubqueries() {
    return null;
  }

  /**
   * Return maximum size in bytes that each result row from this query may return. Mainly used for
   * batches that return results.
   *
   * <p>Results are cached until/unless the query is re-described.</p>
   *
   * @return Max size of result data in bytes according to returned fields, 0 if no results, -1 if
   *         result is unbounded.
   * @throws IllegalStateException if the query is not described
   */
  public int getMaxResultRowSize() {
    return handle.getMaxResultRowSize();
  }

  //
  // Implementation guts
  //

  @Override
  public String getNativeSql() {
    return nativeQuery.nativeSql;
  }

  /**
   * Returns the server-side statement backing this query. The protocol layer resolves the handle
   * once per execution and threads it through the outgoing messages and pending queues, so server
   * responses update the statement they were requested for even if the query moves on to another
   * handle in the meantime.
   *
   * @return the server-side statement backing this query
   */
  ServerHandle getHandle() {
    return handle;
  }

  /**
   * Returns the statement state used by one-shot executions whenever the named statement does not
   * match the current parameter types. It never gets a statement name or a cleanup reference, and
   * every one-shot parse overwrites it. Keeping it separate means a one-shot execution with
   * different parameter types (for example {@code getParameterMetaData()}) no longer destroys the
   * named server-prepared statement, and one-shot describe results no longer masquerade as the
   * named statement's metadata.
   *
   * @return the statement state for one-shot executions
   */
  ServerHandle getUnnamedHandle() {
    ServerHandle unnamedHandle = this.unnamedHandle;
    if (unnamedHandle == null) {
      this.unnamedHandle = unnamedHandle = new ServerHandle();
    }
    return unnamedHandle;
  }

  /**
   * Returns true if some execution of this query has seen a row description, on the named or the
   * unnamed statement. Used as a "this query returns rows" heuristic by the automatic-savepoint
   * logic; the union over both statements preserves the pre-split behavior, where any execution
   * marked the shared state.
   *
   * @return true if some execution of this query has seen a row description
   */
  boolean hasResultFields() {
    ServerHandle unnamedHandle = this.unnamedHandle;
    return handle.getFields() != null
        || (unnamedHandle != null && unnamedHandle.getFields() != null);
  }

  void setStatementName(String statementName, short deallocateEpoch) {
    handle.setStatementName(statementName, deallocateEpoch);
  }

  void setPrepareTypes(int[] paramTypes) {
    handle.setPrepareTypes(paramTypes);
  }

  int @Nullable [] getPrepareTypes() {
    return handle.getPrepareTypes();
  }

  @Nullable String getStatementName() {
    return handle.getStatementName();
  }

  boolean isPreparedFor(int[] paramTypes, short deallocateEpoch) {
    return handle.isPreparedFor(paramTypes, deallocateEpoch);
  }

  boolean hasUnresolvedTypes() {
    return handle.hasUnresolvedTypes();
  }

  /**
   * Returns the server-resolved parameter types from a cached "describe statement" result that is
   * compatible with the given parameter types, or {@code null} if no such result is cached and a
   * network round trip is required.
   *
   * <p>A cached result is compatible when every given type either equals the resolved type, or is
   * {@link Oid#UNSPECIFIED} and the describe request was sent with that position unspecified as
   * well. The latter restriction matters: the server infers unspecified types from the specified
   * ones (consider overloaded functions), so a result described with a type set at some position
   * says nothing about describing with that position unset.</p>
   *
   * <p>A result is reused only while {@code deallocateEpoch} still equals the one the result was
   * captured at. The epoch is bumped whenever the server may resolve the types differently, for
   * instance after DDL or after {@code SET search_path}. Results captured at an older epoch are
   * discarded.</p>
   *
   * <p>Callers must not modify the returned array.</p>
   *
   * @param paramTypes current parameter type OIDs, {@link Oid#UNSPECIFIED} for unset ones
   * @param deallocateEpoch the connection's current deallocate epoch
   * @return parameter type OIDs resolved by the server, or {@code null} on cache miss
   */
  int @Nullable [] getCachedDescribeResult(int[] paramTypes, short deallocateEpoch) {
    @Nullable DescribeResult[] results = describeResults;
    if (results == null) {
      return null;
    }
    if (describeResultsEpoch != deallocateEpoch) {
      this.describeResults = null;
      return null;
    }
    for (int i = 0; i < results.length; i++) {
      @Nullable DescribeResult result = results[i];
      if (result == null) {
        break;
      }
      if (result.matches(paramTypes)) {
        // Keep the results in most-recently-used order, so alternating type patterns
        // do not evict each other
        System.arraycopy(results, 0, results, 1, i);
        results[0] = result;
        return result.resolvedTypes;
      }
    }
    return null;
  }

  /**
   * Caches the result of a "describe statement" round trip for {@link #getCachedDescribeResult}.
   * A repeated describe with the same request types replaces the old result, so a re-describe
   * (e.g. after DDL changed the inferred types) refreshes the cache. The least recently used
   * result is evicted when the cache is full.
   *
   * <p>Unlike the server-prepared statement state, the cached results survive
   * {@link #unprepare()}: they capture how the server resolves the types for this SQL text,
   * not the state of a server-side statement.</p>
   *
   * <p>The results are stamped with the given {@code deallocateEpoch} and are discarded once it
   * changes, which covers the events after which the server may resolve the types differently
   * (DDL, {@code SET search_path}). The epoch also changes on {@code DEALLOCATE ALL}, which the
   * results would survive; dropping them there merely costs one extra describe.</p>
   *
   * @param requestTypes parameter type OIDs sent in the Parse message, cloned by the caller
   * @param resolvedTypes parameter type OIDs from the ParameterDescription response, cloned by
   *                      the caller
   * @param deallocateEpoch the connection's deallocate epoch the describe was answered at
   */
  void addDescribeResult(int[] requestTypes, int[] resolvedTypes, short deallocateEpoch) {
    @Nullable DescribeResult[] results = describeResults;
    if (results == null || describeResultsEpoch != deallocateEpoch) {
      this.describeResults = results = new DescribeResult[MAX_CACHED_DESCRIBE_RESULTS];
      this.describeResultsEpoch = deallocateEpoch;
    }
    int insertionPoint = results.length - 1;
    for (int i = 0; i < results.length; i++) {
      @Nullable DescribeResult result = results[i];
      if (result == null || Arrays.equals(result.requestTypes, requestTypes)) {
        insertionPoint = i;
        break;
      }
    }
    System.arraycopy(results, 0, results, 1, insertionPoint);
    results[0] = new DescribeResult(requestTypes, resolvedTypes);
  }

  /**
   * The result of a single "describe statement" request: the parameter types the driver sent in
   * the Parse message, and the types the server resolved them to.
   */
  private static final class DescribeResult {
    final int[] requestTypes;
    final int[] resolvedTypes;

    DescribeResult(int[] requestTypes, int[] resolvedTypes) {
      this.requestTypes = requestTypes;
      this.resolvedTypes = resolvedTypes;
    }

    boolean matches(int[] paramTypes) {
      int[] resolvedTypes = this.resolvedTypes;
      if (paramTypes.length != resolvedTypes.length) {
        return false;
      }
      // Same compatibility rule as isPreparedFor: a set type must match the resolved type,
      // and an unset type is compatible only when the describe request had it unset as well
      for (int i = 0; i < paramTypes.length; i++) {
        int paramType = paramTypes[i];
        if (paramType != resolvedTypes[i]
            && (paramType != Oid.UNSPECIFIED || requestTypes[i] != Oid.UNSPECIFIED)) {
          return false;
        }
      }
      return true;
    }
  }

  /**
   * Reports the parameter type arrays, whose size follows the parameter count rather than the
   * length of the SQL text. A query with many parameters retains far more than its text suggests:
   * the prepared types, plus two arrays per cached describe result.
   */
  @Override
  public long getRetainedSizeExcludingSql() {
    long size = 0;
    int @Nullable [] preparedTypes = handle.getPrepareTypes();
    if (preparedTypes != null) {
      size += 4L * preparedTypes.length;
    }
    @Nullable DescribeResult[] results = describeResults;
    if (results != null) {
      for (@Nullable DescribeResult result : results) {
        if (result == null) {
          // The results are packed, so the first empty slot ends the used ones
          break;
        }
        size += 4L * (result.requestTypes.length + result.resolvedTypes.length);
      }
    }
    return size;
  }

  byte @Nullable [] getEncodedStatementName() {
    return handle.getEncodedStatementName();
  }

  /**
   * Sets the fields that this query will return.
   *
   * @param fields The fields that this query will return.
   */
  void setFields(Field @Nullable [] fields) {
    handle.setFields(fields);
  }

  /**
   * Returns the fields that this query will return. If the result set fields are not known returns
   * null.
   *
   * @return the fields that this query will return.
   */
  Field @Nullable [] getFields() {
    return handle.getFields();
  }

  /**
   * Returns true if current query needs field formats be adjusted as per connection configuration.
   * Subsequent invocations would return {@code false}. The idea is to perform adjustments only
   * once, not for each
   * {@link QueryExecutorImpl#sendBind(SimpleQuery, SimpleParameterList, Portal, boolean)}.
   *
   * @return true if current query needs field formats be adjusted as per connection configuration
   */
  boolean needUpdateFieldFormats() {
    return handle.needUpdateFieldFormats();
  }

  public void resetNeedUpdateFieldFormats() {
    handle.resetNeedUpdateFieldFormats();
  }

  public boolean hasBinaryFields() {
    return handle.hasBinaryFields();
  }

  public void setHasBinaryFields(boolean hasBinaryFields) {
    handle.setHasBinaryFields(hasBinaryFields);
  }

  // Have we sent a Describe Statement message for this query yet, on either the named or the
  // unnamed statement? Note that we might not have need to, so this may always be false.
  // QueryExecutor.isStatementDescribed(Query, ParameterList, int) is the epoch- and
  // parameter-aware check; this method is the parameter-agnostic approximation.
  @Override
  public boolean isStatementDescribed() {
    ServerHandle unnamedHandle = this.unnamedHandle;
    return handle.isStatementDescribed()
        || (unnamedHandle != null && unnamedHandle.isStatementDescribed());
  }

  @Override
  public boolean isEmpty() {
    return getNativeSql().isEmpty();
  }

  void setCleanupRef(PhantomReference<?> cleanupRef) {
    handle.setCleanupRef(cleanupRef);
  }

  void unprepare() {
    handle.unprepare();
  }

  @Override
  public int getBatchSize() {
    return 1;
  }

  NativeQuery getNativeQuery() {
    return nativeQuery;
  }

  public final int getBindCount() {
    return nativeQuery.bindPositions.length * getBatchSize();
  }

  @Override
  public @Nullable Map<String, Integer> getResultSetColumnNameIndexMap() {
    return handle.getResultSetColumnNameIndexMap(sanitiserDisabled);
  }

  @Override
  public SqlCommand getSqlCommand() {
    return nativeQuery.getCommand();
  }

  private final NativeQuery nativeQuery;

  private final @Nullable TypeTransferModeRegistry transferModeRegistry;
  private final boolean sanitiserDisabled;

  /**
   * The server-side prepared statement backing this query, including its describe results. The
   * handle instance is permanent for now; {@link ServerHandle#unprepare()} resets its state.
   */
  private final ServerHandle handle = new ServerHandle();

  /**
   * Statement state for one-shot executions that cannot reuse the named statement. Lazily
   * allocated; see {@link #getUnnamedHandle()}.
   */
  private @Nullable ServerHandle unnamedHandle;

  /**
   * Maximum number of cached "describe statement" results per query. Real workloads use one or
   * two type patterns per SQL text (no types set, plus the application's usual typed pattern),
   * so a few slots avoid cache thrashing without a per-connection memory cost worth accounting.
   */
  private static final int MAX_CACHED_DESCRIBE_RESULTS = 4;

  /**
   * Cached "describe statement" results in most-recently-used order, lazily allocated,
   * {@code null}-padded at the tail. See {@link #getCachedDescribeResult(int[], short)}.
   */
  private @Nullable DescribeResult @Nullable [] describeResults;

  /**
   * The {@code deallocateEpoch} the cached {@link #describeResults} were captured at. All the
   * cached results share it, since a bump discards them all at once.
   */
  private short describeResultsEpoch;

  static final SimpleParameterList NO_PARAMETERS = new SimpleParameterList(0, null);
}
