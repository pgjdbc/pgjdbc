/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.api.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.postgresql.jdbc.CodecRegistry;
import org.postgresql.jdbc.ObjectName;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * Freezes the contract of the codec-API <em>default</em> primitive-read methods -- the boxing
 * fallbacks {@link PrimitiveDecoders}{@code .boxToInt/boxToLong/boxToFloat/boxToDouble} and the default
 * {@link BinaryCodec#decodeAsBigDecimal}/{@link TextCodec#decodeAsBigDecimal}. Every built-in codec
 * overrides the primitive accessors, so the fuzzers (which enumerate registered codecs) never reach
 * these defaults; this test drives them directly through synthetic codecs whose {@code decodeBinary}/
 * {@code decodeText} return a fixed {@link Number} without overriding any {@code decodeAs*}.
 *
 * <p>The invariant, on every accessor: a value in range decodes to the exact (truncated-toward-zero)
 * value; a value out of the target's range, or a non-finite {@code NaN}/{@code Infinity}, refuses with
 * a checked {@link SQLException} carrying {@link PSQLState#NUMERIC_VALUE_OUT_OF_RANGE} -- never a silent
 * truncation, never an unchecked {@link NumberFormatException} or other {@link RuntimeException}. The
 * {@code float}/{@code double} accessors, whose target represents {@code NaN}/{@code Infinity}, return
 * them rather than refusing.
 */
class DefaultDecodeInvariantTest {

  private static final String OUT_OF_RANGE = PSQLState.NUMERIC_VALUE_OUT_OF_RANGE.getState();

  // Cases the synthetic codecs decode to. All are Numbers so the accessors reach the numeric defaults
  // (a non-Number would short-circuit to a "cannot convert" error instead).
  private static final long OUT_OF_INT = 3_000_000_000L;              // fits long, overflows int
  private static final BigDecimal OUT_OF_LONG =
      new BigDecimal("1000000000000000000000000000000");              // 1e30, overflows long

  /** A codec that decodes any wire to a fixed {@link Number}, overriding no {@code decodeAs*} method. */
  private static final class FixedNumberCodec implements BinaryCodec, TextCodec {
    private final Number value;

    FixedNumberCodec(Number value) {
      this.value = value;
    }

    @Override
    public String getTypeName() {
      return "synthetic_fixed";
    }

    @Override
    public Class<?> getDefaultJavaType() {
      return value.getClass();
    }

    @Override
    public @Nullable Object decodeBinary(byte[] data, int offset, int length, TypeDescriptor type,
        CodecContext ctx) {
      return value;
    }

    @Override
    public byte[] encodeBinary(Object v, TypeDescriptor type, CodecContext ctx) throws SQLException {
      throw Codecs.cannotEncode(v, "synthetic_fixed");
    }

    @Override
    public @Nullable Object decodeText(String data, TypeDescriptor type, CodecContext ctx) {
      return value;
    }

    @Override
    public String encodeText(Object v, TypeDescriptor type, CodecContext ctx) throws SQLException {
      throw Codecs.cannotEncode(v, "synthetic_fixed");
    }
  }

  // A distinct synthetic OID per fixture, so each resolves to its own codec through the context.
  private static int nextOid = 900_001;

  private final byte[] wire = new byte[8]; // ignored by the synthetic codec

  private Fixture fixture(Number value) {
    int oid = nextOid++;
    PgType type = new PgType(new ObjectName("pg_catalog", "synthetic_fixed"), "synthetic_fixed",
        oid, 'b', 'N', -1, 0, 0, 0);
    FixedNumberCodec codec = new FixedNumberCodec(value);
    CodecRegistry registry = new CodecRegistry();
    registry.registerByOid(oid, codec);
    CodecContext ctx = OfflineCodecs.builder().registry(registry).type(type).build();
    return new Fixture(oid, type, codec, ctx);
  }

  private static final class Fixture {
    final int oid;
    final PgType type;
    final BinaryCodec binary;
    final TextCodec text;
    final CodecContext ctx;

    Fixture(int oid, PgType type, FixedNumberCodec codec, CodecContext ctx) {
      this.oid = oid;
      this.type = type;
      this.binary = codec;
      this.text = codec;
      this.ctx = ctx;
    }
  }

  @FunctionalInterface
  private interface Call {
    void run() throws SQLException;
  }

  private static @Nullable Throwable capture(Call call) {
    try {
      call.run();
      return null;
    } catch (Throwable t) {
      return t;
    }
  }

  /** Asserts {@code call} refuses with a checked out-of-range {@link SQLException}, never silently. */
  private static void assertRefusesOutOfRange(String label, Call call) {
    Throwable t = capture(call);
    assertNotNull(t, label + ": expected an out-of-range SQLException, but the call returned (a value "
        + "was silently truncated or saturated)");
    assertInstanceOf(SQLException.class, t, label + ": must refuse with a checked SQLException, not "
        + t.getClass().getName() + " (" + t.getMessage() + ")");
    assertEquals(OUT_OF_RANGE, ((SQLException) t).getSQLState(),
        label + ": must carry SQLState " + OUT_OF_RANGE);
  }

  // ==================== in range -> exact value ====================

  @Test
  void inRange_decodesExactValueOnEveryAccessor() throws SQLException {
    Fixture f = fixture(42L);
    assertEquals(42, PrimitiveDecoders.asInt(f.binary, wire, f.type, f.ctx));
    assertEquals(42L, PrimitiveDecoders.asLong(f.binary, wire, f.type, f.ctx));
    assertEquals(42.0f, PrimitiveDecoders.asFloat(f.binary, wire, f.type, f.ctx));
    assertEquals(42.0, PrimitiveDecoders.asDouble(f.binary, wire, f.type, f.ctx));
    assertEquals(BigDecimal.valueOf(42),
        PrimitiveDecoders.asBigDecimal(f.binary, wire, 0, wire.length, f.type, f.ctx));
  }

  // ==================== out of range -> refuse, never truncate ====================

  @Test
  void outOfIntRange_getIntRefuses_getLongExact() throws SQLException {
    Fixture f = fixture(OUT_OF_INT);
    assertRefusesOutOfRange("asInt(3e9)", () -> PrimitiveDecoders.asInt(f.binary, wire, f.type, f.ctx));
    // The wider accessor still reads it exactly.
    assertEquals(OUT_OF_INT, PrimitiveDecoders.asLong(f.binary, wire, f.type, f.ctx));
  }

  @Test
  void outOfLongRange_getIntAndGetLongRefuse() {
    Fixture f = fixture(OUT_OF_LONG);
    assertRefusesOutOfRange("asInt(1e30)", () -> PrimitiveDecoders.asInt(f.binary, wire, f.type, f.ctx));
    assertRefusesOutOfRange("asLong(1e30)", () -> PrimitiveDecoders.asLong(f.binary, wire, f.type, f.ctx));
  }

  @Test
  void outOfLongRange_getBigDecimalExact() throws SQLException {
    Fixture f = fixture(OUT_OF_LONG);
    assertEquals(OUT_OF_LONG, PrimitiveDecoders.asBigDecimal(f.binary, wire, 0, wire.length, f.type, f.ctx));
    assertEquals(OUT_OF_LONG, PrimitiveDecoders.asBigDecimal(f.text, "ignored", f.type, f.ctx));
  }

  // ==================== NaN / Infinity ====================

  @Test
  void nan_integerAccessorsRefuse_neverUnchecked() {
    Fixture f = fixture(Double.NaN);
    assertRefusesOutOfRange("asInt(NaN)", () -> PrimitiveDecoders.asInt(f.binary, wire, f.type, f.ctx));
    assertRefusesOutOfRange("asLong(NaN)", () -> PrimitiveDecoders.asLong(f.binary, wire, f.type, f.ctx));
    // The binary and text BigDecimal defaults must refuse, not throw NumberFormatException.
    assertRefusesOutOfRange("decodeAsBigDecimal(NaN,binary)",
        () -> PrimitiveDecoders.asBigDecimal(f.binary, wire, 0, wire.length, f.type, f.ctx));
    assertRefusesOutOfRange("decodeAsBigDecimal(NaN,text)",
        () -> PrimitiveDecoders.asBigDecimal(f.text, "ignored", f.type, f.ctx));
  }

  @Test
  void infinity_integerAccessorsRefuse_neverUnchecked() {
    Fixture f = fixture(Double.POSITIVE_INFINITY);
    assertRefusesOutOfRange("asInt(Inf)", () -> PrimitiveDecoders.asInt(f.binary, wire, f.type, f.ctx));
    assertRefusesOutOfRange("asLong(Inf)", () -> PrimitiveDecoders.asLong(f.binary, wire, f.type, f.ctx));
    assertRefusesOutOfRange("decodeAsBigDecimal(Inf,binary)",
        () -> PrimitiveDecoders.asBigDecimal(f.binary, wire, 0, wire.length, f.type, f.ctx));
    assertRefusesOutOfRange("decodeAsBigDecimal(Inf,text)",
        () -> PrimitiveDecoders.asBigDecimal(f.text, "ignored", f.type, f.ctx));
  }

  @Test
  void nonFinite_floatAndDoubleAccessorsReturnThem() throws SQLException {
    Fixture nan = fixture(Double.NaN);
    assertEquals(Double.doubleToRawLongBits(Double.NaN),
        Double.doubleToRawLongBits(PrimitiveDecoders.asDouble(nan.binary, wire, nan.type, nan.ctx)));
    assertEquals(Float.floatToRawIntBits(Float.NaN),
        Float.floatToRawIntBits(PrimitiveDecoders.asFloat(nan.binary, wire, nan.type, nan.ctx)));

    Fixture inf = fixture(Double.POSITIVE_INFINITY);
    assertEquals(Double.POSITIVE_INFINITY, PrimitiveDecoders.asDouble(inf.binary, wire, inf.type, inf.ctx));
    assertEquals(Float.POSITIVE_INFINITY, PrimitiveDecoders.asFloat(inf.binary, wire, inf.type, inf.ctx));
  }

  // ==================== truncation toward zero, in range ====================

  @Test
  void fractionalInRange_truncatesTowardZero() throws SQLException {
    // A fractional value inside int/long range truncates (matching the built-in numeric codecs), it
    // does not refuse -- only out-of-range and non-finite refuse.
    Fixture f = fixture(new BigDecimal("2.75"));
    assertEquals(2, PrimitiveDecoders.asInt(f.binary, wire, f.type, f.ctx));
    assertEquals(2L, PrimitiveDecoders.asLong(f.binary, wire, f.type, f.ctx));
    Fixture negative = fixture(-2.75);
    assertEquals(-2, PrimitiveDecoders.asInt(negative.binary, wire, negative.type, negative.ctx));
    assertEquals(-2L, PrimitiveDecoders.asLong(negative.binary, wire, negative.type, negative.ctx));
  }

  // ==================== Codecs.decode(.., Class) never truncates ====================

  @Test
  void decodeAsClass_neverSilentlyTruncates() {
    // The class-targeted decode casts rather than narrows: a Long resolved as Integer.class refuses
    // instead of returning a truncated int.
    Fixture f = fixture(OUT_OF_INT);
    Throwable t = capture(() -> Codecs.decode(RawValue.binary(wire), f.type, f.ctx, Integer.class));
    assertNotNull(t, "Codecs.decode(Integer.class) on a Long must refuse, not truncate");
    assertInstanceOf(SQLException.class, t,
        "Codecs.decode(Integer.class) must refuse with a checked SQLException, not " + t.getClass().getName());
    // Its own type resolves cleanly.
  }

  @Test
  void decodeAsClass_ownTypeSucceeds() throws SQLException {
    Fixture f = fixture(OUT_OF_INT);
    assertEquals(OUT_OF_INT, Codecs.decode(RawValue.binary(wire), f.type, f.ctx, Long.class));
  }
}
