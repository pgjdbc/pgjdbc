/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.Oid;
import org.postgresql.util.internal.LogMessageUtils;

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
    int initial = maxLogMessageLength > 0 ? Math.min(256, maxLogMessageLength) : 256;
    StringBuilder sbuf = new StringBuilder(initial);
    sbuf.append(" FE=> Bind(stmt=").append(statementName).append(",portal=").append(portal);

    final boolean unlimited = maxLogMessageLength <= 0;
    boolean omitted = false;
    for (int i = 1; i <= params.getParameterCount(); i++) {
      if (!unlimited && sbuf.length() >= maxLogMessageLength) {
        omitted = true;
        break;
      }

      String prefix = ",$" + i + "=<";
      if (!unlimited) {
        int remainingForPrefix = maxLogMessageLength - sbuf.length();
        if (remainingForPrefix <= prefix.length()) {
          omitted = true;
          break;
        }
      }
      sbuf.append(prefix);

      if (unlimited) {
        sbuf.append(params.toString(i, standardConformingStrings));
        sbuf.append(">,type=").append(Oid.toString(params.getTypeOID(i)));
        continue;
      }

      int remaining = maxLogMessageLength - sbuf.length();
      int estimated = params.estimateLogSize(i);
      int oid = params.getTypeOID(i);
      int byteaBytes = (oid == Oid.BYTEA) ? params.byteaPayloadBytes(i) : -1;

      if (estimated < 0) {
        LogMessageUtils.appendBounded(sbuf, "...(unestimated)", maxLogMessageLength);
        sbuf.append(">,type=").append(Oid.toString(oid));
        continue;
      }

      if (byteaBytes >= 0) {
        // Literal is "'\\x' + 2*n hex + "'::bytea" = 2*n+11 (see estimateLogSize).
        if (estimated > remaining) {
          LogMessageUtils.appendBounded(sbuf, "<" + byteaBytes + " bytes>", maxLogMessageLength);
          sbuf.append(">,type=").append(Oid.toString(oid));
          continue;
        }
        LogMessageUtils.appendBounded(sbuf, params.toString(i, standardConformingStrings),
            maxLogMessageLength);
        sbuf.append(">,type=").append(Oid.toString(oid));
        continue;
      }

      @Nullable String stringValue = params.stringValueForLog(i);
      if (stringValue != null) {
        boolean usePrefix = estimated > remaining;
        if (!usePrefix) {
          // Quoting, escaping, and ::cast can make the literal longer than the raw length.
          String typeName = Oid.toString(oid);
          int castExtra = (typeName != null && !"?".equals(typeName)) ? typeName.length() + 2 : 0;
          if (2L * estimated + 4 + castExtra > remaining) {
            usePrefix = true;
          }
        }
        if (usePrefix) {
          LogMessageUtils.appendQuotedStringPrefix(sbuf, stringValue, typeNameForCast(oid),
              standardConformingStrings, maxLogMessageLength);
          sbuf.append(">,type=").append(Oid.toString(oid));
          continue;
        }
      }

      String value = params.toString(i, standardConformingStrings);
      if (value.length() > remaining && stringValue != null) {
        LogMessageUtils.appendQuotedStringPrefix(sbuf, stringValue, typeNameForCast(oid),
            standardConformingStrings, maxLogMessageLength);
      } else {
        LogMessageUtils.appendBounded(sbuf, value, maxLogMessageLength);
      }
      sbuf.append(">,type=").append(Oid.toString(oid));
    }

    if (unlimited) {
      sbuf.append(')');
      return sbuf.toString();
    }

    if (omitted) {
      // Always mark when parameters were left out so the trace does not look complete.
      sbuf.append(LogMessageUtils.TRUNCATION_SUFFIX);
      return LogMessageUtils.truncate(sbuf, maxLogMessageLength);
    }

    if (sbuf.length() < maxLogMessageLength) {
      sbuf.append(')');
    }
    return LogMessageUtils.truncate(sbuf, maxLogMessageLength);
  }

  private static @Nullable String typeNameForCast(int oid) {
    switch (oid) {
      case Oid.VARCHAR:
        return "varchar";
      case Oid.TEXT:
        return "text";
      case Oid.BPCHAR:
        return "bpchar";
      case Oid.JSON:
        return "json";
      case Oid.JSONB:
        return "jsonb";
      case Oid.XML:
        return "xml";
      default:
        return null;
    }
  }
}
