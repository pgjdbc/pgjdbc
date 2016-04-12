/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core;

/**
 * Type information inspection support.
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */

public enum DMLCommandType {
  INSERT(true),
  /**
   * Use BLANK for empty sql queries or when parsing the sql string is not
   * necessary.
   */
  BLANK(false);

  /* to be added when needed SELECT(false), DELETE(false), UPDATE(false),
  * COMMIT(false), ROLLBACK(false); */

  public boolean canSupportBatchedReWrite() {
    return canSupportBatchedReWrite;
  }

  private final boolean canSupportBatchedReWrite;

  private DMLCommandType(boolean reWriteSupport) {
    canSupportBatchedReWrite = reWriteSupport;
  }

}
