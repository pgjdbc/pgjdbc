/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.fuzzkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.api.codec.CodecContext;
import org.postgresql.api.codec.Codecs;
import org.postgresql.api.codec.Format;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PgType;
import org.postgresql.test.data.Bit1EdgeCases;
import org.postgresql.test.data.BoolEdgeCases;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Float4EdgeCases;
import org.postgresql.test.data.Float8EdgeCases;
import org.postgresql.test.data.Int2EdgeCases;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.MoneyEdgeCases;
import org.postgresql.test.data.NumericEdgeCases;
import org.postgresql.test.data.OidEdgeCases;
import org.postgresql.test.data.TextEdgeCases;
import org.postgresql.test.data.VarbitEdgeCases;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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

    Catalogue(int oid, String typeName, char typcategory, List<EdgeCase> cases) {
      this.typeName = typeName;
      this.type = CodecFuzzSupport.scalar(oid, typeName, typcategory);
      this.cases = cases;
    }
  }

  private static final List<Catalogue> CATALOGUES = Arrays.asList(
      new Catalogue(Oid.NUMERIC, "numeric", 'N', NumericEdgeCases.ALL),
      new Catalogue(Oid.INT2, "int2", 'N', Int2EdgeCases.ALL),
      new Catalogue(Oid.INT4, "int4", 'N', Int4EdgeCases.ALL),
      new Catalogue(Oid.INT8, "int8", 'N', Int8EdgeCases.ALL),
      new Catalogue(Oid.FLOAT4, "float4", 'N', Float4EdgeCases.ALL),
      new Catalogue(Oid.FLOAT8, "float8", 'N', Float8EdgeCases.ALL),
      new Catalogue(Oid.OID, "oid", 'N', OidEdgeCases.ALL),
      new Catalogue(Oid.MONEY, "money", 'N', MoneyEdgeCases.ALL),
      // Non-numeric primitive-decoder codecs. Their numeric lattice is inert (NumericFamily.OTHER), but
      // the String/char[]/offset overload-agreement layer still bites on the accessors they do decode --
      // bit/bool booleans, text int/long/double parsing -- which is the same char[]-vs-String parity the
      // numeric fix closed.
      new Catalogue(Oid.TEXT, "text", 'S', TextEdgeCases.ALL),
      new Catalogue(Oid.BOOL, "bool", 'B', BoolEdgeCases.ALL),
      new Catalogue(Oid.BIT, "bit", 'V', Bit1EdgeCases.ALL),
      new Catalogue(Oid.VARBIT, "varbit", 'V', VarbitEdgeCases.ALL));

  /**
   * Primitive-decoder types the fuzzer drives that have no deterministic boundary catalogue here, on
   * purpose. {@code oid8}/{@code xid8} are 64-bit identifier types with no rounding edges worth pinning;
   * {@code varchar}/{@code bpchar}/{@code name} decode identically to {@code text} (already covered) and
   * carry no distinct boundary literals; the Fallback probe is a synthetic unknown type, not a real
   * catalogued type. All stay exercised by {@code GetterConsistencyFuzzTest}.
   */
  private static final Set<Integer> FUZZER_ONLY = new TreeSet<>(Arrays.asList(
      Oid.OID8, Oid.XID8, Oid.VARCHAR, Oid.BPCHAR, Oid.NAME, CodecFuzzSupport.FALLBACK_PROBE_OID));

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

  /**
   * The deterministic edge-case coverage must not silently fall behind the fuzzer. {@code
   * GetterConsistencyFuzzTest} drives every primitive-decoder type the registry pins (via {@link
   * CodecFuzzSupport#primitiveDecoderTypes()}); this guard fails when such a type is neither driven here
   * by an edge-case catalogue nor acknowledged as fuzzer-only in {@link #FUZZER_ONLY}, so a newly pinned
   * primitive-decoder codec forces a conscious choice rather than slipping through. It mirrors {@code
   * PrimitiveDecoderCoverageArchTest} one level down: that test guards the type reaches the fuzzer at all;
   * this one guards it also has deterministic boundary cases, or is knowingly left to the fuzzer.
   */
  @Test
  void everyPrimitiveDecoderTypeIsCoveredOrAcknowledged() {
    Set<Integer> covered = CATALOGUES.stream()
        .map(c -> c.type.getOid())
        .collect(Collectors.toCollection(TreeSet::new));
    Set<Integer> capable = CodecFuzzSupport.primitiveDecoderTypes().stream()
        .map(PgType::getOid)
        .collect(Collectors.toCollection(TreeSet::new));

    Set<Integer> uncovered = new TreeSet<>(capable);
    uncovered.removeAll(covered);
    uncovered.removeAll(FUZZER_ONLY);
    assertEquals(Collections.emptySet(), uncovered,
        "Primitive-decoder types the getter-consistency fuzzer drives but this deterministic test does "
            + "not, and that FUZZER_ONLY does not acknowledge. Add an edge-case catalogue row to "
            + "CATALOGUES, or -- if the type has no meaningful boundary catalogue and is left to the "
            + "fuzzer on purpose -- add its OID to FUZZER_ONLY.");

    // Bidirectional, mirroring PrimitiveDecoderCoverageArchTest: an acknowledgement is stale once the
    // fuzzer no longer drives that OID, or a CATALOGUES row now covers it.
    Set<Integer> staleAck = new TreeSet<>();
    for (Integer oid : FUZZER_ONLY) {
      if (!capable.contains(oid) || covered.contains(oid)) {
        staleAck.add(oid);
      }
    }
    assertEquals(Collections.emptySet(), staleAck,
        "OIDs in FUZZER_ONLY that are no longer fuzzer-only: either primitiveDecoderTypes() no longer "
            + "enumerates them, or a CATALOGUES row now covers them. Remove them from FUZZER_ONLY.");
  }
}
