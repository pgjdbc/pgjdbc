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
 * Coverage-guided parity properties for the opt-in primitive-capability interfaces
 * ({@code PrimitiveBinaryEncoder}, {@code PrimitiveTextEncoder}, {@code PrimitiveBinaryDecoder},
 * {@code PrimitiveTextDecoder}). Each capability method overrides a boxing default; the property is
 * that the no-box override produces the same result as the boxing path -- byte-for-byte for encode,
 * value-for-value for decode -- for every generated value, and that the slice decoders honour a
 * non-zero offset. The round-trip and coercion fuzzers reach these paths only indirectly, where a
 * compensating encode-plus-decode bug would survive; this asserts each half against its boxing twin.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=10000}.
 */
class PrimitiveCapabilityFuzzTest {

  @FuzzTest
  void int2Parity(short value) throws SQLException {
    CodecFuzzSupport.intPrimitiveParity(value, Oid.INT2, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void int4Parity(int value) throws SQLException {
    CodecFuzzSupport.intPrimitiveParity(value, Oid.INT4, CodecFuzzSupport.builtins());
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
