/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Batch handler for {@code CallableStatement}, which never collects generated keys.
 *
 * <p>Rows a call returns are discarded by {@link BatchResultHandler} itself when generated keys are
 * not expected, so the sub-statement bookkeeping in the superclass is what a callable batch needs
 * too: a call that returns rows still ends a sub-statement.</p>
 */
class CallableBatchResultHandler extends BatchResultHandler {
  CallableBatchResultHandler(PgStatement statement, Query[] queries,
      @Nullable ParameterList[] parameterLists) {
    super(statement, queries, parameterLists, false);
  }
}
