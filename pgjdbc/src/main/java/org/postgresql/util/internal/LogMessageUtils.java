/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util.internal;

/**
 * Helpers for keeping driver log output bounded.
 *
 * <p>FINEST protocol dumps (especially Bind with many or large parameters, and Parse with a long
 * native SQL string) can allocate enormous strings and cause {@link OutOfMemoryError}. These
 * utilities cap message length while preserving a readable prefix of the original content.</p>
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

  /**
   * Appends a prefix of a quoted SQL string literal into {@code sb} without exceeding
   * {@code maxLength}. Escapes at most the first {@code remaining} source characters (escaping at
   * most doubles length), then cuts the escaped text to the room left without ending inside a
   * surrogate pair or a doubled quote, and ends with {@code ...}.
   *
   * @param sb destination (already contains {@code ,$i=<})
   * @param text raw string parameter value
   * @param type SQL type name for {@code ::cast}, or {@code null}
   * @param standardConformingStrings whether standard_conforming_strings is on
   * @param maxLength overall cap for {@code sb}; must be positive
   */
  public static void appendQuotedStringPrefix(StringBuilder sb, String text,
      @org.checkerframework.checker.nullness.qual.Nullable String type,
      boolean standardConformingStrings, int maxLength) {
    int remaining = maxLength - sb.length();
    if (remaining <= 0) {
      return;
    }

    // Escape at most the first `remaining` source characters (review: never build the full literal).
    int sourceLimit = Math.min(text.length(), Math.max(0, remaining));
    StringBuilder escaped = new StringBuilder(sourceLimit + 8);
    escaped.append("('");
    for (int i = 0; i < sourceLimit; i++) {
      char ch = text.charAt(i);
      if (ch == '\0') {
        escaped.append('\u0000');
        continue;
      }
      if (standardConformingStrings) {
        if (ch == '\'') {
          escaped.append('\'');
        }
        escaped.append(ch);
      } else {
        if (ch == '\\' || ch == '\'') {
          escaped.append(ch);
        }
        escaped.append(ch);
      }
    }

    // Room for the value portion only; caller appends ">,type=...".
    int room = maxLength - sb.length();
    if (room <= 0) {
      return;
    }

    // Always end a cut value with "..." so it does not look complete.
    final String ellipsis = "...";
    if (escaped.length() + ellipsis.length() <= room
        && sourceLimit == text.length()) {
      // Entire source prefix was the whole string and fits with room to close? Still may need cast.
      // If full string was taken and escaped form + closing fits, prefer full form — but this
      // method is only used when the full literal does not fit, so always mark as cut.
    }

    int cutBudget = room - ellipsis.length();
    if (cutBudget <= 0) {
      // Not enough room for any content + ellipsis; fill with as much ellipsis as fits.
      sb.append(ellipsis, 0, Math.min(ellipsis.length(), room));
      return;
    }

    int cut = Math.min(escaped.length(), cutBudget);
    // Do not end inside a surrogate pair.
    if (cut >= 1 && Character.isHighSurrogate(escaped.charAt(cut - 1))) {
      cut--;
    }
    // Do not end after the first char of a doubled quote ('').
    if (cut >= 1 && cut < escaped.length()
        && escaped.charAt(cut - 1) == '\''
        && escaped.charAt(cut) == '\'') {
      cut--;
    }
    // Also avoid ending on an odd-position quote that opened a doubling mid-pair:
    // if we cut between '' , the previous char is ' and next is ' — handled above.
    // If cut ends on ' that started a doubling and we already decremented, fine.
    if (cut < 0) {
      cut = 0;
    }
    sb.append(escaped, 0, cut);
    sb.append(ellipsis);
  }
}
