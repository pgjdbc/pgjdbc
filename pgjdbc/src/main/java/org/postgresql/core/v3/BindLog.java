/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.Oid;
import org.postgresql.util.internal.LogMessageUtils;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Formats the FINEST {@code FE=> Bind(...)} trace within {@code maxLogMessageLength} characters.
 *
 * <p>Each parameter is either shown whole, shown in a shorter form that says it is shorter (a
 * string prefix ending with {@code ...}, or {@code <N bytes>} for a {@code bytea}), or left out
 * together with every parameter after it, in which case the trace ends with
 * {@link LogMessageUtils#TRUNCATION_SUFFIX}. Room for {@code >,type=...} and for the closing
 * {@code )} or the marker is reserved before a value is appended, so the trace is never cut in
 * the middle of a parameter.</p>
 *
 * @see org.postgresql.PGProperty#MAX_LOG_MESSAGE_LENGTH
 */
final class BindLog {

  private BindLog() {
  }

  /**
   * Builds a Bind trace.
   *
   * @param statementName prepared statement name, or {@code null} for the unnamed statement
   * @param portal destination portal, or {@code null}
   * @param params bind parameters
   * @param standardConformingStrings whether standard_conforming_strings is on
   * @param maxLogMessageLength maximum length in characters; {@code 0} means no limit
   * @return the trace, never longer than {@code maxLogMessageLength} when that is positive
   */
  static String format(@Nullable String statementName, @Nullable Portal portal,
      SimpleParameterList params, boolean standardConformingStrings, int maxLogMessageLength) {
    StringBuilder sb = new StringBuilder(256);
    sb.append(" FE=> Bind(stmt=").append(statementName).append(",portal=").append(portal);
    int count = params.getParameterCount();

    if (maxLogMessageLength <= 0) {
      for (int i = 1; i <= count; i++) {
        sb.append(",$").append(i).append("=<")
            .append(params.toString(i, standardConformingStrings))
            .append(">,type=").append(Oid.toString(params.getTypeOID(i)));
      }
      return sb.append(')').toString();
    }

    final String marker = LogMessageUtils.TRUNCATION_SUFFIX;
    boolean omitted = false;
    for (int i = 1; i <= count; i++) {
      String prefix = ",$" + i + "=<";
      String suffix = ">,type=" + Oid.toString(params.getTypeOID(i));
      // Keep room for ")" after the last parameter, or for the marker if a later one is left out.
      int reserve = i == count ? 1 : marker.length();
      int room = maxLogMessageLength - sb.length() - prefix.length() - suffix.length() - reserve;
      if (room <= 0) {
        omitted = true;
        break;
      }

      long length = params.logLiteralLength(i, standardConformingStrings, room);
      if (length >= 0 && length <= room) {
        sb.append(prefix).append(params.toString(i, standardConformingStrings)).append(suffix);
        continue;
      }

      int byteaLength = params.byteaLengthForLog(i);
      if (byteaLength >= 0) {
        String placeholder = "<" + byteaLength + " bytes>";
        if (placeholder.length() > room) {
          omitted = true;
          break;
        }
        sb.append(prefix).append(placeholder).append(suffix);
        continue;
      }

      @Nullable String text = params.stringValueForLog(i);
      if (text != null) {
        int mark = sb.length();
        sb.append(prefix);
        if (LogMessageUtils.appendQuotedStringPrefix(sb, text, standardConformingStrings, room)) {
          sb.append(suffix);
          continue;
        }
        sb.setLength(mark);
      }
      omitted = true;
      break;
    }

    sb.append(omitted ? marker : ")");
    // Only a limit shorter than the statement header itself is exceeded here.
    return LogMessageUtils.truncate(sb, maxLogMessageLength);
  }
}
