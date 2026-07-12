/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Float4EdgeCases;
import org.postgresql.test.data.Float8EdgeCases;
import org.postgresql.test.data.Int2EdgeCases;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.MoneyEdgeCases;
import org.postgresql.test.data.NumericEdgeCases;
import org.postgresql.test.data.OidEdgeCases;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the getter-consistency invariants (see {@link CodecFuzzSupport}) over the shared per-type
 * edge-case catalogues, so the boundary literals that decide rounding and overflow -- 127.5,
 * 9999.9999, 2147483647.5, NaN, ... -- are checked deterministically and fast on every build rather
 * than left for the coverage-guided fuzzer to rediscover. This is the check that catches, with no
 * fuzz run, the numeric {@code getInt("N.9")} round-vs-truncate mismatch between the String and
 * char[] accessors: numeric's boundary cases include {@code *.5} fractions the String accessor
 * rounds and the char[] one used to truncate.
 */
class GetterConsistencyEdgeCaseTest {

  private static final class Catalogue {
    final String typeName;
    final PgType type;
    final List<EdgeCase> cases;

    Catalogue(int oid, String typeName, List<EdgeCase> cases) {
      this.typeName = typeName;
      this.type = CodecFuzzSupport.scalar(oid, typeName, 'N');
      this.cases = cases;
    }
  }

  private static final List<Catalogue> CATALOGUES = Arrays.asList(
      new Catalogue(Oid.NUMERIC, "numeric", NumericEdgeCases.ALL),
      new Catalogue(Oid.INT2, "int2", Int2EdgeCases.ALL),
      new Catalogue(Oid.INT4, "int4", Int4EdgeCases.ALL),
      new Catalogue(Oid.INT8, "int8", Int8EdgeCases.ALL),
      new Catalogue(Oid.FLOAT4, "float4", Float4EdgeCases.ALL),
      new Catalogue(Oid.FLOAT8, "float8", Float8EdgeCases.ALL),
      new Catalogue(Oid.OID, "oid", OidEdgeCases.ALL),
      new Catalogue(Oid.MONEY, "money", MoneyEdgeCases.ALL));

  static Stream<Arguments> edgeCases() {
    return CATALOGUES.stream()
        .flatMap(c -> c.cases.stream().map(ec -> Arguments.of(c.typeName, c.type, ec)));
  }

  @ParameterizedTest(name = "{0} {2}")
  @MethodSource("edgeCases")
  void invariantsHoldOnEdgeCase(String typeName, PgType type, EdgeCase edgeCase) throws SQLException {
    CodecContext ctx = CodecFuzzSupport.consistencyContext();

    // Text side: the literal is the read-path input the fuzzer feeds, and where the char[]-vs-String
    // accessor mismatch lives.
    try {
      CodecFuzzSupport.textGetterConsistency(type, edgeCase.literal(), ctx);
    } catch (AssertionError failure) {
      throw CodecFuzzSupport.textReproError(failure, type, edgeCase.literal());
    }

    // Binary side: when the case carries a bindable value, encode it and check the wire form too. A
    // value that this type cannot encode (best-effort) leaves the text check as the coverage.
    Object value = edgeCase.value();
    if (value == null) {
      return;
    }
    byte[] wire;
    try {
      wire = Codecs.encode(value, type, ctx, Format.BINARY).toByteArray();
    } catch (SQLException notEncodable) {
      return;
    }
    try {
      CodecFuzzSupport.binaryGetterConsistency(type, wire, ctx);
    } catch (AssertionError failure) {
      throw CodecFuzzSupport.binaryReproError(failure, type, wire);
    }
  }
}
