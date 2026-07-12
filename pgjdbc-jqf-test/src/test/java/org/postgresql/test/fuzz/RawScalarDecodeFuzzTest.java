/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.api.codec.RawValue;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;
import java.util.Arrays;

/**
 * Prototype (Axis 4): a raw-bytes decode fuzzer that hands <em>arbitrary</em> bytes to a scalar
 * codec's binary and text decode paths, rather than only the canonical wire the codec itself
 * produced. This reaches the decode-side error branches that the canonical-wire fuzzers cannot
 * exercise in principle -- the length guards, the malformed-number fallbacks, the truncated-input
 * paths -- and asserts the decode contract holds on every input.
 *
 * <p>The invariant is intentionally weak, because arbitrary bytes are usually not a valid value: a
 * decode must return a value or refuse with a clean {@link SQLException}. It must never leak an
 * unchecked exception ({@link RuntimeException}) or an {@link Error} (a wire-driven allocation or an
 * unbounded loop). When a decode does return, a second invariant applies -- idempotence:
 * {@code decode(encode(decode(b))) == decode(b)}. Re-encoding the decoded value and decoding again
 * must reproduce the same value, so a codec cannot decode one thing and re-encode another.
 *
 * <p>Scope: this prototype fuzzes only <em>scalar</em> codecs, including the now-bounded
 * {@code numeric}/{@code bit}/{@code varbit} binary decoders (roadmap phase F1). The multi-element
 * container codecs (array, composite, range, multirange) read element counts and dimensions straight
 * from the wire; roadmap phase F1 also gave those a decode-side allocation bound, but a dedicated
 * bounded fuzzer over them is roadmap phase U4. The adversarial <em>text</em> grammars are covered by
 * {@link RawTextLiteralDecodeFuzzTest}.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=100000}.
 */
class RawScalarDecodeFuzzTest {

  /**
   * OIDs whose re-encode is bit-exact, so both the no-leak invariant and idempotence apply in both
   * formats. The type name is irrelevant -- {@link Codecs#decode} resolves the codec by OID -- so a
   * bare scalar descriptor suffices. These decoders are fixed-width or read the whole buffer, and
   * their round-trip preserves the value exactly (no scale or sub-second normalisation).
   */
  private static final int[] IDEMPOTENT_OIDS = {
      Oid.INT2, Oid.INT4, Oid.INT8, Oid.OID, Oid.OID8, Oid.XID8, Oid.BOOL, Oid.BYTEA, Oid.TEXT,
      Oid.VARCHAR, Oid.BPCHAR, Oid.NAME, Oid.UUID,
  };

  /**
   * OIDs fuzzed in both formats under the no-leak invariant only. Their decode is safe on arbitrary
   * binary (no unbounded wire-length allocation), but re-encode may normalise the value -- floats round
   * to their shortest form, temporal types drop sub-second precision -- so idempotence does not hold for
   * a value reached from arbitrary bytes and is not asserted.
   *
   * <p>{@code numeric}, {@code bit} and {@code varbit} join this group: their binary decoders read a
   * digit or bit count straight from the wire, which used to allocate on it without a sanity bound and
   * so were fuzzed in text only. Roadmap phase F1 bounded those reads (numeric refuses a malformed
   * length, bit/varbit validate the bit count against the bytes present), so arbitrary binary now
   * refuses cleanly rather than driving an {@link OutOfMemoryError}.</p>
   */
  private static final int[] NO_LEAK_BOTH_OIDS = {
      Oid.FLOAT4, Oid.FLOAT8, Oid.DATE, Oid.TIME, Oid.TIMETZ, Oid.TIMESTAMP, Oid.TIMESTAMPTZ,
      Oid.INTERVAL, Oid.JSON, Oid.JSONB, Oid.NUMERIC, Oid.BIT, Oid.VARBIT,
  };

  private static PgType scalar(int oid) {
    return new PgType(new ObjectName("pg_catalog", "t" + oid), "t" + oid, oid, 'b', 'N', -1, 0, 0, 0);
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void decodeRawBytes(byte[] payload) throws SQLException {
    PgCodecContext ctx = (PgCodecContext) OfflineCodecs.builder().build();
    for (int oid : IDEMPOTENT_OIDS) {
      PgType type = scalar(oid);
      decode(RawValue.binary(payload), type, ctx, Format.BINARY, true);
      decode(RawValue.text(payload), type, ctx, Format.TEXT, true);
    }
    for (int oid : NO_LEAK_BOTH_OIDS) {
      PgType type = scalar(oid);
      decode(RawValue.binary(payload), type, ctx, Format.BINARY, false);
      decode(RawValue.text(payload), type, ctx, Format.TEXT, false);
    }
  }

  /**
   * Decodes the raw value and checks the no-leak invariant; when {@code checkIdempotence} and the
   * decode returned, also checks {@code decode(encode(decode(b))) == decode(b)}. Any unchecked leak or
   * {@link Error} fails the property; a clean {@link SQLException} is accepted (the weak invariant).
   */
  private static void decode(RawValue raw, PgType type, PgCodecContext ctx, Format format,
      boolean checkIdempotence) {
    Object first;
    try {
      first = Codecs.decode(raw, type, ctx, Object.class);
    } catch (SQLException refused) {
      return;
    } catch (RuntimeException leak) {
      throw new AssertionError("decode " + format + " of t" + type.getOid() + " leaked "
          + leak.getClass().getName() + " (expected only SQLException)", leak);
    }
    if (!checkIdempotence) {
      return;
    }
    if (first == null) {
      return;
    }
    // Idempotence: re-encoding the decoded value and decoding again must reproduce it. A re-encode
    // may legitimately refuse (a decoded poison value with no wire form), in which case the check is
    // vacuous; but if it encodes, the second decode must equal the first.
    RawValue reencoded;
    try {
      reencoded = Codecs.encode(first, type, ctx, format);
    } catch (SQLException cannotReencode) {
      return;
    }
    Object second;
    try {
      second = Codecs.decode(reencoded, type, ctx, Object.class);
    } catch (SQLException refused) {
      throw new AssertionError("decode(encode(decode(b))) refused on t" + type.getOid() + " "
          + format + " though decode(b) returned " + describe(first), refused);
    }
    if (!valueEquals(first, second)) {
      throw new AssertionError("decode not idempotent on t" + type.getOid() + " " + format
          + ": decode(b)=" + describe(first) + " but decode(encode(decode(b)))=" + describe(second));
    }
  }

  private static boolean valueEquals(Object a, Object b) {
    if (a instanceof byte[] && b instanceof byte[]) {
      return Arrays.equals((byte[]) a, (byte[]) b);
    }
    if (a instanceof java.math.BigDecimal && b instanceof java.math.BigDecimal) {
      // Compare by value: re-encoding may normalise the scale (1E+2 vs 100) without losing the value.
      return ((java.math.BigDecimal) a).compareTo((java.math.BigDecimal) b) == 0;
    }
    return a == null ? b == null : a.equals(b);
  }

  private static String describe(Object v) {
    return v instanceof byte[] ? Arrays.toString((byte[]) v) : String.valueOf(v);
  }
}
