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
 * Controls whether the v3 protocol reader enforces the limits pgjdbc applies where the
 * wire protocol fixes no maximum of its own.
 *
 * <p>Configured through the {@value #SYSTEM_PROPERTY} JVM system property, read once when this
 * class is initialized and applied to every connection the JVM opens. It is deliberately
 * not exposed through the JDBC URL or a {@code Properties} object: a connection string is
 * often assembled from data the application does not fully control, and the driver must not
 * let one relax a protocol check. Where a connection property sets a limit, that property
 * raises it, because raising a limit is a compatibility decision that belongs to a single
 * connection.</p>
 *
 * <p>The mode governs only those limits; every other check the driver makes on a backend
 * message stays on. A check stays outside the mode when it rejects a value no conforming
 * backend can send, when the user configured its limit, or when it is a message-length limit
 * applied before authentication. The tracking that keeps the reader on a message boundary and the
 * {@code maxResultBuffer} memory limit are among them.</p>
 */
public enum ProtocolHardeningMode {
  /**
   * Marks the {@code PGStream} broken on a message over one of pgjdbc's limits and raises a
   * connection-level error, so a pool that checks {@code isClosed()} on borrow discards the
   * connection instead of handing on a stream that may be out of sync. This is the default.
   */
  FAIL("fail"),

  /**
   * Skips every limit the mode can switch off. Intended as a temporary workaround while a
   * false positive is investigated. Prefer raising the individual connection property, which
   * keeps the remaining limits in force.
   */
  DISABLE("disable");

  /**
   * JVM system property that {@link #CURRENT} is read from. Its value names one of the
   * constants, matched case-insensitively once surrounding whitespace is trimmed.
   */
  public static final String SYSTEM_PROPERTY = "pgjdbc.protocolHardeningMode";

  /**
   * Token that selects this constant through {@value #SYSTEM_PROPERTY}. Must be lowercase,
   * since {@link #fromSystemProperty()} lowercases the property value before matching.
   */
  private final String value;

  ProtocolHardeningMode(String value) {
    this.value = value;
  }

  /**
   * Resolves {@link #SYSTEM_PROPERTY} to a mode, reading the property afresh on each call. An
   * unset, empty, unrecognized or unreadable value selects {@link #FAIL}, so a typo in the JVM
   * flag cannot quietly disable the limits. An unrecognized value is logged at
   * {@link Level#WARNING}.
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
    for (ProtocolHardeningMode mode : values()) {
      if (mode.value.equals(trimmed)) {
        return mode;
      }
    }
    // The text is formatted here and logged without parameters, so the handler does not format
    // it a second time and a raw value that contains {0} or a quote is logged as it is.
    Logger.getLogger(ProtocolHardeningMode.class.getName()).log(Level.WARNING,
        GT.tr("System property {0} has the unrecognized value {1}; expected fail or disable. Using fail, so the protocol limits stay enforced.",
            SYSTEM_PROPERTY, raw));
    return FAIL;
  }

  /**
   * The mode this JVM runs under, read from {@value #SYSTEM_PROPERTY} once at class-load time.
   * A value set after this class is initialized has no effect on it.
   */
  public static final ProtocolHardeningMode CURRENT = fromSystemProperty();
}
