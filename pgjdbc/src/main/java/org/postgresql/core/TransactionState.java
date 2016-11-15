/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

public enum TransactionState {
  IDLE,
  OPEN,
  FAILED
}
