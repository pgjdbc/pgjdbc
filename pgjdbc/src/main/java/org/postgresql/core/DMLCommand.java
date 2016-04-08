/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core;

import static org.postgresql.core.DMLCommandType.INSERT;

/**
 * Data Modification Language inspection support.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */
public class DMLCommand {

  public boolean isBatchedReWriteCompatible() {
    return commandType == INSERT && batchedReWriteConfigured
      && parsedSQLIsBatchedReWriteCompatible && !autoCommit
      && !parsedSQLhasRETURNINGKeyword && count == 0;
  }

  public DMLCommandType getType() {
    return commandType;
  }

  public boolean isReturningKeywordPresent() {
    return parsedSQLhasRETURNINGKeyword;
  }

  public static DMLCommand createStatementTypeInfo(DMLCommandType type,
      boolean isBatchedReWritePropertyConfigured,
      boolean isBatchedReWriteCompatible, boolean isRETURNINGkeywordPresent,
      boolean autocommit, int priorQueryCount) {
    return new DMLCommand(type, isBatchedReWritePropertyConfigured,
        isBatchedReWriteCompatible, isRETURNINGkeywordPresent, autocommit,
        priorQueryCount);
  }

  public static DMLCommand createStatementTypeInfo(DMLCommandType type) {
    return new DMLCommand(type, false, false, false, false,0);
  }

  public static DMLCommand createStatementTypeInfo(DMLCommandType type,
      boolean isRETURNINGkeywordPresent) {
    return new DMLCommand(type, false, false, isRETURNINGkeywordPresent, false,0);
  }

  private DMLCommand(DMLCommandType type, boolean isBatchedReWriteConfigured,
      boolean isCompatible, boolean isPresent, boolean isautocommitConfigured,
      int priorQueryCount) {
    commandType = type;
    batchedReWriteConfigured = isBatchedReWriteConfigured;
    parsedSQLIsBatchedReWriteCompatible = isCompatible;
    parsedSQLhasRETURNINGKeyword = isPresent;
    autoCommit = isautocommitConfigured;
    count = priorQueryCount;
  }

  private final DMLCommandType commandType;
  private final boolean batchedReWriteConfigured;
  private final boolean parsedSQLIsBatchedReWriteCompatible;
  private final boolean autoCommit;
  private final boolean parsedSQLhasRETURNINGKeyword;
  private final int count;
}
