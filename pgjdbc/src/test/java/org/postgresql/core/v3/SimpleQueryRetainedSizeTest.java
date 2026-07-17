/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.CachedQuery;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.SqlCommand;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

/**
 * The statement cache budgets memory in bytes, so a query must report the parameter type arrays it
 * retains. They follow the parameter count, which the SQL text alone does not reveal.
 */
class SimpleQueryRetainedSizeTest {
  private static final int PARAMS = 1000;

  private static SimpleQuery newQuery() {
    int[] bindPositions = new int[PARAMS];
    return new SimpleQuery(
        new NativeQuery("SELECT 1", bindPositions, false, SqlCommand.BLANK), null, false);
  }

  private static int[] types(int oid) {
    int[] types = new int[PARAMS];
    Arrays.fill(types, oid);
    return types;
  }

  @Test
  void freshQueryRetainsNothingBeyondSql() {
    assertEquals(0, newQuery().getRetainedSizeExcludingSql(),
        "a query that was never described retains no parameter type arrays");
  }

  @Test
  void describeResultsAreReported() {
    SimpleQuery query = newQuery();
    query.addDescribeResult(types(Oid.UNSPECIFIED), types(Oid.INT4), (short) 0);

    assertEquals(2 * 4L * PARAMS, query.getRetainedSizeExcludingSql(),
        "one describe result retains the request and the resolved types");
  }

  @Test
  void everyCachedResultIsReported() {
    SimpleQuery query = newQuery();
    // Distinct request types, so the results do not replace each other
    query.addDescribeResult(types(Oid.UNSPECIFIED), types(Oid.INT4), (short) 0);
    query.addDescribeResult(types(Oid.INT4), types(Oid.INT4), (short) 0);

    assertEquals(4 * 4L * PARAMS, query.getRetainedSizeExcludingSql(),
        "both cached results are accounted for");
  }

  @Test
  void cachedQuerySizeGrowsWithDescribeResults() {
    SimpleQuery query = newQuery();
    CachedQuery cachedQuery = new CachedQuery("SELECT 1", query, false);
    long before = cachedQuery.getSize();

    query.addDescribeResult(types(Oid.UNSPECIFIED), types(Oid.INT4), (short) 0);

    assertEquals(before + 2 * 4L * PARAMS, cachedQuery.getSize(),
        "the cache budget sees the describe result, not just the SQL text");
  }
}
