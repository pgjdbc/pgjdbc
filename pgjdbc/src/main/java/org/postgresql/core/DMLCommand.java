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
    return batchedReWriteCompatible;
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
    parsedSQLhasRETURNINGKeyword = isPresent;
    batchedReWriteCompatible = (type == INSERT) && isBatchedReWriteConfigured
        && isCompatible && !isautocommitConfigured
        && !isPresent && priorQueryCount == 0;
  }

  private final DMLCommandType commandType;
  private final boolean parsedSQLhasRETURNINGKeyword;
  private final boolean batchedReWriteCompatible;
}
