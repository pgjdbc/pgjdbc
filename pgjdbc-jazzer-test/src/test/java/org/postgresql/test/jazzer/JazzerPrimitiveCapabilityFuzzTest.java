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

  // A domain forwards its base type's primitive accessors, so a domain over a pure codec inherits the
  // same outcome parity -- the no-box path that DomainCodec restores.

  @FuzzTest
  void domainOverInt4Parity(int value) throws SQLException {
    CodecFuzzSupport.domainIntPrimitiveParity(value, Oid.INT4, "int4", 'N');
  }

  @FuzzTest
  void domainOverInt8Parity(long value) throws SQLException {
    CodecFuzzSupport.domainLongPrimitiveParity(value, Oid.INT8, "int8", 'N');
  }

  @FuzzTest
  void domainOverFloat8Parity(double value) throws SQLException {
    CodecFuzzSupport.domainDoublePrimitiveParity(value, Oid.FLOAT8, "float8", 'N');
  }

  @FuzzTest
  void domainOverBoolParity(boolean value) throws SQLException {
    CodecFuzzSupport.domainBooleanPrimitiveParity(value, Oid.BOOL, "bool", 'B');
  }

  // A narrowing accessor (int8 -> int) is NOT outcome-parity with its boxing default, which truncates
  // via Long.intValue(); instead it must reject an out-of-range value rather than silently truncate.

  @FuzzTest
  void int8NarrowingRejectsOverflow(long value) throws SQLException {
    CodecFuzzSupport.int8NarrowingRejectsOverflow(value, CodecFuzzSupport.builtins());
  }
}
