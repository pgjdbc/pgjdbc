/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Coverage-guided outcome-parity properties for the opt-in primitive-capability interfaces
 * ({@code PrimitiveBinaryEncoder}, {@code PrimitiveTextEncoder}, {@code PrimitiveBinaryDecoder},
 * {@code PrimitiveTextDecoder}). Each capability method overrides a boxing default; the property is
 * that the no-box override has the same outcome as the boxing path -- the same bytes/value on
 * success, and the same failure (same SQLState) on a bad input. The round-trip and coercion fuzzers
 * reach these paths only indirectly, where a compensating encode-plus-decode bug survives.
 *
 * <p>Each range-checked codec is fed its wider type -- an {@code int} to int2, a {@code long} to int4
 * -- so the overflow path is actually generated and the "too large a value throws on both paths"
 * property has teeth. The Jazzer counterpart is {@code JazzerPrimitiveCapabilityFuzzTest}.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class PrimitiveCapabilityFuzzTest {

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
