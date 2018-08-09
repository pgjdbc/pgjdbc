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
import org.postgresql.core.Utils;
import org.postgresql.jdbc.PgResultSet;

import java.lang.ref.PhantomReference;
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

  SimpleQuery(NativeQuery query, TypeTransferModeRegistry transferModeRegistry,
      boolean sanitiserDisabled) {
    this.nativeQuery = query;
    this.transferModeRegistry = transferModeRegistry;
    this.sanitiserDisabled = sanitiserDisabled;
  }

  public ParameterList createParameterList() {
    if (nativeQuery.bindPositions.length == 0) {
      return NO_PARAMETERS;
    }

    return new SimpleParameterList(getBindCount(), transferModeRegistry);
  }

  public String toString(ParameterList parameters) {
    return nativeQuery.toString(parameters);
  }

  public String toString() {
    return toString(null);
  }

  public void close() {
    unprepare();
  }

  public SimpleQuery[] getSubqueries() {
    return null;
  }

  /**
   * <p>Return maximum size in bytes that each result row from this query may return. Mainly used for
   * batches that return results.</p>
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
        final int fieldLength = f.getLength();
        if (fieldLength < 1 || fieldLength >= 65535) {
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

  public String getNativeSql() {
    return nativeQuery.nativeSql;
  }

  void setStatementName(String statementName, short deallocateEpoch) {
    assert statementName != null : "statement name should not be null";
    this.statementName = statementName;
    this.encodedStatementName = Utils.encodeUTF8(statementName);
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

  int[] getPrepareTypes() {
    return preparedTypes;
  }

  String getStatementName() {
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
    for (int i = 0; i < paramTypes.length; ++i) {
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

  byte[] getEncodedStatementName() {
    return encodedStatementName;
  }

  /**
   * Sets the fields that this query will return.
   *
   * @param fields The fields that this query will return.
   */
  void setFields(Field[] fields) {
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
  Field[] getFields() {
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
  public boolean isStatementDescribed() {
    return statementDescribed;
  }

  void setStatementDescribed(boolean statementDescribed) {
    this.statementDescribed = statementDescribed;
    this.cachedMaxResultRowSize = null;
  }

  public boolean isEmpty() {
    return getNativeSql().isEmpty();
  }

  void setCleanupRef(PhantomReference<?> cleanupRef) {
    if (this.cleanupRef != null) {
      this.cleanupRef.clear();
      this.cleanupRef.enqueue();
    }
    this.cleanupRef = cleanupRef;
  }

  void unprepare() {
    if (cleanupRef != null) {
      cleanupRef.clear();
      cleanupRef.enqueue();
      cleanupRef = null;
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

  public int getBatchSize() {
    return 1;
  }

  NativeQuery getNativeQuery() {
    return nativeQuery;
  }

  public final int getBindCount() {
    return nativeQuery.bindPositions.length * getBatchSize();
  }

  private Map<String, Integer> resultSetColumnNameIndexMap;

  @Override
  public Map<String, Integer> getResultSetColumnNameIndexMap() {
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

  private final TypeTransferModeRegistry transferModeRegistry;
  private String statementName;
  private byte[] encodedStatementName;
  /**
   * The stored fields from previous execution or describe of a prepared statement. Always null for
   * non-prepared statements.
   */
  private Field[] fields;
  private boolean needUpdateFieldFormats;
  private boolean hasBinaryFields;
  private boolean portalDescribed;
  private boolean statementDescribed;
  private final boolean sanitiserDisabled;
  private PhantomReference<?> cleanupRef;
  private int[] preparedTypes;
  private BitSet unspecifiedParams;
  private short deallocateEpoch;

  private Integer cachedMaxResultRowSize;

  static final SimpleParameterList NO_PARAMETERS = new SimpleParameterList(0, null);
}
