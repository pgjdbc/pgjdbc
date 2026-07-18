/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import org.postgresql.api.Experimental;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The backend's {@code IntervalStyle} setting, which selects how the server renders {@code interval}
 * values in text. It is reported to the client as a {@code ParameterStatus} (GUC_REPORT) since
 * PostgreSQL 8.4, so a codec can read the current value from {@link CodecContext#getIntervalStyle()}
 * and render a binary {@code interval} the same way the server would have in text mode.
 *
 * @see CodecContext#getIntervalStyle()
 * @since 42.8.0
 */
@Experimental("Codec API is experimental and may change in future releases")
public enum IntervalStyle {
  /** The default: {@code 1 year 2 mons 3 days 04:05:06}, {@code -1 days +02:03:04}. */
  POSTGRES,
  /** The pre-8.4 verbose form: {@code @ 1 year 2 mons 3 days 4 hours 5 mins 6 secs}. */
  POSTGRES_VERBOSE,
  /** SQL-standard year-month / day-time literals: {@code +1-2 +3 +4:05:06}. */
  SQL_STANDARD,
  /** ISO 8601 duration: {@code P1Y2M3DT4H5M6S}. */
  ISO_8601;

  /**
   * Maps the raw {@code IntervalStyle} parameter-status value (as the backend reports it) to the enum.
   * An unknown or absent value falls back to {@link #POSTGRES}, matching the server default.
   *
   * @param value the backend's {@code IntervalStyle} value, or {@code null} if not reported
   * @return the matching style, never {@code null}
   */
  public static IntervalStyle fromServerValue(@Nullable String value) {
    if (value != null) {
      switch (value) {
        case "postgres_verbose":
          return POSTGRES_VERBOSE;
        case "sql_standard":
          return SQL_STANDARD;
        case "iso_8601":
          return ISO_8601;
        default:
          break;
      }
    }
    return POSTGRES;
  }
}
