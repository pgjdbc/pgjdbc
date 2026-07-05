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
class ScalarCodecFuzzTest {

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

  // The geometric types (blind spot Z6, U7b): point, line, lseg, box, path, polygon, circle. Like the
  // PGobject scalars above they carry a codec but no coercion row, so the PgType is built inline from
  // the pinned built-in OID and the value comes from PgValueArgumentsFactory (the stock provider builds
  // no PG geometric type). point and box are binary-capable, so they round-trip in both formats; the
  // other five have a text-only codec, so they use roundTripText -- roundTrip's binary leg would fail
  // for a type with no binary codec, not because the value is lossy.

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
    CodecFuzzSupport.roundTripText(value, CodecFuzzSupport.scalar(Oid.LINE, "line", 'G'),
        PGline.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void lsegRoundTrip(PGlseg value) throws SQLException {
    CodecFuzzSupport.roundTripText(value, CodecFuzzSupport.scalar(Oid.LSEG, "lseg", 'G'),
        PGlseg.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void pathRoundTrip(PGpath value) throws SQLException {
    CodecFuzzSupport.roundTripText(value, CodecFuzzSupport.scalar(Oid.PATH, "path", 'G'),
        PGpath.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void polygonRoundTrip(PGpolygon value) throws SQLException {
    CodecFuzzSupport.roundTripText(value, CodecFuzzSupport.scalar(Oid.POLYGON, "polygon", 'G'),
        PGpolygon.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void circleRoundTrip(PGcircle value) throws SQLException {
    CodecFuzzSupport.roundTripText(value, CodecFuzzSupport.scalar(Oid.CIRCLE, "circle", 'G'),
        PGcircle.class, CodecFuzzSupport.builtins());
  }
}
