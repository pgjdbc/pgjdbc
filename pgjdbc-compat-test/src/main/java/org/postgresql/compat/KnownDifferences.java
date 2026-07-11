/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.compat;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The xfail registry of deliberate behaviour changes between the baseline and the current driver. A
 * difference recorded here is expected; a difference not recorded here fails the oracle. This mirrors the
 * "known bugs stay in a separate list" approach of the typecache coercion registry: the gate stays green
 * only while every observed divergence is understood and justified.
 *
 * <p>Two kinds of entry:
 * <ul>
 *   <li><b>Point entries</b> ({@link #point}) pin one cell by its {@link #readLabel}/{@link #writeLabel}.
 *       Use them for a single, specific change (a bug fix, one type's server-parity fix).
 *   <li><b>Rules</b> ({@link Rule}) accept a whole family of cells described by a predicate over the
 *       outcomes. Use them for a systematic change, so the registry does not rot into dozens of
 *       near-identical lines.
 * </ul>
 *
 * <p>Calibrated against the PostgreSQL version used by the test database; a different server version may
 * shift a few coercion outcomes.
 */
public final class KnownDifferences {
  /** A predicate over a reported difference that, when it matches, explains why the change is acceptable. */
  @FunctionalInterface
  interface Rule {
    @Nullable String reason(String label, ObservableOutcome current, ObservableOutcome baseline);
  }

  private static final Map<String, String> POINTS = new LinkedHashMap<>();
  private static final List<Rule> RULES = new ArrayList<>();

  // Which entries actually explained a difference during the run. An entry that never matched is
  // reported by unusedEntries() so a converged divergence does not rot in the registry unnoticed.
  private static final Set<String> USED_POINTS = new HashSet<>();
  private static final Set<Integer> USED_RULES = new HashSet<>();

  static {
    // Systematic: an incoercible read that leaked an unchecked exception in the baseline now raises a
    // proper SQLException. Strictly better error typing; safe to accept wherever it appears on the read
    // side. Covers the whole getDate-on-incoercible-type family and similar cases.
    RULES.add((label, current, baseline) -> {
      if (isReadSurface(label)
          && current.threw() && baseline.threw()
          && ObservableOutcome.FAMILY_SQL.equals(current.exceptionFamily())
          && ObservableOutcome.FAMILY_RUNTIME.equals(baseline.exceptionFamily())) {
        return "baseline leaked an unchecked exception on an incoercible read; the codec path now "
            + "raises a proper SQLException";
      }
      return null;
    });

    // Systematic: getObject(col, Class) for String/BigDecimal/Instant targets that the baseline refused
    // (SQLState 22023) now returns a value. The codec path broadened getObject(Class) support; this is
    // additive and JDBC-spec-aligned, so accept it wherever the baseline refused and the current driver
    // returns the requested type. GET_OBJECT_BYTES stays out of this list on purpose: byte[] has no
    // broadened mapping, so both drivers refuse it (the array path once returned the element mapping
    // instead of refusing; that is fixed, so the cell now matches the baseline with no rule).
    RULES.add((label, current, baseline) -> {
      boolean broadenedTarget = label.endsWith("|GET_OBJECT_STRING")
          || label.endsWith("|GET_OBJECT_BIG_DECIMAL")
          || label.endsWith("|GET_OBJECT_INSTANT");
      if (isReadSurface(label) && broadenedTarget && !current.threw() && baseline.threw()) {
        return "codec broadened getObject(Class) support: the baseline refused this target type, the "
            + "current driver now returns a value of it";
      }
      return null;
    });

    // money: the baseline could not strip the locale's grouping separators from the server's money text
    // ("$92,233,720,368,547,758.07"), so every numeric getter threw on the int64-range values. The money
    // codec now decodes them, so the current driver returns a value where the baseline threw. getObject
    // still returns Double (money maps to Types.DOUBLE) on both drivers, so it is compared normally;
    // getString returns the server's own money text on both, so it matches too.
    RULES.add((label, current, baseline) -> {
      if (label.startsWith("money-edge|") && !current.threw() && baseline.threw()) {
        return "money now decodes the locale-grouped int64-range values; the baseline could not strip "
            + "the grouping separators from the server's money text and threw";
      }
      return null;
    });

    // Wire-format-dependent getBytes (issue #4277) on the interval edge axis: interval now transfers in
    // binary, so getBytes returns the raw 16-byte wire value instead of the text rendering.
    RULES.add((label, current, baseline) -> {
      if (label.startsWith("interval-edge|") && label.endsWith("|GET_BYTES")) {
        return "getBytes on interval returns raw wire bytes; interval now transfers in binary (see #4277)";
      }
      return null;
    });

    // Wire-format-dependent getBytes (issue #4277) on the bit-string axes: bit/varbit now transfers in
    // binary, so getBytes returns the length-prefixed wire value instead of the text rendering.
    RULES.add((label, current, baseline) -> {
      if ((label.startsWith("varbit-edge|") || label.startsWith("bit1-edge|"))
          && label.endsWith("|GET_BYTES")) {
        return "getBytes on a bit string returns raw wire bytes; bit/varbit now transfers in binary "
            + "(see #4277)";
      }
      return null;
    });

    // Wire-format-dependent getBytes (issue #4277) on the jsonb edge axis: jsonb now transfers in binary,
    // so getBytes returns the raw wire value (leading 0x01 version byte plus the JSON text).
    RULES.add((label, current, baseline) -> {
      if (label.startsWith("jsonb-edge|") && label.endsWith("|GET_BYTES")) {
        return "getBytes on jsonb returns raw wire bytes with its version byte; jsonb now transfers in "
            + "binary (see #4277)";
      }
      return null;
    });

    // Bug fix on the bytea edge axis: getString returned the byte[]'s Object.toString() ("[B@...") in the
    // baseline; the codec path returns the proper hex text (\x...).
    RULES.add((label, current, baseline) -> {
      String baselineValue = baseline.value();
      if (label.startsWith("bytea-edge|") && label.endsWith("|GET_STRING")
          && baselineValue != null && baselineValue.contains("[B@")) {
        return "getString on bytea returns the hex text; the baseline returned byte[].toString()";
      }
      return null;
    });

    // bool-edge mirrors the fixed bool read: getString matches the server text 'true'/'false' where the
    // baseline surfaced the internal 't'/'f' when bool arrived in binary, and getBytes returns the raw
    // wire byte (0x01/0x00) instead of the text rendering (issue #4277).
    RULES.add((label, current, baseline) -> {
      if (label.startsWith("bool-edge|") && label.endsWith("|GET_STRING")
          && !current.threw() && !baseline.threw()) {
        String c = current.value();
        String b = baseline.value();
        // Accept only the intended shape: current renders the server text ('true'/'false') where the
        // baseline surfaced the internal 't'/'f'. A future regression on this cell (a different string,
        // a null, or a throw) must still fail the matrix, so check the values, not just the label.
        // The normalized form is "<class>:<value>" (see OutcomeComparator.normalize), so match the
        // value suffix rather than the whole string.
        boolean serverText = c != null && (c.endsWith(":true") || c.endsWith(":false"));
        boolean internalText = b != null && (b.endsWith(":t") || b.endsWith(":f"));
        if (serverText && internalText) {
          return "getString on bool matches the server text 'true'/'false'; the baseline surfaced the "
              + "internal 't'/'f' when the value arrived in binary";
        }
      }
      if (label.startsWith("bool-edge|") && label.endsWith("|GET_BYTES")) {
        return "getBytes on bool returns the raw wire byte; bool now transfers in binary (see #4277)";
      }
      return null;
    });

    // Server-parity on the int4[] edge axis: getString matches the server array text ({1,2,3}) instead of
    // quoting each element ({"1","2","3"}) as the baseline did.
    RULES.add((label, current, baseline) -> {
      if (label.startsWith("int4arr-edge|") && label.endsWith("|GET_STRING")
          && !current.threw() && !baseline.threw()) {
        String c = current.value();
        String b = baseline.value();
        // Accept only the intended shape: the baseline is exactly the current text with each element
        // quoted, so stripping the quotes recovers it. A regression that changed the current value (or
        // made it null/throw) no longer matches and still fails the matrix.
        if (c != null && b != null && b.indexOf('"') >= 0 && b.replace("\"", "").equals(c)) {
          return "getString on int4[] matches the server array text instead of quoting elements";
        }
      }
      return null;
    });

    // Server-parity: float4 now widens to the exact double the 4-byte value represents (verified:
    // '2147483647'::float4::float8 = 2147483648), where the baseline widened the lossy 7-significant-digit
    // text form (2.1474836E9). Applies to the wide getters that return the value directly; the narrower
    // integer getters are compared normally so their clamping is not hidden.
    RULES.add((label, current, baseline) -> {
      boolean wideGetter = label.endsWith("|GET_DOUBLE")
          || label.endsWith("|GET_BIG_DECIMAL")
          || label.endsWith("|GET_LONG");
      if (label.startsWith("float4-edge|") && wideGetter && !current.threw() && !baseline.threw()) {
        return "float4 widens to the exact double (server-parity); the baseline used the lossy "
            + "7-significant-digit form";
      }
      return null;
    });

    // Server-parity: the float4 values 2147483647 and 2147483648 both round to the exact float 2^31,
    // one past Integer.MAX_VALUE, so getInt now refuses with 22003 as the server's real->int4 cast
    // does. The baseline widened the lossy 7-significant-digit text form (2147483600), which still fit
    // int, and returned it — silently losing the overflow. Only getInt is affected here: getLong keeps
    // the widened value (accepted above) and the NaN/Infinity specials refuse on both drivers.
    RULES.add((label, current, baseline) -> {
      if (label.startsWith("float4-edge|") && label.endsWith("|GET_INT")
          && current.threw() && "22003".equals(current.sqlState()) && !baseline.threw()) {
        return "getInt on an over-range float4 refuses (server-parity); the baseline returned the "
            + "lossy 7-significant-digit widening that still fit int";
      }
      return null;
    });

    // Intended new type: range getObject returns a typed org.postgresql.util.PGRange where the baseline
    // returned a generic PGobject. Accept only when the rendered value is otherwise identical, so a value
    // change (for example a tsrange bound formatted differently) is still reported.
    RULES.add((label, current, baseline) -> {
      if (label.contains("range-edge|") && label.endsWith("|GET_OBJECT")
          && !current.threw() && !baseline.threw()) {
        String c = current.value();
        String b = baseline.value();
        if (c != null && b != null
            && c.startsWith("org.postgresql.util.PGRange:")
            && b.startsWith("org.postgresql.util.PGobject:")
            && c.substring(c.indexOf(':') + 1).equals(b.substring(b.indexOf(':') + 1))) {
          return "range getObject returns the new typed PGRange instead of the generic PGobject";
        }
      }
      return null;
    });

    // Wire-format-dependent getBytes (issue #4277) on the geometric and range axes: these types now
    // transfer in binary, so getBytes returns the raw wire value instead of the text rendering.
    RULES.add((label, current, baseline) -> {
      boolean geomOrRange = label.startsWith("point-edge|") || label.startsWith("line-edge|")
          || label.startsWith("lseg-edge|") || label.startsWith("box-edge|")
          || label.startsWith("path-edge|") || label.startsWith("polygon-edge|")
          || label.startsWith("circle-edge|") || label.contains("range-edge|");
      if (geomOrRange && label.endsWith("|GET_BYTES")) {
        return "getBytes on a geometric/range type returns raw wire bytes; it now transfers in binary "
            + "(see #4277)";
      }
      return null;
    });

    // oid is unsigned 32-bit: the integer getters reinterpret its low bits and no longer throw for oids
    // above the signed-int range (getInt wraps to a negative, getByte/getShort return the low bits, all
    // treating the result as unsigned). The baseline refused these with 22003. getLong keeps the full
    // unsigned value on both drivers, so it is compared normally.
    RULES.add((label, current, baseline) -> {
      if (label.startsWith("oid-edge|")
          && (label.endsWith("|GET_INT") || label.endsWith("|GET_SHORT") || label.endsWith("|GET_BYTE"))
          && !current.threw() && baseline.threw() && "22003".equals(baseline.sqlState())) {
        return "oid integer getters reinterpret the unsigned 32-bit value and no longer throw; the "
            + "baseline refused oids above the signed-int range";
      }
      return null;
    });

    // Systematic: getArray on a non-array type refuses on both drivers, but the codec path reports a
    // different SQLState (for example 22000/02000 vs the baseline's 0A000). Both mean "this is not an
    // array". getArray on a real array still returns a value and is compared normally.
    RULES.add((label, current, baseline) -> {
      if (label.contains("|GET_ARRAY") && current.threw() && baseline.threw()
          && ObservableOutcome.FAMILY_SQL.equals(current.exceptionFamily())
          && ObservableOutcome.FAMILY_SQL.equals(baseline.exceptionFamily())) {
        return "getArray on a non-array type refuses on both drivers; the codec path reports a "
            + "different SQLState";
      }
      return null;
    });

    // Baseline bugs on incoercible reads that the codec path now refuses cleanly.
    point(readLabel("text", "point", Accessor.GET_ARRAY),
        "baseline getArray on point returned a bogus float8 array; the codec path refuses");
    point(readLabel("binary", "point", Accessor.GET_BIG_DECIMAL),
        "baseline getBigDecimal on point returned null; the codec path refuses");

    // Bug fix: getString on bytea returned the byte[]'s Object.toString() ("[B@...") in the baseline; the
    // codec path returns the proper hex text (\x...). Same fix reached through setBytes then getString.
    point(readLabel("binary", "bytea", Accessor.GET_STRING),
        "baseline getString on bytea returned byte[].toString(); the codec path returns the hex text");
    point(writeLabel("binary", Binder.SET_BYTES),
        "baseline getString over a setBytes round-trip returned byte[].toString(); the codec path "
            + "returns the hex text");

    // Server-parity: the server text form of bool is 'true'/'false'; the baseline surfaced the internal
    // single char 't' when the value arrived in binary.
    point(readLabel("binary", "bool", Accessor.GET_STRING),
        "getString on bool now matches the server text form 'true' instead of the internal 't'");

    // Wire-format-dependent getBytes (issue #4277): getBytes on a non-bytea type returns the raw wire
    // bytes, so enabling binary transfer for these types changes the bytes (text '42'/'t' vs binary
    // encoding). The footgun is pre-existing; the change is a consequence of the wire format, not a
    // decode regression.
    point(readLabel("binary", "oid", Accessor.GET_BYTES),
        "getBytes on oid returns raw wire bytes; oid now transfers in binary (see #4277)");
    point(readLabel("binary", "bool", Accessor.GET_BYTES),
        "getBytes on bool returns raw wire bytes; bool now transfers in binary (see #4277)");
    point(readLabel("binary", "jsonb", Accessor.GET_BYTES),
        "getBytes on jsonb returns raw wire bytes; jsonb now transfers in binary with its version "
            + "byte (see #4277)");
    point(readLabel("binary", "interval", Accessor.GET_BYTES),
        "getBytes on interval returns raw wire bytes; interval now transfers in binary (see #4277)");

    // Server-parity: getString on int4[] now matches the server array text {1,2,3} instead of quoting
    // each element.
    point(readLabel("binary", "int4arr", Accessor.GET_STRING),
        "getString on int4[] now matches the server array text instead of quoting elements");

    // Server-parity: the integer getters (getByte/getShort/getInt/getLong) on a numeric now round
    // half-away-from-zero like PostgreSQL's numeric->intN casts, where the baseline truncated toward zero.
    // This is a systematic change over the whole numeric-edge axis, handled by numericRoundingParity (it
    // needs each case's value), not a point entry: it covers both the changed in-range value
    // (2147483646.5 -> 2147483647, not 2147483646) and the boundary overflow (2147483647.5 rounds to
    // 2147483648 and overflows, where the baseline returned Integer.MAX_VALUE).

    // Server-parity tightening: PostgreSQL does not cast date to time ('...'::date::time errors), so the
    // baseline's lenient date -> time 00:00:00 coercion is dropped in favour of a refusal.
    point(readLabel("text", "date", Accessor.GET_TIME),
        "getTime on a date no longer coerces to midnight; the server has no date -> time cast");

    // Server-parity: getString on a binary timetz now keeps the value's own UTC offset (matching the
    // server's ::text), where the baseline shifted the value into the session zone and reported that
    // zone's offset. Enumerated per edge case rather than as a blanket rule so a genuine future
    // regression in one cell is not masked.
    for (String timetzCase : new String[]{"utc", "max_offset", "min_offset", "half_hour_offset",
        "one_microsecond", "half_microsecond", "second_boundary"}) {
      point(edgeLabel("timetz-edge", "binary", timetzCase, Accessor.GET_STRING),
          "getString on a binary timetz now keeps its wire offset instead of shifting to the session "
              + "zone");
    }

    // Server-parity: getString on a small binary numeric now uses the plain form, matching the server's
    // ::text, where the baseline used BigDecimal's scientific notation (1E-20).
    point(numericEdgeLabel("binary", "tiny", Accessor.GET_STRING),
        "getString on a small binary numeric now uses the plain form instead of scientific notation");
  }

  private KnownDifferences() {
  }

  private static void point(String label, String reason) {
    POINTS.put(label, reason);
  }

  /** True for a read-side cell (ResultSet, CallableStatement, or any {@code <type>-edge} axis). */
  private static boolean isReadSurface(String label) {
    return label.startsWith("read|")
        || label.startsWith("callable|")
        || label.contains("-edge|");
  }

  /** Stable label for a read-half cell: {@code read|<format>|<type>|<accessor>}. */
  public static String readLabel(String format, String typeName, Accessor accessor) {
    return "read|" + format + "|" + typeName + "|" + accessor.name();
  }

  /** Stable label for a write-half cell: {@code write|<format>|<binder>}. */
  public static String writeLabel(String format, Binder binder) {
    return "write|" + format + "|" + binder.name();
  }

  /** Stable label for a setObject(PGobject) cell: {@code pgobject|<format>|<type>}. */
  public static String pgObjectLabel(String format, String typeName) {
    return "pgobject|" + format + "|" + typeName;
  }

  /** Stable label for a CallableStatement read cell: {@code callable|<format>|<type>|<accessor>}. */
  public static String callableLabel(String format, String typeName, CsAccessor accessor) {
    return "callable|" + format + "|" + typeName + "|" + accessor.name();
  }

  /** Stable label for an edge-case read cell: {@code <axis>|<format>|<caseName>|<accessor>}. */
  public static String edgeLabel(String axis, String format, String caseName, Accessor accessor) {
    return axis + "|" + format + "|" + caseName + "|" + accessor.name();
  }

  /** Stable label for a numeric edge-case read cell: {@code numeric-edge|<format>|<caseName>|<accessor>}. */
  public static String numericEdgeLabel(String format, String caseName, Accessor accessor) {
    return edgeLabel("numeric-edge", format, caseName, accessor);
  }

  /**
   * Returns the justification for an expected difference, or {@code null} if the cell is not expected to
   * differ (in which case the oracle treats it as a regression). Point entries win over rules.
   */
  public static @Nullable String accept(String label, ObservableOutcome current,
      ObservableOutcome baseline) {
    String pointReason = POINTS.get(label);
    if (pointReason != null) {
      USED_POINTS.add(label);
      return pointReason;
    }
    for (int i = 0; i < RULES.size(); i++) {
      String reason = RULES.get(i).reason(label, current, baseline);
      if (reason != null) {
        USED_RULES.add(i);
        return reason;
      }
    }
    return null;
  }

  /**
   * Registry entries that never explained a difference during the run: a point entry whose cell no
   * longer differs, or a rule that never fired. An unexpectedly-passing entry means the divergence it
   * guarded has converged (a fix reverted, the wire format changed, or the server version shifted the
   * outcome), so the oracle reports it rather than letting a dead entry mask a future regression.
   */
  public static List<String> unusedEntries() {
    List<String> unused = new ArrayList<>();
    for (String label : POINTS.keySet()) {
      if (!USED_POINTS.contains(label)) {
        unused.add("point never matched: " + label);
      }
    }
    for (int i = 0; i < RULES.size(); i++) {
      if (!USED_RULES.contains(i)) {
        unused.add("rule #" + (i + 1) + " (declaration order) never matched");
      }
    }
    return unused;
  }

  /**
   * Like {@link #accept(String, ObservableOutcome, ObservableOutcome)}, but first applies the
   * server-parity rounding rule for a numeric edge-case cell, which needs the case's {@code value}. The
   * caller passes it in (a {@link BigDecimal}, or {@code null} for {@code NaN}/{@code Infinity}) so the
   * reusable core does not depend on the test-only edge-case catalogue.
   */
  public static @Nullable String acceptNumericEdge(String label, @Nullable BigDecimal value,
      ObservableOutcome current, ObservableOutcome baseline) {
    String rounding = numericRoundingParity(label, value, current, baseline);
    if (rounding != null) {
      return rounding;
    }
    return accept(label, current, baseline);
  }

  /**
   * Like {@link #acceptNumericEdge}, but for a float4/float8 cell: PostgreSQL's {@code float8->intN} cast
   * rounds to nearest, ties to even ({@code rint}), where the baseline truncated toward zero.
   */
  public static @Nullable String acceptFloatEdge(String label, @Nullable BigDecimal value,
      ObservableOutcome current, ObservableOutcome baseline) {
    String rounding = floatRoundingParity(label, value, current, baseline);
    if (rounding != null) {
      return rounding;
    }
    return accept(label, current, baseline);
  }

  /**
   * Accepts a numeric-edge integer-getter cell whose only difference is that the current driver rounds the
   * value half-away-from-zero (PostgreSQL's {@code numeric->intN} cast) where the baseline truncated toward
   * zero. Returns {@code null} for any other cell so the generic registry still applies.
   */
  private static @Nullable String numericRoundingParity(String label, @Nullable BigDecimal value,
      ObservableOutcome current, ObservableOutcome baseline) {
    return roundingParity(label, value, RoundingMode.HALF_UP,
        "integer getter rounds half-away-from-zero (PostgreSQL numeric->intN parity); the baseline "
            + "truncated toward zero",
        current, baseline);
  }

  /**
   * Accepts a float4/float8 integer-getter cell whose only difference is that the current driver rounds the
   * value to nearest, ties to even (PostgreSQL's {@code float8->intN} cast) where the baseline truncated
   * toward zero. Returns {@code null} for any other cell so the generic registry still applies.
   */
  private static @Nullable String floatRoundingParity(String label, @Nullable BigDecimal value,
      ObservableOutcome current, ObservableOutcome baseline) {
    return roundingParity(label, value, RoundingMode.HALF_EVEN,
        "integer getter rounds to nearest, ties to even (PostgreSQL float8->intN parity); the baseline "
            + "truncated toward zero",
        current, baseline);
  }

  /**
   * Accepts an integer-getter cell whose only difference is the rounding {@code mode}: the current driver
   * rounds the value under {@code mode} where the baseline truncated toward zero, and both outcomes match
   * their respective narrowings. Returns {@code null} (so the generic registry still applies) when the
   * label is not an integer getter, the value is absent, or the two roundings coincide at this value.
   */
  private static @Nullable String roundingParity(String label, @Nullable BigDecimal value,
      RoundingMode mode, String reason, ObservableOutcome current, ObservableOutcome baseline) {
    IntGetter getter = intGetterOf(label);
    if (getter == null || value == null) {
      return null;
    }
    BigInteger rounded = value.setScale(0, mode).toBigIntegerExact();
    BigInteger truncated = value.setScale(0, RoundingMode.DOWN).toBigIntegerExact();
    if (rounded.equals(truncated)) {
      // No rounding divergence at this value; a difference here must be explained by something else.
      return null;
    }
    if (getter.outcomeMatches(current, rounded) && getter.outcomeMatches(baseline, truncated)) {
      return reason;
    }
    return null;
  }

  /**
   * The integer getter named by the trailing {@code |<accessor>} segment of a cell label (for a numeric
   * cell, whether {@code numeric-edge|...} or {@code read|...|numeric|...}), or {@code null}. Callers pass a
   * non-null value only for numeric cells, so this need not re-check the type.
   */
  private static @Nullable IntGetter intGetterOf(String label) {
    String accessor = label.substring(label.lastIndexOf('|') + 1);
    for (IntGetter getter : IntGetter.values()) {
      if (getter.accessor.name().equals(accessor)) {
        return getter;
      }
    }
    return null;
  }

  /** A ResultSet integer getter, with the range it accepts and the boxed type it returns. */
  private enum IntGetter {
    BYTE(Accessor.GET_BYTE, Byte.MIN_VALUE, Byte.MAX_VALUE, "java.lang.Byte"),
    SHORT(Accessor.GET_SHORT, Short.MIN_VALUE, Short.MAX_VALUE, "java.lang.Short"),
    INT(Accessor.GET_INT, Integer.MIN_VALUE, Integer.MAX_VALUE, "java.lang.Integer"),
    LONG(Accessor.GET_LONG, Long.MIN_VALUE, Long.MAX_VALUE, "java.lang.Long");

    private final Accessor accessor;
    private final BigInteger min;
    private final BigInteger max;
    private final String className;

    IntGetter(Accessor accessor, long min, long max, String className) {
      this.accessor = accessor;
      this.min = BigInteger.valueOf(min);
      this.max = BigInteger.valueOf(max);
      this.className = className;
    }

    /**
     * True when {@code outcome} is what this getter produces for the integer {@code value}: an
     * out-of-range {@link java.sql.SQLException} when the value does not fit, otherwise the boxed value in
     * {@link OutcomeComparator#normalize}'s {@code className:value} form.
     */
    boolean outcomeMatches(ObservableOutcome outcome, BigInteger value) {
      if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
        return outcome.threw() && ObservableOutcome.FAMILY_SQL.equals(outcome.exceptionFamily());
      }
      return !outcome.threw() && !outcome.wasNull()
          && (className + ":" + value).equals(outcome.value());
    }
  }
}
