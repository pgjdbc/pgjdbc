/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.Provider;
import org.postgresql.core.QueryExecutor;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Shared helper for lazy TimestampUtils creation and default Calendar management.
 *
 * <p>Both {@code PgResultSet} and {@code PgStatement} need per-instance
 * {@link TimestampUtils} (not thread-safe) and cached default timezone logic.
 * This class eliminates that duplication.</p>
 */
final class DateTimeHelper {

  private final QueryExecutor queryExecutor;
  private @Nullable TimestampUtils timestampUtils;
  private @Nullable TimeZone defaultTimeZone;

  DateTimeHelper(QueryExecutor queryExecutor) {
    this.queryExecutor = queryExecutor;
  }

  /**
   * Returns the TimestampUtils instance, creating it lazily.
   * Each helper instance has its own TimestampUtils since it is not thread-safe.
   */
  TimestampUtils getTimestampUtils() {
    if (timestampUtils == null) {
      timestampUtils = new TimestampUtils(
          !queryExecutor.getIntegerDateTimes(),
          (Provider<TimeZone>) new QueryExecutorTimeZoneProvider(queryExecutor)
      );
    }
    return timestampUtils;
  }

  /**
   * Returns a Calendar using the default timezone, with caching to avoid
   * repeated TimeZone lookups.
   */
  Calendar getDefaultCalendar() {
    if (getTimestampUtils().hasFastDefaultTimeZone()) {
      return getTimestampUtils().getSharedCalendar(null);
    }
    Calendar sharedCalendar = getTimestampUtils().getSharedCalendar(defaultTimeZone);
    if (defaultTimeZone == null) {
      defaultTimeZone = sharedCalendar.getTimeZone();
    }
    return sharedCalendar;
  }

  /**
   * Resets the cached default timezone. Called after statement execution
   * in case the server timezone changed during execution.
   */
  void resetDefaultTimeZone() {
    defaultTimeZone = null;
  }
}
