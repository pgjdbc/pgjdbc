/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.core.Oid;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Int2EdgeCases;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.NumericEdgeCases;
import org.postgresql.test.data.OidEdgeCases;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;

/**
 * Pins the known-bad half of the text-literal decode contract: every literal in a catalogue's
 * {@code MALFORMED} list must refuse with an {@link java.sql.SQLException}, in the same offline decode the
 * text-literal fuzzers drive.
 *
 * <p>The fuzzers themselves ({@code JqfTextLiteralDecodeFuzzTest}, {@code JazzerTextLiteralDecodeFuzzTest},
 * and the generated {@code *_text} scalar targets) hold only the weak invariant -- an arbitrary string must
 * not leak an unchecked exception -- because for a string a generator composed, refusal is not the known
 * answer. A curated malformed literal has one, so it is checked here with
 * {@link CodecFuzzSupport#decodeTextExpectingRefusal}: a parser that silently accepts a literal
 * {@code numeric_in} rejects invents a value the server can never have sent, and the weak oracle would call
 * that a pass.
 *
 * <p>The non-ASCII digit cases are the reason the integer and numeric text decoders screen a literal before
 * handing it to {@code Integer.parseInt} / {@link java.math.BigDecimal}, which read every Unicode decimal
 * digit (see {@code NumberDecoders.requireAsciiLiteral}). Server output is ASCII, so the divergence bites an
 * offline decode of a caller-supplied literal rather than a query.
 *
 * <p>These literals are also written into the Jazzer seed corpus for the matching {@code *_text} targets, so
 * a guided campaign starts from the empty string and the non-ASCII charset path rather than having to
 * discover them.
 */
class MalformedLiteralRefusalTest {

  static List<Arguments> refusedCases() {
    List<Arguments> out = new ArrayList<>();
    add(out, "int2", Oid.INT2, Int2EdgeCases.MALFORMED);
    add(out, "int4", Oid.INT4, Int4EdgeCases.MALFORMED);
    add(out, "int8", Oid.INT8, Int8EdgeCases.MALFORMED);
    add(out, "oid", Oid.OID, OidEdgeCases.MALFORMED);
    add(out, "numeric", Oid.NUMERIC, NumericEdgeCases.MALFORMED);
    return out;
  }

  private static void add(List<Arguments> out, String typeName, int oid, List<EdgeCase> cases) {
    for (EdgeCase e : cases) {
      out.add(Arguments.of(typeName, oid, e.name(), e.literal()));
    }
  }

  @ParameterizedTest(name = "{0}/{2}")
  @MethodSource("refusedCases")
  void malformedLiteralIsRefused(String typeName, int oid, String caseName, String literal) {
    CodecFuzzSupport.decodeScalarTextExpectingRefusal(literal, oid);
  }
}
