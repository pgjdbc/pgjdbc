/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.SqlCommand;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;

/**
 * V3 Query implementation for queries that involve multiple statements. We split it up into one
 * SimpleQuery per statement, and wrap the corresponding per-statement SimpleParameterList objects
 * in a CompositeParameterList.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
class CompositeQuery implements Query {
  CompositeQuery(SimpleQuery[] subqueries, int[] offsets) {
    this.subqueries = subqueries;
    this.offsets = offsets;
  }

  @Override
  public ParameterList createParameterList() {
    SimpleParameterList[] subparams = new SimpleParameterList[subqueries.length];
    for (int i = 0; i < subqueries.length; i++) {
      subparams[i] = (SimpleParameterList) subqueries[i].createParameterList();
    }
    return new CompositeParameterList(subparams, offsets);
  }

  @Override
  public String toString(@Nullable ParameterList parameters) {
    return toString(parameters, DefaultSqlSerializationContext.STDSTR_IDEMPOTENT);
  }

  @Override
  public String toString(@Nullable ParameterList parameters, SqlSerializationContext context) {
    SimpleParameterList[] subparams =
        parameters == null ? null : ((V3ParameterList) parameters).getSubparams();
    StringBuilder sbuf = new StringBuilder(
        subqueries[0].toString(subparams == null ? null : subparams[0], context));
    for (int i = 1; i < subqueries.length; i++) {
      sbuf.append(';');
      sbuf.append(subqueries[i].toString(subparams == null ? null : subparams[i], context));
    }
    return sbuf.toString();
  }

  @Override
  public String getNativeSql() {
    StringBuilder sbuf = new StringBuilder(subqueries[0].getNativeSql());
    for (int i = 1; i < subqueries.length; i++) {
      sbuf.append(';');
      sbuf.append(subqueries[i].getNativeSql());
    }
    return sbuf.toString();
  }

  @Override
  public @Nullable SqlCommand getSqlCommand() {
    return null;
  }

  @Override
  public String toString() {
    return toString(null);
  }

  @Override
  public void close() {
    for (SimpleQuery subquery : subqueries) {
      subquery.close();
    }
  }

  @Override
  public Query[] getSubqueries() {
    return subqueries;
  }

  @Override
  public boolean isStatementDescribed() {
    for (SimpleQuery subquery : subqueries) {
      if (!subquery.isStatementDescribed()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public boolean isEmpty() {
    for (SimpleQuery subquery : subqueries) {
      if (!subquery.isEmpty()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int getBatchSize() {
    return 0; // no-op, unsupported
  }

  @Override
  public @Nullable Map<String, Integer> getResultSetColumnNameIndexMap() {
    return null; // unsupported
  }

  private final SimpleQuery[] subqueries;
  private final int[] offsets;
}
