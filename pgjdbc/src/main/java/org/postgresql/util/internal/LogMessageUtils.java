/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

/**
 * Helpers for keeping driver log output bounded.
 *
 * <p>A FINEST protocol trace of a statement with many or large parameters can be as large as the
 * statement itself. These helpers keep such a trace within a length limit and keep a readable
 * prefix of the original content.</p>
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
   * (no limit). When truncation is required, the result ends with
   * {@link #TRUNCATION_SUFFIX} (or a prefix of it when {@code maxLength} is smaller than the
   * suffix).</p>
   *
   * @param message message to possibly truncate; must not be {@code null}
   * @param maxLength maximum length in characters; {@code <= 0} means no limit
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
   * <p>If {@code maxLength} is {@code <= 0}, the full value is appended (no limit).</p>
   *
   * @param sb destination buffer
   * @param value text to append
   * @param maxLength length limit for {@code sb}; {@code <= 0} means no limit
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

  /**
   * Appends the start of a quoted SQL string literal, {@code ('} followed by the escaped prefix of
   * {@code text} and {@code ...}, using at most {@code room} characters. Each escaped character
   * (a doubled quote, a doubled backslash when standard_conforming_strings is off, or a surrogate
   * pair) is appended whole or not at all. Only the prefix that fits is escaped.
   *
   * @param sb destination buffer
   * @param text raw string value
   * @param standardConformingStrings whether standard_conforming_strings is on
   * @param room maximum number of characters to append
   * @return {@code true} if at least one character of {@code text} was appended; {@code false} if
   *     none fits, in which case {@code sb} is left unchanged
   */
  public static boolean appendQuotedStringPrefix(StringBuilder sb, String text,
      boolean standardConformingStrings, int room) {
    final String open = "('";
    final String ellipsis = "...";
    int budget = room - open.length() - ellipsis.length();
    int start = sb.length();
    sb.append(open);
    int used = 0;
    int i = 0;
    while (i < text.length()) {
      char ch = text.charAt(i);
      int width;
      int consumed = 1;
      if (ch == '\'' || (!standardConformingStrings && ch == '\\')) {
        width = 2;
      } else if (Character.isHighSurrogate(ch) && i + 1 < text.length()
          && Character.isLowSurrogate(text.charAt(i + 1))) {
        width = 2;
        consumed = 2;
      } else {
        width = 1;
      }
      if (used + width > budget) {
        break;
      }
      if (width == 2 && consumed == 1) {
        sb.append(ch);
      }
      sb.append(text, i, i + consumed);
      used += width;
      i += consumed;
      if (ch == '\0') {
        break;
      }
    }
    if (used == 0) {
      sb.setLength(start);
      return false;
    }
    sb.append(ellipsis);
    return true;
  }
}
