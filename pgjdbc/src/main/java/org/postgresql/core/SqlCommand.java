/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core;

/**
 * Data Modification Language inspection support.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */
public class SqlCommand {

  public boolean isBatchedReWriteCompatible() {
    return batchedReWriteCompatible;
  }

  public SqlCommandType getType() {
    return commandType;
  }

  public boolean isReturningKeywordPresent() {
    return parsedSQLhasRETURNINGKeyword;
  }

  public static SqlCommand createStatementTypeInfo(SqlCommandType type,
      boolean isBatchedReWritePropertyConfigured,
      boolean isBatchedReWriteCompatible, boolean isRETURNINGkeywordPresent,
      boolean autocommit, int priorQueryCount) {
    return new SqlCommand(type, isBatchedReWritePropertyConfigured,
        isBatchedReWriteCompatible, isRETURNINGkeywordPresent, autocommit,
        priorQueryCount);
  }

  public static SqlCommand createStatementTypeInfo(SqlCommandType type) {
    return new SqlCommand(type, false, false, false, false,0);
  }

  public static SqlCommand createStatementTypeInfo(SqlCommandType type,
      boolean isRETURNINGkeywordPresent) {
    return new SqlCommand(type, false, false, isRETURNINGkeywordPresent, false,0);
  }

  private SqlCommand(SqlCommandType type, boolean isBatchedReWriteConfigured,
      boolean isCompatible, boolean isPresent, boolean isautocommitConfigured,
      int priorQueryCount) {
    commandType = type;
    parsedSQLhasRETURNINGKeyword = isPresent;
    batchedReWriteCompatible = type.canSupportBatchedReWrite() && isBatchedReWriteConfigured
        && isCompatible && !isautocommitConfigured
        && !isPresent && priorQueryCount == 0;
  }

  private final SqlCommandType commandType;
  private final boolean parsedSQLhasRETURNINGKeyword;
  private final boolean batchedReWriteCompatible;
}
