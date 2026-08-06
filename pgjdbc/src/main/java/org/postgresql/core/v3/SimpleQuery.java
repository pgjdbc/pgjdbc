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
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.jdbc.TypeInfoCache;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.PhantomReference;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * V3 Query implementation for a single-statement query. This also holds the state of any associated
 * server-side named statement. We use a PhantomReference managed by the QueryExecutor to handle
 * statement cleanup.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class SimpleQuery implements Query {
  private static final Logger LOGGER = Logger.getLogger(SimpleQuery.class.getName());

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
    if (cachedMaxResultRowSize != null) {
      return cachedMaxResultRowSize;
    }
    if (!this.statementDescribed) {
      throw new IllegalStateException(
          "Cannot estimate result row size on a statement that is not described");
    }
    int maxResultRowSize = 0;
    if (fields != null) {
      for (Field f : fields) {
        final int fieldLength = TypeInfoCache.estimateMaxLength(f.getOID(), f.getLength(), f.getMod());
        if (fieldLength < 0) {
          /*
           * Field length unknown or large; we can't make any safe estimates about the result size,
           * so we have to fall back to sending queries individually.
           */
          maxResultRowSize = -1;
          break;
        }
        maxResultRowSize += fieldLength;
      }
    }
    cachedMaxResultRowSize = maxResultRowSize;
    return maxResultRowSize;
  }

  //
  // Implementation guts
  //

  @Override
  public String getNativeSql() {
    return nativeQuery.nativeSql;
  }

  void setStatementName(String statementName, short deallocateEpoch) {
    assert statementName != null : "statement name should not be null";
    this.statementName = statementName;
    this.encodedStatementName = statementName.getBytes(StandardCharsets.UTF_8);
    this.deallocateEpoch = deallocateEpoch;
  }

  void setPrepareTypes(int[] paramTypes) {
    // Remember which parameters were unspecified since the parameters will be overridden later by
    // ParameterDescription message
    for (int i = 0; i < paramTypes.length; i++) {
      int paramType = paramTypes[i];
      if (paramType == Oid.UNSPECIFIED) {
        if (this.unspecifiedParams == null) {
          this.unspecifiedParams = new BitSet();
        }
        this.unspecifiedParams.set(i);
      }
    }

    // paramTypes is changed by "describe statement" response, so we clone the array
    // However, we can reuse array if there is one
    if (this.preparedTypes == null) {
      this.preparedTypes = paramTypes.clone();
      return;
    }
    System.arraycopy(paramTypes, 0, this.preparedTypes, 0, paramTypes.length);
  }

  int @Nullable [] getPrepareTypes() {
    return preparedTypes;
  }

  @Nullable String getStatementName() {
    return statementName;
  }

  boolean isPreparedFor(int[] paramTypes, short deallocateEpoch) {
    if (statementName == null || preparedTypes == null) {
      return false; // Not prepared.
    }
    if (this.deallocateEpoch != deallocateEpoch) {
      return false;
    }

    assert paramTypes.length == preparedTypes.length
        : String.format("paramTypes:%1$d preparedTypes:%2$d", paramTypes.length,
        preparedTypes.length);
    // Check for compatible types.
    BitSet unspecified = this.unspecifiedParams;
    for (int i = 0; i < paramTypes.length; i++) {
      int paramType = paramTypes[i];
      // Either paramType should match prepared type
      // Or paramType==UNSPECIFIED and the prepare type was UNSPECIFIED

      // Note: preparedTypes can be updated by "statement describe"
      // 1) parse(name="S_01", sql="select ?::timestamp", types={UNSPECIFIED})
      // 2) statement describe: bind 1 type is TIMESTAMP
      // 3) SimpleQuery.preparedTypes is updated to TIMESTAMP
      // ...
      // 4.1) bind(name="S_01", ..., types={TIMESTAMP}) -> OK (since preparedTypes is equal to TIMESTAMP)
      // 4.2) bind(name="S_01", ..., types={UNSPECIFIED}) -> OK (since the query was initially parsed with UNSPECIFIED)
      // 4.3) bind(name="S_01", ..., types={DATE}) -> KO, unprepare and parse required

      int preparedType = preparedTypes[i];
      if (paramType != preparedType
          && (paramType != Oid.UNSPECIFIED
          || unspecified == null
          || !unspecified.get(i))) {
        if (LOGGER.isLoggable(Level.FINER)) {
          LOGGER.log(Level.FINER,
              "Statement {0} does not match new parameter types. Will have to un-prepare it and parse once again."
                  + " To avoid performance issues, use the same data type for the same bind position. Bind index (1-based) is {1},"
                  + " preparedType was {2} (after describe {3}), current bind type is {4}",
              new Object[]{statementName, i + 1,
                  Oid.toString(unspecified != null && unspecified.get(i) ? 0 : preparedType),
                  Oid.toString(preparedType), Oid.toString(paramType)});
        }
        return false;
      }
    }

    return true;
  }

  boolean hasUnresolvedTypes() {
    if (preparedTypes == null) {
      return true;
    }

    return this.unspecifiedParams != null && !this.unspecifiedParams.isEmpty();
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
    int[] preparedTypes = this.preparedTypes;
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
    return encodedStatementName;
  }

  /**
   * Sets the fields that this query will return.
   *
   * @param fields The fields that this query will return.
   */
  void setFields(Field @Nullable [] fields) {
    this.fields = fields;
    this.resultSetColumnNameIndexMap = null;
    this.cachedMaxResultRowSize = null;
    this.needUpdateFieldFormats = fields != null;
    this.hasBinaryFields = false; // just in case
  }

  /**
   * Returns the fields that this query will return. If the result set fields are not known returns
   * null.
   *
   * @return the fields that this query will return.
   */
  Field @Nullable [] getFields() {
    return fields;
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
    if (needUpdateFieldFormats) {
      needUpdateFieldFormats = false;
      return true;
    }
    return false;
  }

  public void resetNeedUpdateFieldFormats() {
    needUpdateFieldFormats = fields != null;
  }

  public boolean hasBinaryFields() {
    return hasBinaryFields;
  }

  public void setHasBinaryFields(boolean hasBinaryFields) {
    this.hasBinaryFields = hasBinaryFields;
  }

  // Have we sent a Describe Portal message for this query yet?
  boolean isPortalDescribed() {
    return portalDescribed;
  }

  void setPortalDescribed(boolean portalDescribed) {
    this.portalDescribed = portalDescribed;
    this.cachedMaxResultRowSize = null;
  }

  // Have we sent a Describe Statement message for this query yet?
  // Note that we might not have need to, so this may always be false.
  @Override
  public boolean isStatementDescribed() {
    return statementDescribed;
  }

  void setStatementDescribed(boolean statementDescribed) {
    this.statementDescribed = statementDescribed;
    this.cachedMaxResultRowSize = null;
  }

  @Override
  public boolean isEmpty() {
    return getNativeSql().isEmpty();
  }

  void setCleanupRef(PhantomReference<?> cleanupRef) {
    PhantomReference<?> oldCleanupRef = this.cleanupRef;
    if (oldCleanupRef != null) {
      oldCleanupRef.clear();
      oldCleanupRef.enqueue();
    }
    this.cleanupRef = cleanupRef;
  }

  void unprepare() {
    PhantomReference<?> cleanupRef = this.cleanupRef;
    if (cleanupRef != null) {
      cleanupRef.clear();
      cleanupRef.enqueue();
      this.cleanupRef = null;
    }
    if (this.unspecifiedParams != null) {
      this.unspecifiedParams.clear();
    }

    statementName = null;
    encodedStatementName = null;
    fields = null;
    this.resultSetColumnNameIndexMap = null;
    portalDescribed = false;
    statementDescribed = false;
    cachedMaxResultRowSize = null;
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

  private @Nullable Map<String, Integer> resultSetColumnNameIndexMap;

  @Override
  public @Nullable Map<String, Integer> getResultSetColumnNameIndexMap() {
    Map<String, Integer> columnPositions = this.resultSetColumnNameIndexMap;
    if (columnPositions == null && fields != null) {
      columnPositions =
          PgResultSet.createColumnNameIndexMap(fields, sanitiserDisabled);
      if (statementName != null) {
        // Cache column positions for server-prepared statements only
        this.resultSetColumnNameIndexMap = columnPositions;
      }
    }
    return columnPositions;
  }

  @Override
  public SqlCommand getSqlCommand() {
    return nativeQuery.getCommand();
  }

  private final NativeQuery nativeQuery;

  private final @Nullable TypeTransferModeRegistry transferModeRegistry;
  private @Nullable String statementName;
  private byte @Nullable [] encodedStatementName;
  /**
   * The stored fields from previous execution or describe of a prepared statement. Always null for
   * non-prepared statements.
   */
  private Field @Nullable [] fields;
  private boolean needUpdateFieldFormats;
  private boolean hasBinaryFields;
  private boolean portalDescribed;
  private boolean statementDescribed;
  private final boolean sanitiserDisabled;
  private @Nullable PhantomReference<?> cleanupRef;
  private int @Nullable [] preparedTypes;
  private @Nullable BitSet unspecifiedParams;
  private short deallocateEpoch;

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

  private @Nullable Integer cachedMaxResultRowSize;

  static final SimpleParameterList NO_PARAMETERS = new SimpleParameterList(0, null);
}
