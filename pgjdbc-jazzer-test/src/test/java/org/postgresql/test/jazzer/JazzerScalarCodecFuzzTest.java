/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.PgType;
import org.postgresql.util.PGInterval;
import org.postgresql.util.PGobject;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

import java.math.BigDecimal;
import java.sql.SQLException;

/**
 * The Jazzer counterpart of {@code ScalarCodecFuzzTest} in pgjdbc-jqf-test: the same coverage-guided
 * round-trip properties for scalar codecs and the named-composite shape, over the shared fuzzkit oracle
 * ({@link CodecFuzzSupport}) and the shared descriptors ({@link PgTypeDescriptors}). The two test classes
 * are line-for-line comparable -- same targets, same oracle -- differing only in how arguments arrive.
 *
 * <p>Jazzer's mutation framework maps the primitives, {@link String}, and {@code byte[]} straight from the
 * {@code @FuzzTest} signature: no {@code PgValueArgumentsFactory} to write and no
 * {@code @FuzzTest(arguments = ...)} to wire. {@code numeric} is the one scalar the stock mutators do not
 * build ({@link BigDecimal}), so it draws an unscaled long and a small scale from a
 * {@link FuzzedDataProvider} -- the direct analogue of the jetCheck {@code NUMERIC} generator, inline.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jazzer-test:test}; fuzz one target with
 * {@code gradle :pgjdbc-jazzer-test:test -Pjazzer.fuzz=1 --tests '*int4RoundTrip'}.
 */
class JazzerScalarCodecFuzzTest {

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
  void textRoundTrip(@NotNull String value) throws SQLException {
    CodecFuzzSupport.roundTrip(value, PgTypeDescriptors.scalar(Oid.TEXT).pgType(), String.class,
        CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void byteaRoundTrip(byte @NotNull [] value) throws SQLException {
    CodecFuzzSupport.byteaRoundTrip(value, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void numericRoundTrip(@NotNull FuzzedDataProvider data) throws SQLException {
    BigDecimal value = BigDecimal.valueOf(data.consumeLong(), data.consumeInt(0, 12));
    CodecFuzzSupport.numericRoundTrip(value, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void regularStructRoundTrip(int x, int y, @NotNull String label) throws SQLException {
    PgType point = PgTypeDescriptors.composite(PgTypeDescriptors.POINT_OID).pgType();
    CodecFuzzSupport.structRoundTrip(point, new Object[]{x, y, label}, CodecFuzzSupport.with(point));
  }

  // The PGobject / PGInterval scalars (blind spot Z6): json, jsonb, bit, varbit, interval. Their types
  // are not registered descriptors (they carry a codec but no coercion row), so the PgType is built
  // inline from the pinned built-in OID. The stock mutators build neither PGobject nor PGInterval, so
  // each value is drawn from the FuzzedDataProvider through JazzerValues -- the Jazzer counterpart of
  // pgjdbc-jqf-test's PgValueArgumentsFactory generators, with the same round-trip fidelity constraints.

  @FuzzTest
  void jsonRoundTrip(@NotNull FuzzedDataProvider data) throws SQLException {
    PGobject value = pgObject("json", JazzerValues.jsonLiteral(data));
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.JSON, "json", 'U'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void jsonbRoundTrip(@NotNull FuzzedDataProvider data) throws SQLException {
    PGobject value = pgObject("jsonb", JazzerValues.jsonLiteral(data));
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.JSONB, "jsonb", 'U'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void bitRoundTrip(@NotNull FuzzedDataProvider data) throws SQLException {
    PGobject value = pgObject("bit", JazzerValues.bitString(data));
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.BIT, "bit", 'V'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void varbitRoundTrip(@NotNull FuzzedDataProvider data) throws SQLException {
    PGobject value = pgObject("varbit", JazzerValues.bitString(data));
    CodecFuzzSupport.roundTrip(value, CodecFuzzSupport.scalar(Oid.VARBIT, "varbit", 'V'),
        PGobject.class, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void intervalRoundTrip(@NotNull FuzzedDataProvider data) throws SQLException {
    CodecFuzzSupport.roundTrip(JazzerValues.interval(data),
        CodecFuzzSupport.scalar(Oid.INTERVAL, "interval", 'T'), PGInterval.class,
        CodecFuzzSupport.builtins());
  }

  private static PGobject pgObject(String type, String value) throws SQLException {
    PGobject obj = new PGobject();
    obj.setType(type);
    obj.setValue(value);
    return obj;
  }
}
