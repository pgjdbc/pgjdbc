/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.util.PGRange;
import org.postgresql.util.PGmultirange;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided properties for the delegating codecs -- domain, range, and multirange -- which
 * encode by forwarding to an inner codec.
 *
 * <p>Domain uses {@link CodecFuzzSupport#domainRoundTrip}: its {@code byte[]}/{@code String} form
 * materialises the base value while its streaming form streams the base, so the round-trip's
 * {@code encodeParity} leg has teeth. Range and multirange buffer their own streaming form into the
 * {@code byte[]} path, so the streaming-vs-materialising bound branch is otherwise unreachable in a
 * test where every registered subtype streams; {@link CodecFuzzSupport#rangeStreamParity} and
 * {@link CodecFuzzSupport#multirangeStreamParity} force the materialising branch through a de-streamed
 * context and pin its wire bytes against the streaming ones.
 *
 * <p>Values are built from the stock {@code jqf-generator-jetcheck} primitives, so no custom
 * arguments factory is needed. Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test};
 * fuzz with {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class DelegatingCodecFuzzTest {

  // Built-in range and multirange OIDs (pg_type). Offline codec resolution keys on typtype and the
  // type name alias, so these travel with the type name into the synthetic PgType.
  private static final int INT4RANGE_OID = 3904;
  private static final int INT8RANGE_OID = 3926;
  private static final int INT4MULTIRANGE_OID = 4451;

  // --- Domain: round-trip plus streaming-vs-materialising base parity ------------------------

  @FuzzTest
  void domainOverInt4RoundTrip(int value) throws SQLException {
    CodecFuzzSupport.domainRoundTrip(Oid.INT4, "int4", 'N', value, Integer.class);
  }

  @FuzzTest
  void domainOverInt8RoundTrip(long value) throws SQLException {
    CodecFuzzSupport.domainRoundTrip(Oid.INT8, "int8", 'N', value, Long.class);
  }

  @FuzzTest
  void domainOverTextRoundTrip(String value) throws SQLException {
    CodecFuzzSupport.domainRoundTrip(Oid.TEXT, "text", 'S', value, String.class);
  }

  @FuzzTest
  void domainOverBoolRoundTrip(boolean value) throws SQLException {
    CodecFuzzSupport.domainRoundTrip(Oid.BOOL, "bool", 'B', value, Boolean.class);
  }

  // --- Range: streaming bound path equals the materialising one ------------------------------

  @FuzzTest
  void int4rangeStreamParity(int a, int b, int shape) throws SQLException {
    CodecFuzzSupport.rangeStreamParity(INT4RANGE_OID, "int4range", Oid.INT4, intRange(a, b, shape));
  }

  @FuzzTest
  void int8rangeStreamParity(long a, long b, int shape) throws SQLException {
    CodecFuzzSupport.rangeStreamParity(INT8RANGE_OID, "int8range", Oid.INT8, longRange(a, b, shape));
  }

  // --- Multirange: element range streaming equals the materialising path ---------------------

  @FuzzTest
  void int4multirangeStreamParity(int a, int b, int c, int d) throws SQLException {
    PGmultirange<Integer> value = new PGmultirange<>(finiteIntRange(a, b), finiteIntRange(c, d));
    CodecFuzzSupport.multirangeStreamParity(INT4MULTIRANGE_OID, "int4multirange",
        INT4RANGE_OID, "int4range", Oid.INT4, value);
  }

  /**
   * Builds an {@code int4} range from two draws and a shape selector: mostly a finite range with
   * both inclusivity flags drawn from {@code shape}, plus the empty range and a lower-unbounded range
   * so the encoder's empty-flag and infinite-bound paths are exercised alongside the bound framing.
   */
  private static PGRange<Integer> intRange(int a, int b, int shape) {
    switch (Math.floorMod(shape, 6)) {
      case 0:
        return new PGRange<>();
      case 1:
        // Lower-unbounded: the encoder writes only the upper bound.
        return new PGRange<>(null, Math.max(a, b), false, true);
      default:
        return finiteIntRange(a, b, shape);
    }
  }

  private static PGRange<Long> longRange(long a, long b, int shape) {
    switch (Math.floorMod(shape, 6)) {
      case 0:
        return new PGRange<>();
      case 1:
        return new PGRange<>(null, Math.max(a, b), false, true);
      default:
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        return new PGRange<>(lo, hi, (shape & 1) == 0, (shape & 2) == 0);
    }
  }

  private static PGRange<Integer> finiteIntRange(int a, int b) {
    return finiteIntRange(a, b, 0);
  }

  private static PGRange<Integer> finiteIntRange(int a, int b, int shape) {
    int lo = Math.min(a, b);
    int hi = Math.max(a, b);
    return new PGRange<>(lo, hi, (shape & 1) == 0, (shape & 2) == 0);
  }
}
