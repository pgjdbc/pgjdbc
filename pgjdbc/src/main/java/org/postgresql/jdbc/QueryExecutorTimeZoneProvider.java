/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Provider;
import org.postgresql.core.QueryExecutor;
import org.postgresql.util.GT;

import java.util.TimeZone;

/**
 * This class workarounds <a href="https://github.com/wildfly/jandex/issues/93">Exception when
 * indexing guava-30.0-jre</a>.
 * <p>It looks like {@code jandex} does not support {@code new Interface<..>} with type annotations.
 * </p>
 */
class QueryExecutorTimeZoneProvider implements Provider<TimeZone> {
  private final QueryExecutor queryExecutor;

  QueryExecutorTimeZoneProvider(QueryExecutor queryExecutor) {
    this.queryExecutor = queryExecutor;
  }

  @Override
  public TimeZone get() {
    TimeZone timeZone = queryExecutor.getTimeZone();
    if (timeZone == null) {
      throw new IllegalStateException(
          GT.tr("Backend timezone is not known. Backend should have returned TimeZone when "
              + "establishing a connection")
      );
    }
    return timeZone;
  }
}
