/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.geometric.PGbox;
import org.postgresql.geometric.PGcircle;
import org.postgresql.geometric.PGline;
import org.postgresql.geometric.PGlseg;
import org.postgresql.geometric.PGpath;
import org.postgresql.geometric.PGpoint;
import org.postgresql.geometric.PGpolygon;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGobject;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetTime;

/**
 * Coverage-guided round-trip properties for scalar codecs and the regular (named composite) struct
 * shape. Primitive and {@link String} parameters come from the auto-discovered
 * {@code jqf-generator-jetcheck} provider; {@code bytea} and {@code numeric} need
 * {@link PgValueArgumentsFactory} because the stock provider does not build {@code byte[]} or
 * {@link BigDecimal}.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class JqfScalarCodecFuzzTest {

  @FuzzTest
  void int2RoundTrip(short value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.INT2).pgType(), Short.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int4RoundTrip(int value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.INT4).pgType(), Integer.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int8RoundTrip(long value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.INT8).pgType(), Long.class,
        CodecFuzzSupport.builtins());
  }

  // oid8 (PostgreSQL 18+, blind spot Z6): an unsigned 64-bit object identifier, represented by its raw
  // bit pattern (see Oid8Codec). encode/decode are exact inverses over the whole long domain, so the
  // generic byte-for-byte roundTrip oracle applies unmodified -- unlike oid8Parity in
  // GeneratedPrimitiveCapabilityFuzzTest, which independently probes the unsigned text form and so needs
  // unsignedLongPrimitiveParity instead of longPrimitiveParity. Not a registered descriptor (pinned
  // built-in codec, no coercion row), so the PgType is built inline like the PGobject/geometric scalars
  // below.

  @FuzzTest
  void oid8RoundTrip(long value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.OID8, "oid8", 'N'), Long.class,
        CodecFuzzSupport.builtins());
  }

  // xid8 (PostgreSQL 13+, blind spot Z6): an unsigned 64-bit transaction ID, sharing oid8's wire
  // shape and codec design (see Xid8Codec) -- same rationale as oid8RoundTrip above.

  @FuzzTest
  void xid8RoundTrip(long value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.XID8, "xid8", 'U'), Long.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void float4RoundTrip(float value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.FLOAT4).pgType(), Float.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void float8RoundTrip(double value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.FLOAT8).pgType(), Double.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void boolRoundTrip(boolean value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.BOOL).pgType(), Boolean.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void textRoundTrip(String value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.TEXT).pgType(), String.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void byteaRoundTrip(byte[] value) throws SQLException {
    CodecFuzzSupport.byteaRoundTrip(value, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void numericRoundTrip(BigDecimal value) throws SQLException {
    CodecFuzzSupport.numericRoundTrip(value, CodecFuzzSupport.builtins());
  }

  // numeric under a column modifier numeric(p,s): decoding through a typmod-carrying descriptor
  // rescales the value to the declared scale (NumericCodec.applyTypmodScale), including a negative
  // scale on PG15+. The unscaled long and scale draw the value like the NUMERIC generator; the
  // precision and scale draw the modifier. floorMod keeps the jetCheck ints in the valid ranges.
  @FuzzTest
  void numericTypmodRoundTrip(long unscaled, int valueScale, int precision, int scale)
      throws SQLException {
    BigDecimal value = BigDecimal.valueOf(unscaled, Math.floorMod(valueScale, 13));
    CodecFuzzSupport.numericTypmodRoundTrip(value, 1 + Math.floorMod(precision, 1000),
        Math.floorMod(scale + 10, 31) - 10, CodecFuzzSupport.builtins());
  }

  // numeric(p,s)[]: the array column modifier is the element modifier, so getArray() rescales every
  // element to the declared scale. Two elements sharing a wire scale exercise the element walk.
  @FuzzTest
  void numericArrayTypmodRoundTrip(long a, long b, int valueScale, int precision, int scale)
      throws SQLException {
    int s = Math.floorMod(valueScale, 13);
    BigDecimal[] values = {BigDecimal.valueOf(a, s), BigDecimal.valueOf(b, s)};
    CodecFuzzSupport.numericArrayTypmodRoundTrip(values, 1 + Math.floorMod(precision, 1000),
        Math.floorMod(scale + 10, 31) - 10, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void regularStructRoundTrip(int x, int y, String label) throws SQLException {
    PgType point = PgTypeDescriptors.composite(PgTypeDescriptors.POINT_OID).pgType();
    CodecFuzzSupport.structRoundTrip(point, new Object[]{x, y, label}, CodecFuzzSupport.with(point));
  }

  // The PGobject / PGInterval scalars (blind spot Z6): json, jsonb, bit, varbit, interval. Their types
  // are not registered descriptors (they carry a codec but no coercion row), so the PgType is built
  // inline from the pinned built-in OID. The values come from PgValueArgumentsFactory because the stock
  // provider builds neither PGobject nor PGInterval; each generator draws a value that round-trips by
  // equals in both formats (see the factory's field comments for the fidelity constraints).

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void jsonRoundTrip(FuzzJson value) throws SQLException {
    CodecFuzzSupport.roundTrip(value.value(), CodecFuzzSupport.scalar(Oid.JSON, "json", 'U'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void jsonbRoundTrip(FuzzJson value) throws SQLException {
    CodecFuzzSupport.roundTrip(value.value(), CodecFuzzSupport.scalar(Oid.JSONB, "jsonb", 'U'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void bitRoundTrip(FuzzBit value) throws SQLException {
    CodecFuzzSupport.roundTrip(value.value(), CodecFuzzSupport.scalar(Oid.BIT, "bit", 'V'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void varbitRoundTrip(FuzzBit value) throws SQLException {
    CodecFuzzSupport.roundTrip(value.value(), CodecFuzzSupport.scalar(Oid.VARBIT, "varbit", 'V'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void intervalRoundTrip(PGInterval value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.INTERVAL, "interval", 'T'),
        PGInterval.class, CodecFuzzSupport.builtins());
  }

  // timetz getString parity (the class of the fixed binary-offset bug): roundTrip folds in
  // crossFormatString, so decodeAsString over text and binary must agree. timetz carries its own
  // offset, so unlike timestamptz (an instant rendered in the session zone, where the offline text
  // and binary string forms legitimately differ) the offline check is unambiguous. timestamptz
  // decode-to-string is covered against the live server by ServerTruthOracleTest instead.
  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void timetzRoundTrip(OffsetTime value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.TIMETZ).pgType(),
        OffsetTime.class, CodecFuzzSupport.builtins());
  }

  // The geometric types (blind spot Z6, U7b): point, line, lseg, box, path, polygon, circle. Like the
  // PGobject scalars above they carry a codec but no coercion row, so the PgType is built inline from
  // the pinned built-in OID and the value comes from PgValueArgumentsFactory (the stock provider builds
  // no PG geometric type). All seven are binary-capable, so they round-trip in both formats.

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void pointRoundTrip(PGpoint value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.POINT, "point", 'G'),
        PGpoint.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void boxRoundTrip(PGbox value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.BOX, "box", 'G'),
        PGbox.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void lineRoundTrip(PGline value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.LINE, "line", 'G'),
        PGline.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void lsegRoundTrip(PGlseg value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.LSEG, "lseg", 'G'),
        PGlseg.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void pathRoundTrip(PGpath value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.PATH, "path", 'G'),
        PGpath.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void polygonRoundTrip(PGpolygon value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.POLYGON, "polygon", 'G'),
        PGpolygon.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void circleRoundTrip(PGcircle value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.CIRCLE, "circle", 'G'),
        PGcircle.class, CodecFuzzSupport.builtins());
  }
}
