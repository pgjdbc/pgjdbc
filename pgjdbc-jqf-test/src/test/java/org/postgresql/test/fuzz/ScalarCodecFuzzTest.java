/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.PgType;

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
}
