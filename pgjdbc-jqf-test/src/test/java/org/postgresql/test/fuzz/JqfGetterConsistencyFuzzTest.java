/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.jdbc.PgType;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Getter-consistency properties for the primitive decode accessors: on one fuzzed wire, the
 * {@code int}/{@code long}/{@code float}/{@code double} views of a single codec must agree through the
 * standard widening/narrowing casts, and the overloads of one accessor (String vs char[] vs
 * {@code decodeTextBytesAs*}, byte[] at offset 0 vs at an offset) must return the same value. Unlike the
 * parity fuzzers -- which pin only each codec's native-width accessor -- this drives the cross-width
 * accessors ({@code float8.decodeAsInt}, {@code int8.decodeAsFloat}) and the codecs the parity suite
 * never touched ({@code numeric}, {@code money}, {@code bit}), across every pinned primitive-decoder
 * codec at once. The oracle and the property details live in {@link CodecFuzzSupport}.
 *
 * <p>The codec list is derived from the registry ({@link CodecFuzzSupport#primitiveDecoderTypes()}), so
 * a newly pinned primitive-decoder codec joins this fuzzer automatically;
 * {@code PrimitiveDecoderCoverageArchTest} guards that nothing is silently missed.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=100000}.
 */
class JqfGetterConsistencyFuzzTest {

  @FuzzTest(arguments = PgValueArgumentsFactory.class)
  void binary(byte[] wire) throws SQLException {
    CodecContext ctx = CodecFuzzSupport.consistencyContext();
    for (PgType type : CodecFuzzSupport.primitiveDecoderTypes()) {
      try {
        CodecFuzzSupport.binaryGetterConsistency(type, wire, ctx);
      } catch (AssertionError failure) {
        // Re-throw naming the failing type and the exact wire, with a repro recipe, so a fuzz finding
        // says which data it happened on rather than only which accessor disagreed.
        throw CodecFuzzSupport.binaryReproError(failure, type, wire);
      }
    }
  }

  @FuzzTest
  void text(String text) throws SQLException {
    CodecContext ctx = CodecFuzzSupport.consistencyContext();
    for (PgType type : CodecFuzzSupport.primitiveDecoderTypes()) {
      try {
        CodecFuzzSupport.textGetterConsistency(type, text, ctx);
      } catch (AssertionError failure) {
        throw CodecFuzzSupport.textReproError(failure, type, text);
      }
    }
  }
}
