/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.PgType;

import org.junit.jupiter.api.Test;

/**
 * Pins the shape of a getter-consistency fuzz failure: it must name the failing type, show the exact
 * input the fuzzer fed (hex wire or escaped text), point at how to reproduce, and keep the original
 * expected/actual as its cause. {@link JqfGetterConsistencyFuzzTest} wraps every finding through these
 * helpers so a red fuzz run is actionable rather than only naming the disagreeing accessor.
 */
class JqfGetterConsistencyReproTest {

  private static final PgType FLOAT4 = PgTypeDescriptors.scalar(Oid.FLOAT4).pgType();

  @Test
  void textReproError_namesDataTypeAndHowToReproduce() {
    AssertionError original =
        new AssertionError("float4 decodeAsInt(char[]) overloads agree ==> expected: <1> but was: <0>");
    AssertionError wrapped = CodecFuzzSupport.textReproError(original, FLOAT4, "0.6");
    String message = wrapped.getMessage();

    assertTrue(message.contains("\"0.6\" (3 chars)"), message);          // the exact data
    assertTrue(message.contains("oid " + Oid.FLOAT4), message);          // the failing type
    assertTrue(message.contains("reproduce"), message);                  // how to reproduce
    assertTrue(message.contains("textGetterConsistency"), message);
    assertTrue(message.contains("consistencyContext"), message);
    assertTrue(message.contains("expected: <1> but was: <0>"), message); // the original cause, inline
    assertSame(original, wrapped.getCause());
  }

  @Test
  void textReproError_escapesNonPrintableInput() {
    // A control char must render as a \\uXXXX escape rather than pass through raw, so the failure stays
    // a single readable line. Build it with (char) 1 to keep the escape out of this source file.
    String input = "x" + ((char) 1) + "y";
    String message = CodecFuzzSupport.textReproError(new AssertionError("boom"), FLOAT4, input).getMessage();
    assertFalse(message.indexOf((char) 1) >= 0, "the raw control char must be escaped, not passed through");
    assertTrue(message.contains("u0001"), message);        // rendered as the escape \\u0001
    assertTrue(message.contains("(3 chars)"), message);    // length reflects the real input
  }

  @Test
  void binaryReproError_showsWireHexAndLength() {
    byte[] wire = {0x3f, 0x19, (byte) 0x99, (byte) 0x9a};
    AssertionError wrapped =
        CodecFuzzSupport.binaryReproError(new AssertionError("boom"), FLOAT4, wire);
    String message = wrapped.getMessage();
    assertTrue(message.contains("0x3f19999a (4 bytes)"), message);
    assertTrue(message.contains("binaryGetterConsistency"), message);
  }
}
