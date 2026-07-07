/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;

import com.code_intelligence.jazzer.junit.FuzzTest;

import java.sql.SQLException;

/**
 * The Jazzer counterpart of {@code PrimitiveCapabilityFuzzTest} in pgjdbc-jqf-test: the same
 * outcome-parity properties for the primitive-capability interfaces, over the shared fuzzkit oracle
 * ({@link CodecFuzzSupport}). The two classes are line-for-line comparable -- same targets, same
 * oracle -- differing only in how arguments arrive. Jazzer's mutators map the primitives straight from
 * the {@code @FuzzTest} signature, so the range-checked codecs see values well outside their range and
 * the "too large a value throws on both paths" property is exercised.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jazzer-test:test}; fuzz one target with
 * {@code gradle :pgjdbc-jazzer-test:test -Pjazzer.fuzz=1 --tests '*int2Parity'}.
 */
class JazzerPrimitiveCapabilityFuzzTest {

  @FuzzTest
  void int2Parity(int value) throws SQLException {
    CodecFuzzSupport.intPrimitiveParity(value, Oid.INT2, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int2WideParity(long value) throws SQLException {
    CodecFuzzSupport.longPrimitiveParity(value, Oid.INT2, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int4Parity(int value) throws SQLException {
    CodecFuzzSupport.intPrimitiveParity(value, Oid.INT4, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int4WideParity(long value) throws SQLException {
    CodecFuzzSupport.longPrimitiveParity(value, Oid.INT4, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int8Parity(long value) throws SQLException {
    CodecFuzzSupport.longPrimitiveParity(value, Oid.INT8, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void oidParity(long value) throws SQLException {
    // oid is an unsigned 32-bit value; mask so encodeBinary produces a canonical 4-byte wire.
    CodecFuzzSupport.longPrimitiveParity(value & 0xFFFFFFFFL, Oid.OID, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void float4Parity(float value) throws SQLException {
    CodecFuzzSupport.floatPrimitiveParity(value, Oid.FLOAT4, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void float8Parity(double value) throws SQLException {
    CodecFuzzSupport.doublePrimitiveParity(value, Oid.FLOAT8, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void boolParity(boolean value) throws SQLException {
    CodecFuzzSupport.booleanPrimitiveParity(value, Oid.BOOL, CodecFuzzSupport.builtins());
  }
}
