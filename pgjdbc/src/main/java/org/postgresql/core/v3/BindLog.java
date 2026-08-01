/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.Oid;
import org.postgresql.util.LogMessageUtils;

import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Formats FINEST {@code FE=> Bind(...)} log lines with a hard length cap so large or numerous
 * bind parameters cannot OOM the process while logging.
 *
 * @see org.postgresql.PGProperty#MAX_LOG_MESSAGE_LENGTH
 */
final class BindLog {

  private BindLog() {
  }

  /**
   * Builds a Bind dump string.
   *
   * @param statementName prepared statement name (may be {@code null} for unnamed)
   * @param portal destination portal (may be {@code null})
   * @param params bind parameters
   * @param standardConformingStrings whether to use standard_conforming_strings when rendering
   * @param maxLogMessageLength max characters; {@code <= 0} means unlimited (legacy behaviour)
   * @return log line, never longer than {@code maxLogMessageLength} when that limit is positive
   */
  static String format(@Nullable String statementName, @Nullable Portal portal,
      SimpleParameterList params, boolean standardConformingStrings, int maxLogMessageLength) {
    // Prefer a modest initial capacity; growth is bounded by the cap when set.
    int initial = maxLogMessageLength > 0 ? Math.min(256, maxLogMessageLength) : 256;
    StringBuilder sbuf = new StringBuilder(initial);
    sbuf.append(" FE=> Bind(stmt=").append(statementName).append(",portal=").append(portal);

    final boolean unlimited = maxLogMessageLength <= 0;
    for (int i = 1; i <= params.getParameterCount(); i++) {
      if (!unlimited && sbuf.length() >= maxLogMessageLength) {
        break;
      }

      // Build the parameter prefix first so we can decide whether it fits before appending.
      // Appending ",$i=<" and then breaking leaves a dangling fragment on the truncated line.
      String prefix = ",$" + i + "=<";
      if (!unlimited) {
        int remainingForPrefix = maxLogMessageLength - sbuf.length();
        if (remainingForPrefix <= prefix.length()) {
          break;
        }
      }
      sbuf.append(prefix);

      if (!unlimited) {
        int remaining = maxLogMessageLength - sbuf.length();
        // Avoid materializing huge values just for logging.
        // estimateLogSize is a lower-bound for known types (bytea hex expands ~2x; text quoting
        // adds a little). A negative estimate means an unrecognized value type — do not call
        // toString (which can fully materialize e.g. a large custom object) and use a placeholder
        // instead. The final truncate() still guarantees the returned line never exceeds the cap.
        int estimated = params.estimateLogSize(i);
        if (estimated < 0 || estimated > remaining) {
          String placeholder = estimated < 0
              ? "...(unestimated)"
              : "...(~" + estimated + " chars)";
          LogMessageUtils.appendBounded(sbuf, placeholder, maxLogMessageLength);
          sbuf.append(">,type=").append(Oid.toString(params.getTypeOID(i)));
          continue;
        }
        String value = params.toString(i, standardConformingStrings);
        LogMessageUtils.appendBounded(sbuf, value, maxLogMessageLength);
      } else {
        sbuf.append(params.toString(i, standardConformingStrings));
      }
      sbuf.append(">,type=").append(Oid.toString(params.getTypeOID(i)));
    }

    if (unlimited || sbuf.length() < maxLogMessageLength) {
      sbuf.append(')');
    }
    return LogMessageUtils.truncate(sbuf, maxLogMessageLength);
  }
}
