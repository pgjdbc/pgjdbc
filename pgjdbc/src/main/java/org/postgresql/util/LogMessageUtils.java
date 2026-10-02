/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

/**
 * Helpers for keeping driver log output bounded.
 *
 * <p>FINEST protocol dumps (especially Bind with many or large parameters) can allocate enormous
 * strings and cause {@link OutOfMemoryError}. These utilities cap message length while preserving a
 * readable prefix of the original content.</p>
 *
 * @see org.postgresql.PGProperty#MAX_LOG_MESSAGE_LENGTH
 */
public final class LogMessageUtils {

  /** Suffix appended when a log message is truncated. */
  public static final String TRUNCATION_SUFFIX = "...(truncated)";

  private LogMessageUtils() {
  }

  /**
   * Truncates {@code message} so that its length is at most {@code maxLength} characters.
   *
   * <p>If {@code maxLength} is {@code 0} or negative, the message is returned unchanged
   * (unlimited). When truncation is required, the result ends with
   * {@link #TRUNCATION_SUFFIX} (or a prefix of it when {@code maxLength} is smaller than the
   * suffix).</p>
   *
   * @param message message to possibly truncate; must not be {@code null}
   * @param maxLength maximum length in characters; {@code <= 0} means unlimited
   * @return the original message, or a truncated copy of length {@code maxLength}
   */
  public static String truncate(CharSequence message, int maxLength) {
    if (message == null) {
      throw new IllegalArgumentException("message must not be null");
    }
    if (maxLength <= 0 || message.length() <= maxLength) {
      return message.toString();
    }
    if (maxLength <= TRUNCATION_SUFFIX.length()) {
      return TRUNCATION_SUFFIX.substring(0, maxLength);
    }
    StringBuilder sb = new StringBuilder(maxLength);
    sb.append(message, 0, maxLength - TRUNCATION_SUFFIX.length());
    sb.append(TRUNCATION_SUFFIX);
    return sb.toString();
  }

  /**
   * Appends as much of {@code value} as fits into {@code sb} without exceeding {@code maxLength}
   * total length of {@code sb}. When the value does not fit fully, appends a truncated form ending
   * with {@link #TRUNCATION_SUFFIX} (budget permitting).
   *
   * <p>If {@code maxLength} is {@code <= 0}, the full value is appended (unlimited).</p>
   *
   * @param sb destination buffer
   * @param value text to append
   * @param maxLength overall cap for {@code sb}; {@code <= 0} means unlimited
   * @return {@code true} if the full value was appended, {@code false} if truncated or skipped
   */
  public static boolean appendBounded(StringBuilder sb, CharSequence value, int maxLength) {
    if (maxLength <= 0) {
      sb.append(value);
      return true;
    }
    int remaining = maxLength - sb.length();
    if (remaining <= 0) {
      return false;
    }
    if (value.length() <= remaining) {
      sb.append(value);
      return true;
    }
    if (remaining <= TRUNCATION_SUFFIX.length()) {
      sb.append(TRUNCATION_SUFFIX, 0, remaining);
      return false;
    }
    sb.append(value, 0, remaining - TRUNCATION_SUFFIX.length());
    sb.append(TRUNCATION_SUFFIX);
    return false;
  }
}
