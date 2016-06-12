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
 * @author Christopher Deckers (chrriis@gmail.com)
 *
 */
public class DMLCommand {

  public boolean isBatchedReWriteCompatible() {
    return valuesBraceOpenPosition >= 0;
  }

  public int getBatchRewriteValuesBraceOpenPosition() {
    return valuesBraceOpenPosition;
  }

  public int getBatchRewriteValuesBraceClosePosition() {
    return valuesBraceClosePosition;
  }

  public DMLCommandType getType() {
    return commandType;
  }

  public boolean isReturningKeywordPresent() {
    return parsedSQLhasRETURNINGKeyword;
  }

  public static DMLCommand createStatementTypeInfo(DMLCommandType type,
      boolean isBatchedReWritePropertyConfigured,
      int valuesBraceOpenPosition, int valuesBraceClosePosition, boolean isRETURNINGkeywordPresent,
      int priorQueryCount) {
    return new DMLCommand(type, isBatchedReWritePropertyConfigured,
        valuesBraceOpenPosition, valuesBraceClosePosition, isRETURNINGkeywordPresent,
        priorQueryCount);
  }

  public static DMLCommand createStatementTypeInfo(DMLCommandType type) {
    return new DMLCommand(type, false, -1, -1, false, 0);
  }

  public static DMLCommand createStatementTypeInfo(DMLCommandType type,
      boolean isRETURNINGkeywordPresent) {
    return new DMLCommand(type, false, -1, -1, isRETURNINGkeywordPresent, 0);
  }

  private DMLCommand(DMLCommandType type, boolean isBatchedReWriteConfigured,
      int valuesBraceOpenPosition, int valuesBraceClosePosition, boolean isPresent,
      int priorQueryCount) {
    commandType = type;
    parsedSQLhasRETURNINGKeyword = isPresent;
    boolean batchedReWriteCompatible = (type == INSERT) && isBatchedReWriteConfigured
        && valuesBraceOpenPosition >= 0 && valuesBraceClosePosition > valuesBraceOpenPosition
        && !isPresent && priorQueryCount == 0;
    this.valuesBraceOpenPosition = batchedReWriteCompatible ? valuesBraceOpenPosition : -1;
    this.valuesBraceClosePosition = batchedReWriteCompatible ? valuesBraceClosePosition : -1;
  }

  private final DMLCommandType commandType;
  private final boolean parsedSQLhasRETURNINGKeyword;
  private final int valuesBraceOpenPosition;
  private final int valuesBraceClosePosition;

}
