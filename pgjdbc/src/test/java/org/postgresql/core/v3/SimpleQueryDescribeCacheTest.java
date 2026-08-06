/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.core.NativeQuery;
import org.postgresql.core.Oid;
import org.postgresql.core.SqlCommand;

import org.junit.jupiter.api.Test;

/**
 * Covers the arity guard of the describe result cache. A {@code SimpleQuery} binds a fixed number
 * of parameters, so JDBC callers cannot reach a lookup of a different length. The guard still earns
 * its place: without it a shorter request would match on a prefix and resolve to types that belong
 * to another parameter, and a longer one would read past the cached array.
 */
class SimpleQueryDescribeCacheTest {
  private static final short EPOCH = 0;

  private static SimpleQuery newQuery() {
    return new SimpleQuery(
        new NativeQuery("SELECT $1, $2", new int[]{7, 11}, false, SqlCommand.BLANK), null, false);
  }

  private static SimpleQuery queryDescribedForTwoParameters() {
    SimpleQuery query = newQuery();
    query.addDescribeResult(new int[]{Oid.UNSPECIFIED, Oid.UNSPECIFIED},
        new int[]{Oid.INT4, Oid.TEXT}, EPOCH);
    return query;
  }

  @Test
  void resultIsReusedForTheSameParameterCount() {
    SimpleQuery query = queryDescribedForTwoParameters();

    assertArrayEquals(new int[]{Oid.INT4, Oid.TEXT},
        query.getCachedDescribeResult(new int[]{Oid.UNSPECIFIED, Oid.UNSPECIFIED}, EPOCH),
        "the describe result resolves the types it was captured for");
  }

  @Test
  void resultIsNotReusedForFewerParameters() {
    SimpleQuery query = queryDescribedForTwoParameters();

    assertNull(query.getCachedDescribeResult(new int[]{Oid.UNSPECIFIED}, EPOCH),
        "a result described for two parameters must not resolve one parameter from its prefix");
  }

  @Test
  void resultIsNotReusedForMoreParameters() {
    SimpleQuery query = queryDescribedForTwoParameters();

    assertNull(
        query.getCachedDescribeResult(
            new int[]{Oid.UNSPECIFIED, Oid.UNSPECIFIED, Oid.UNSPECIFIED}, EPOCH),
        "a result described for two parameters must not be read past its end");
  }
}
