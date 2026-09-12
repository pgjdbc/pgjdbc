/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.GT;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls whether the v3 protocol reader enforces the ceilings pgjdbc applies where the
 * wire protocol fixes no maximum of its own.
 *
 * <p>Configured through the {@value #SYSTEM_PROPERTY} JVM system property, read once at
 * driver class-load time and applied to every connection the JVM opens. It is deliberately
 * not exposed through the JDBC URL or a {@code Properties} object: a connection string is
 * often assembled from data the application does not fully control, and the driver must not
 * let one relax a protocol check. The ceilings themselves are ordinary connection properties,
 * because raising one is a compatibility decision that belongs to a single connection.</p>
 *
 * <p>Only those ceilings are affected. Every other check the driver makes on a backend
 * message stays on, and each of them rejects a value no conforming backend can send. The
 * envelope tracking that keeps the reader on a message boundary and the
 * {@code maxResultBuffer} memory cap are among them.</p>
 */
public enum ProtocolHardeningMode {
  /**
   * Marks the {@code PGStream} broken on a message over its ceiling and raises a
   * connection-level error, so a pool that checks {@code isClosed()} on borrow discards the
   * connection instead of handing on a reader that may be misaligned. This is the default.
   */
  FAIL("fail"),

  /**
   * Skips the ceilings entirely. Intended as a temporary workaround while a false positive
   * is investigated. Prefer raising the individual property, which keeps the remaining
   * ceilings in force.
   */
  DISABLE("disable");

  /**
   * JVM system property that selects the mode at driver load time. The {@code pgjdbc.}
   * prefix avoids collision with unrelated software in the same JVM. Values match the
   * lowercase token of each constant, case-insensitively.
   */
  public static final String SYSTEM_PROPERTY = "pgjdbc.protocolHardeningMode";

  private final String value;

  ProtocolHardeningMode(String value) {
    this.value = value;
  }

  /**
   * Resolves {@link #SYSTEM_PROPERTY} to a mode. An unset, empty or unrecognised value
   * selects {@link #FAIL}, so a typo in the JVM flag cannot quietly disable the ceilings.
   */
  static ProtocolHardeningMode fromSystemProperty() {
    String raw;
    try {
      raw = System.getProperty(SYSTEM_PROPERTY);
    } catch (SecurityException e) {
      // Some sandboxed runtimes deny System.getProperty for non-standard keys.
      return FAIL;
    }
    if (raw == null) {
      return FAIL;
    }
    String trimmed = raw.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) {
      return FAIL;
    }
    for (ProtocolHardeningMode b : values()) {
      if (b.value.equals(trimmed)) {
        return b;
      }
    }
    Logger.getLogger(ProtocolHardeningMode.class.getName()).log(Level.WARNING,
        "Unrecognised value for system property {0}: {1}. Allowed: fail, disable. Falling back to fail.",
        new Object[]{SYSTEM_PROPERTY, raw});
    return FAIL;
  }

  /**
   * The mode selected for this JVM, resolved once at class-load time so the hot path does
   * not re-read the system property.
   */
  public static final ProtocolHardeningMode CURRENT = fromSystemProperty();

  /**
   * Where users are asked to report a false positive. Passed as a message argument rather
   * than spelled out in the format string, so moving the tracker does not invalidate every
   * translation of the messages that link to it.
   */
  public static final String ISSUE_TRACKER_URL = "https://github.com/pgjdbc/pgjdbc/issues";

  /**
   * Appends the escape hatch to a message for a ceiling no connection property raises, so the
   * text still tells the reader what to do next. Prefer
   * {@link #appendSilenceHint(String, String)} wherever a property exists, since raising the
   * property keeps the remaining ceilings in force.
   *
   * @param baseMessage localised message the ceiling produced
   */
  public static String appendSilenceHint(String baseMessage) {
    return baseMessage
        + GT.tr(" Set -D{0}=disable to skip these ceilings altogether."
            + " Please file a bug report at {1}.",
            SYSTEM_PROPERTY, ISSUE_TRACKER_URL);
  }

  /**
   * Appends the hint naming the escape hatch, so whoever reads the exception knows the
   * ceiling is adjustable without reading the source.
   *
   * <p>The format string is inline rather than a constant because {@code xgettext} extracts
   * {@link GT#tr} arguments lexically, and a constant reference produces no catalogue
   * entry.</p>
   */
  public static String appendSilenceHint(String baseMessage, String propertyName) {
    return baseMessage
        + GT.tr(" Raise the {0} connection property if the backend legitimately sends more,"
            + " or set -D{1}=disable to skip these ceilings altogether."
            + " Please file a bug report at {2}.",
            propertyName, SYSTEM_PROPERTY, ISSUE_TRACKER_URL);
  }
}
