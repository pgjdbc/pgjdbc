/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgResultSet;
import org.postgresql.jdbc.TypeInfoCache;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.lang.ref.PhantomReference;
import java.nio.charset.StandardCharsets;
import java.util.BitSet;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The state of one server-side prepared statement backing a {@link SimpleQuery}: the statement
 * name, the parameter types it was parsed with, and the describe results (row description, format
 * flags, cached row-size estimate) obtained for it.
 *
 * <p>All of this state is bound to a single backend session and dies with the statement. What a
 * query knows about the SQL text itself (the native SQL, bind positions, the describe-result
 * cache for {@code getParameterMetaData()}) stays on {@link SimpleQuery}.</p>
 */
class ServerHandle {
  private static final Logger LOGGER = Logger.getLogger(ServerHandle.class.getName());

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
  private @Nullable PhantomReference<?> cleanupRef;
  private int @Nullable [] preparedTypes;
  private @Nullable BitSet unspecifiedParams;
  private short deallocateEpoch;
  /** The epoch {@link #markStatementDescribed(short)} last ran at; see {@link #isStatementDescribedAt(short)}. */
  private short describedEpoch;
  private @Nullable Integer cachedMaxResultRowSize;
  private @Nullable Map<String, Integer> resultSetColumnNameIndexMap;
  /**
   * Number of open portals bound from this statement. The backend closes all dependent portals
   * when a statement is closed, so the statement must not be evicted and closed while pinned.
   * Mutated only under the executor's connection lock.
   */
  private int pinCount;
  /** See {@link #closeWhenUnpinned()}. */
  private boolean closeWhenUnpinned;

  void setStatementName(String statementName, short deallocateEpoch) {
    assert statementName != null : "statement name should not be null";
    this.statementName = statementName;
    this.encodedStatementName = statementName.getBytes(StandardCharsets.UTF_8);
    this.deallocateEpoch = deallocateEpoch;
  }

  @Nullable String getStatementName() {
    return statementName;
  }

  byte @Nullable [] getEncodedStatementName() {
    return encodedStatementName;
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

  boolean isPreparedFor(int[] paramTypes, short deallocateEpoch) {
    return isPreparedFor(paramTypes, deallocateEpoch, true);
  }

  /**
   * Returns true if this statement can be reused as-is for the given parameter types at the given
   * epoch. Pass {@code logMismatch = false} for speculative probes (handle resolution, variant
   * scans, pre-describe gates), so the "will have to un-prepare" diagnostics fire once per
   * execution, from the send path that acts on the mismatch.
   *
   * @param paramTypes parameter type OIDs of the upcoming execution
   * @param deallocateEpoch the connection's current deallocate epoch
   * @param logMismatch whether a type mismatch is worth a FINER diagnostic
   * @return true if this statement can be reused as-is
   */
  boolean isPreparedFor(int[] paramTypes, short deallocateEpoch, boolean logMismatch) {
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
        if (logMismatch && LOGGER.isLoggable(Level.FINER)) {
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

  void setFields(Field @Nullable [] fields) {
    this.fields = fields;
    this.resultSetColumnNameIndexMap = null;
    this.cachedMaxResultRowSize = null;
    this.needUpdateFieldFormats = fields != null;
    this.hasBinaryFields = false; // just in case
  }

  Field @Nullable [] getFields() {
    return fields;
  }

  boolean needUpdateFieldFormats() {
    if (needUpdateFieldFormats) {
      needUpdateFieldFormats = false;
      return true;
    }
    return false;
  }

  void resetNeedUpdateFieldFormats() {
    needUpdateFieldFormats = fields != null;
  }

  boolean hasBinaryFields() {
    return hasBinaryFields;
  }

  void setHasBinaryFields(boolean hasBinaryFields) {
    this.hasBinaryFields = hasBinaryFields;
  }

  boolean isPortalDescribed() {
    return portalDescribed;
  }

  void setPortalDescribed(boolean portalDescribed) {
    this.portalDescribed = portalDescribed;
    this.cachedMaxResultRowSize = null;
  }

  boolean isStatementDescribed() {
    return statementDescribed;
  }

  /**
   * Returns true if the statement is described and the describe happened at the given epoch.
   * After an epoch bump (DDL, {@code SET search_path}, {@code DEALLOCATE ALL}) the server may
   * resolve the query differently, so older describe results must not gate away a re-describe.
   *
   * @param deallocateEpoch the connection's current deallocate epoch
   * @return true if the statement is described and the describe is current
   */
  boolean isStatementDescribedAt(short deallocateEpoch) {
    return statementDescribed && describedEpoch == deallocateEpoch;
  }

  void markStatementDescribed(short deallocateEpoch) {
    this.statementDescribed = true;
    this.describedEpoch = deallocateEpoch;
    this.cachedMaxResultRowSize = null;
  }

  void setStatementDescribed(boolean statementDescribed) {
    this.statementDescribed = statementDescribed;
    this.cachedMaxResultRowSize = null;
  }

  /**
   * Return maximum size in bytes that each result row from this statement may return. Mainly used
   * for batches that return results.
   *
   * <p>Results are cached until/unless the statement is re-described.</p>
   *
   * @return Max size of result data in bytes according to returned fields, 0 if no results, -1 if
   *         result is unbounded.
   * @throws IllegalStateException if the statement is not described
   */
  int getMaxResultRowSize() {
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

  @Nullable Map<String, Integer> getResultSetColumnNameIndexMap(boolean sanitiserDisabled) {
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

  void pin() {
    pinCount++;
  }

  void unpin() {
    assert pinCount > 0 : "unpin() without a matching pin() on " + this;
    pinCount--;
    if (pinCount == 0 && closeWhenUnpinned) {
      closeWhenUnpinned = false;
      unprepare();
    }
  }

  boolean isPinned() {
    return pinCount > 0;
  }

  /**
   * Returns true if this statement was prepared at an older deallocate epoch and can no longer be
   * reused; its describe results and plan are suspect after DDL, {@code SET search_path}, or
   * {@code DEALLOCATE ALL}.
   *
   * @param deallocateEpoch the connection's current deallocate epoch
   * @return true if this statement is named and outdated
   */
  boolean isStale(short deallocateEpoch) {
    return statementName != null && this.deallocateEpoch != deallocateEpoch;
  }

  /**
   * Closes the server statement as soon as no portal depends on it: immediately when unpinned,
   * otherwise at the {@link #unpin()} that releases the last portal. The backend closes all
   * dependent portals when a statement is closed, so an evicted-but-pinned statement must outlive
   * its cursors ("deferred close").
   */
  void closeWhenUnpinned() {
    if (pinCount == 0) {
      unprepare();
    } else {
      closeWhenUnpinned = true;
    }
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
    // The close just happened (or is queued); nothing is pending for the last unpin anymore,
    // and a later re-prepare of this slot must not be closed by an old portal's unpin.
    closeWhenUnpinned = false;

    statementName = null;
    encodedStatementName = null;
    fields = null;
    this.resultSetColumnNameIndexMap = null;
    portalDescribed = false;
    statementDescribed = false;
    cachedMaxResultRowSize = null;
  }

  @Override
  public String toString() {
    return "ServerHandle{" + statementName + '}';
  }
}
