/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.fuzz;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.ContainerDecodeTypes;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.OfflineCodecs;
import org.postgresql.jdbc.PgType;

import edu.berkeley.cs.jqf.junit5.FuzzTest;

import java.sql.SQLException;

/**
 * Prototype (Axis 4): an adversarial text-literal fuzzer that hands an <em>arbitrary</em> string to
 * the text-literal parsers -- the array literal grammar ({@code MultiDimArrayText} over
 * {@code LiteralCursor}), the composite literal grammar ({@code CompositeCodec} over
 * {@code LiteralCursor}), the range/multirange literal grammars ({@code RangeCodec}/
 * {@code MultirangeCodec}), and the scalar text decoders -- rather than only the well-formed literals the
 * encoder produces. These recursive, quoting- and escape-aware parsers are almost entirely cold in the
 * canonical-wire fuzzers: {@code LiteralCursor} sits at ~12% branch coverage, because every literal the
 * codec fuzzers decode is one the codec itself just wrote.
 *
 * <p>The invariant is the weak one, shared with the Jazzer port through
 * {@link CodecFuzzSupport#decodeTextExpectingNoLeak}: parsing an arbitrary string must return a value
 * or refuse with a clean {@link SQLException}; it must never leak an unchecked
 * {@link RuntimeException}. A malformed literal (unbalanced braces, a truncated element, a stray quote)
 * is expected to refuse, not crash. Only the front-end differs from the Jazzer counterpart: here jqf
 * supplies the {@link String}; the oracle is the same.
 *
 * <p>Known limitation, documented rather than guarded here: the array text grammar recurses on nesting
 * depth, so a pathologically deep {@code {{{{...}}}} } literal can drive a {@link StackOverflowError}.
 * The bounded corpus never reaches that depth, but a long guided campaign over the array targets can; a
 * recursion-depth bound in the parser is a prerequisite for turning this into an always-on target (see
 * FUZZ_ROADMAP.md).
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jqf-test:test}; fuzz with
 * {@code -Djqf.fuzz=true -Djqf.fuzz.trials=100000}.
 */
class JqfTextLiteralDecodeFuzzTest {

  private static final int POINT_OID = PgTypeDescriptors.POINT_OID;

  /** The registered point composite so its {@code (x,y,label)} literal parser resolves offline. */
  private static CodecContext contextWithPoint() {
    PgType point = PgTypeDescriptors.composite(POINT_OID).pgType();
    return OfflineCodecs.builder().type(point).build();
  }

  @FuzzTest
  void int4ArrayLiteral(String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal,
        PgTypeDescriptors.array(Oid.INT4_ARRAY).pgType(), CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void textArrayLiteral(String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal,
        PgTypeDescriptors.array(Oid.TEXT_ARRAY).pgType(), CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void compositeLiteral(String literal) throws SQLException {
    PgType point = PgTypeDescriptors.composite(POINT_OID).pgType();
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal, point, contextWithPoint());
  }

  @FuzzTest
  void numericLiteral(String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal,
        PgTypeDescriptors.scalar(Oid.NUMERIC).pgType(), CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void rangeLiteral(String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal, ContainerDecodeTypes.INT4RANGE,
        ContainerDecodeTypes.INT4RANGE_CONTEXT);
  }

  @FuzzTest
  void multirangeLiteral(String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal, ContainerDecodeTypes.INT4MULTIRANGE,
        ContainerDecodeTypes.INT4MULTIRANGE_CONTEXT);
  }
}
