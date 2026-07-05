/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jazzer;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.core.Oid;
import org.postgresql.fuzzkit.CodecFuzzSupport;
import org.postgresql.fuzzkit.coercion.PgTypeDescriptors;
import org.postgresql.jdbc.PgCodecContext;
import org.postgresql.jdbc.PgType;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.code_intelligence.jazzer.mutation.annotation.NotNull;

import java.sql.SQLException;

/**
 * The Jazzer port of {@code RawTextLiteralDecodeFuzzTest} in pgjdbc-jqf-test: the same adversarial
 * text-literal decode invariant over the same four targets (int4[], text[], the point composite, and
 * numeric), differing only in how the literal arrives. jqf composes a typed {@link String}; here
 * Jazzer's mutation framework maps a {@code @NotNull String} straight from the {@code @FuzzTest}
 * signature. The oracle is shared -- {@link CodecFuzzSupport#decodeTextExpectingNoLeak} -- so a leak
 * surfaces identically under both front-ends.
 *
 * <p>The invariant is the weak one this fuzzer targets (blind spot Z3): the text-literal parsers -- the
 * array literal grammar ({@code MultiDimArrayText} over {@code LiteralCursor}), the composite literal
 * grammar ({@code CompositeCodec} over {@code LiteralCursor}), and the scalar text decoders -- are
 * almost entirely cold in the canonical-wire fuzzers, with {@code LiteralCursor} at ~12% branch
 * coverage, because every literal those fuzzers decode is one the codec itself just wrote. Decoding an
 * arbitrary string must return a value or refuse with a clean {@link SQLException}, never leak an
 * unchecked {@link RuntimeException}.
 *
 * <p>Known limitation, documented rather than guarded here: the array text grammar recurses on nesting
 * depth, so a pathologically deep {@code {{{{...}}}} } literal can drive a {@link StackOverflowError}.
 * The bounded corpus never reaches that depth; a long guided campaign over the two array targets can. A
 * recursion-depth bound in the parser is a prerequisite for turning the array targets into always-on
 * ones (see FUZZ_ROADMAP.md), the same way {@code numericBinary} in {@code JazzerDecodeRobustnessFuzzTest}
 * carries {@code @Disabled} for finding F3.
 *
 * <p>Run as bounded regression with {@code gradle :pgjdbc-jazzer-test:test}; fuzz one target with
 * {@code gradle :pgjdbc-jazzer-test:test -Pjazzer.fuzz=1 --tests '*compositeLiteral*'}. The array
 * targets can hit the recursion limit above under a long guided run.
 */
class JazzerTextLiteralDecodeFuzzTest {

  private static final PgType INT4_ARRAY = PgTypeDescriptors.array(Oid.INT4_ARRAY).pgType();
  private static final PgType TEXT_ARRAY = PgTypeDescriptors.array(Oid.TEXT_ARRAY).pgType();
  private static final PgType NUMERIC = PgTypeDescriptors.scalar(Oid.NUMERIC).pgType();
  private static final PgType POINT = PgTypeDescriptors.composite(PgTypeDescriptors.POINT_OID).pgType();

  /** The registered point composite so its {@code (x,y,label)} literal parser resolves offline. */
  private static final CodecContext POINT_CONTEXT =
      PgCodecContext.offlineBuilder().type(POINT).build();

  @FuzzTest
  void int4ArrayLiteral(@NotNull String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal, INT4_ARRAY, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void textArrayLiteral(@NotNull String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal, TEXT_ARRAY, CodecFuzzSupport.builtins());
  }

  @FuzzTest
  void compositeLiteral(@NotNull String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal, POINT, POINT_CONTEXT);
  }

  @FuzzTest
  void numericLiteral(@NotNull String literal) throws SQLException {
    CodecFuzzSupport.decodeTextExpectingNoLeak(literal, NUMERIC, CodecFuzzSupport.builtins());
  }
}
