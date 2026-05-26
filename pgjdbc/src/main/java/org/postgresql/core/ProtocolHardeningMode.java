/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.util.GT;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Locale;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls how the v3 protocol reader reacts when one of the hardening checks
 * introduced by issue #4015 trips on a backend message.
 *
 * <p>Configured through the {@value #SYSTEM_PROPERTY} JVM system property
 * (e.g. {@code -Dpgjdbc.protocolHardeningMode=fail}). The setting is read
 * once at driver class-load time and applies to every connection opened by
 * the JVM. The property is deliberately not exposed via the JDBC URL or
 * {@code Properties} object: a URL-level override would let an attacker who
 * controls the connection string disable the hardening checks. Default:
 * {@link #WARN}, which preserves the pre-#4015 read path while logging any
 * check failure so that a real desync remains visible in logs.</p>
 *
 * <p>The hardening checks were added to fail fast and tear the connection down
 * when a backend response looks corrupted (length field out of range, message
 * envelope size disagrees with field-length sum, missing NUL in a C-string,
 * authentication exchange exceeds a sane round-trip cap, and so on). They are
 * tight by design: a real desync should not produce well-formed enough bytes
 * to slip through. If a benign workload trips one (e.g. an exotic
 * wire-compatible fork that violates the documented protocol in a way pgjdbc
 * has not yet accommodated), the property lets the user relax the policy
 * without rebuilding the driver.</p>
 */
public enum ProtocolHardeningMode {
  /**
   * On the first hardening check failure, mark the {@code PGStream} broken
   * (best-effort close the socket and flag {@code isClosed()}) and throw a
   * connection-level error. A pooling layer that calls {@code isClosed()} on
   * borrow will discard the connection rather than hand a desynced reader to
   * the next caller. Opt in via {@value #SYSTEM_PROPERTY}{@code =fail} for the
   * strictest safety profile against a desynced or hostile backend.
   */
  FAIL("fail"),

  /**
   * Default. Log the hardening violation at {@link Level#WARNING WARNING} and
   * continue with the suspect value, restoring the pre-#4015 read path. The
   * driver may still crash later (e.g. {@code NegativeArraySizeException},
   * {@code OutOfMemoryError}, or an indefinite socket read) if the underlying
   * data is genuinely corrupted, but the log breadcrumb lets the user confirm
   * whether the check is firing on real or pathological but benign traffic.
   * Chosen as the default so that an upgrade to a pgjdbc release that carries
   * #4015 cannot turn a previously working (if mildly non-conformant) workload
   * into hard connection failures without warning.
   *
   * <p>The log record carries the exception that {@link #FAIL} would have
   * thrown, so the stack trace points at the exact protocol-reader site that
   * detected the violation. Whether the trace is rendered in the log output
   * is up to the logger handler and formatter, not the driver.</p>
   */
  WARN("warn"),

  /**
   * Silently skip the new hardening checks, restoring the pre-#4015 read path.
   * Discouraged: a real desync can exhaust memory or block on a socket read
   * indefinitely, and a check that fires on benign traffic leaves nothing in
   * the logs to triage. Set this only as a temporary workaround while a bug
   * report is being investigated; prefer {@link #WARN} so the symptom remains
   * visible in logs.
   */
  DISABLE("disable");

  /**
   * JVM system property that selects the behaviour at driver load time.
   * Prefixed with {@code pgjdbc.} to avoid collision with unrelated software
   * in the same JVM. Values are matched case-insensitively against
   * {@link #getValue()}.
   */
  public static final String SYSTEM_PROPERTY = "pgjdbc.protocolHardeningMode";

  private final String value;

  ProtocolHardeningMode(String value) {
    this.value = value;
  }

  /**
   * Returns the lowercase token used to select this behaviour on the command
   * line ({@code fail}, {@code warn}, {@code disable}). The token is the one
   * end users type, so it appears in {@link #SYSTEM_PROPERTY}, in error
   * messages that direct users to the silence knob, and in the parser.
   */
  public String getValue() {
    return value;
  }

  /**
   * Reads {@link #SYSTEM_PROPERTY} from the JVM and resolves it to a
   * {@code ProtocolHardeningMode}. An unset or empty property selects
   * {@link #WARN} (the default). An unrecognised value is reported via
   * {@code LOGGER} at {@link Level#WARNING} and also falls back to {@link #WARN},
   * so a typo in the JVM flag does not silently flip the policy to a stricter
   * or more permissive mode than the user intended.
   */
  static ProtocolHardeningMode fromSystemProperty() {
    String raw;
    try {
      raw = System.getProperty(SYSTEM_PROPERTY);
    } catch (SecurityException e) {
      // Some sandboxed runtimes deny System.getProperty for non-standard keys;
      // treat that as "no override configured" rather than failing class load.
      return WARN;
    }
    if (raw == null) {
      return WARN;
    }
    String trimmed = raw.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty()) {
      return WARN;
    }
    for (ProtocolHardeningMode b : values()) {
      if (b.value.equals(trimmed)) {
        return b;
      }
    }
    Logger.getLogger(ProtocolHardeningMode.class.getName()).log(Level.WARNING,
        "Unrecognised value for system property {0}: {1}. Allowed: fail, warn, disable. Falling back to warn.",
        new Object[]{SYSTEM_PROPERTY, raw});
    return WARN;
  }

  /**
   * The behaviour selected for this JVM, resolved once at class-load time.
   * Held in a singleton to avoid re-reading the system property on every
   * hot-path check.
   */
  public static final ProtocolHardeningMode CURRENT = fromSystemProperty();

  /**
   * Hint appended to the error message a {@link #FAIL}-mode throw site
   * surfaces to the user. The text explicitly points at the silence knob and
   * asks for a bug report, so that an SRE or AI assistant triaging the
   * exception has actionable next steps without having to read the source.
   * The hint is only added in {@link #FAIL} mode (in {@link #WARN} and
   * {@link #DISABLE} the operator already knows about the property, since
   * they set it).
   *
   * <p>Carries a {@code {0}} placeholder for {@link #SYSTEM_PROPERTY} so the
   * full string remains a single translation key in the gettext catalogue.</p>
   */
  public static final String SILENCE_HINT_FORMAT =
      " If you are sure this is a false positive, you can silence the error by setting"
          + " -D{0}=warn (log only) or -D{0}=disable (no logging)."
          + " Consider filing a bug report at https://github.com/pgjdbc/pgjdbc/issues.";

  /**
   * Appends the localised silence hint to {@code baseMessage} for use in
   * {@link #FAIL}-mode error messages. Kept as a small helper so the suffix
   * is added in a single place and call sites do not need to remember to
   * include it.
   */
  public static String appendSilenceHint(@Nullable String baseMessage) {
    return (baseMessage == null ? "" : baseMessage)
        + GT.tr(SILENCE_HINT_FORMAT, SYSTEM_PROPERTY);
  }
}
