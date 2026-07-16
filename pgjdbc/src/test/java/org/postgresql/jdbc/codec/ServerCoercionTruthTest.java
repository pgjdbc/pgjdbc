/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.test.TestUtil;
import org.postgresql.test.data.EdgeCase;
import org.postgresql.test.data.Float4EdgeCases;
import org.postgresql.test.data.Float8EdgeCases;
import org.postgresql.test.data.Int2EdgeCases;
import org.postgresql.test.data.Int4EdgeCases;
import org.postgresql.test.data.Int8EdgeCases;
import org.postgresql.test.data.NumericEdgeCases;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-truth for the numeric read coercions: the driver's narrowing getters ({@code getShort},
 * {@code getInt}, {@code getLong}, {@code getFloat}, {@code getDouble}) must agree with PostgreSQL's own
 * cast of the same value to the matching type. For every source type and edge value the client reads
 * {@code SELECT lit::src} through the getter, and the server casts {@code lit::src::<target>}; the two
 * must both return the same value or both refuse (an overflow, or a {@code float} underflow).
 *
 * <p>This pins the client narrowings against ground truth -- both the overflow boundary AND the rounding
 * direction ({@code float8->int} rounds ties-to-even, {@code numeric->int} rounds half-away-from-zero) --
 * where the offline lattice ({@link org.postgresql.fuzzkit.CodecFuzzSupport} {@code numericLattice})
 * checks them only against a hand-coded reimplementation. It is the live, server-backed complement of
 * that offline test and subsumes the {@code getFloat} {@code float8->float4} case that motivated the F-9
 * fix. The server's cast result is read back with the same getter, an identity read (the cast already
 * produced the target type), so the compared value is the server's own narrowing.
 *
 * <p>The bind values and their literals come from the shared testkit {@code *EdgeCases} catalogues, so
 * the same boundary and out-of-range corners are exercised here as in the offline tests.
 */
class ServerCoercionTruthTest {

  private static Connection con;

  @BeforeAll
  static void setUpClass() throws Exception {
    con = TestUtil.openDB();
  }

  @AfterAll
  static void tearDownClass() throws Exception {
    TestUtil.closeDB(con);
  }

  /** Reads column 1 as a canonical string; bit-exact for float/double so {@code NaN}/{@code -0} compare. */
  @FunctionalInterface
  private interface Reader {
    String read(ResultSet rs) throws SQLException;
  }

  /** A narrowing getter paired with the server cast that computes the same narrowing. */
  private enum Coercion {
    SHORT("int2", rs -> Short.toString(rs.getShort(1))),
    INT("int4", rs -> Integer.toString(rs.getInt(1))),
    LONG("int8", rs -> Long.toString(rs.getLong(1))),
    FLOAT("float4", rs -> Integer.toString(Float.floatToIntBits(rs.getFloat(1)))),
    DOUBLE("float8", rs -> Long.toString(Double.doubleToLongBits(rs.getDouble(1))));

    private final String castType;
    private final Reader reader;

    Coercion(String castType, Reader reader) {
      this.castType = castType;
      this.reader = reader;
    }
  }

  /** A source PostgreSQL type paired with its shared edge-case catalogue. */
  private enum Source {
    INT2("int2", Int2EdgeCases.ALL),
    INT4("int4", Int4EdgeCases.ALL),
    INT8("int8", Int8EdgeCases.ALL),
    NUMERIC("numeric", NumericEdgeCases.ALL),
    FLOAT4("float4", Float4EdgeCases.ALL),
    FLOAT8("float8", Float8EdgeCases.ALL);

    private final String typeName;
    private final List<EdgeCase> cases;

    Source(String typeName, List<EdgeCase> cases) {
      this.typeName = typeName;
      this.cases = cases;
    }
  }

  static List<Arguments> cases() {
    List<Arguments> out = new ArrayList<>();
    for (Source s : Source.values()) {
      for (EdgeCase e : s.cases) {
        for (Coercion c : Coercion.values()) {
          // numeric NaN/Infinity -> float/double is out of scope: the driver's non-finite numeric
          // coercion is a separate concern from this narrowing oracle (the numeric specials carry a null
          // catalogue value). The int coercions of the same specials are kept -- both sides refuse.
          if (s == Source.NUMERIC && e.value() == null
              && (c == Coercion.FLOAT || c == Coercion.DOUBLE)) {
            continue;
          }
          out.add(Arguments.of(s.typeName, e.name(), e.literal(), c));
        }
      }
    }
    return out;
  }

  @ParameterizedTest(name = "{0}/{3}/{1}")
  @MethodSource("cases")
  void coercionMatchesServerCast(String source, String caseName, String literal, Coercion c) {
    Outcome client = read(c.reader, "SELECT '" + literal + "'::" + source);
    Outcome server = read(c.reader, "SELECT '" + literal + "'::" + source + "::" + c.castType);
    assertEquals(server.refused, client.refused, () ->
        source + " '" + literal + "' get" + c + ": client refusal must match the server "
            + source + "->" + c.castType + " cast (client=" + client + ", server=" + server + ")");
    if (!server.refused) {
      assertEquals(server.value, client.value, () ->
          source + " '" + literal + "' get" + c + " must equal the server " + source + "->"
              + c.castType + " cast");
    }
  }

  private static Outcome read(Reader reader, String sql) {
    try (Statement st = con.createStatement();
         ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return new Outcome(false, reader.read(rs));
    } catch (SQLException refused) {
      return new Outcome(true, null);
    }
  }

  /** The result of one read: the canonical value, or a refusal (the server cast or the getter threw). */
  private static final class Outcome {
    private final boolean refused;
    private final String value;

    Outcome(boolean refused, String value) {
      this.refused = refused;
      this.value = value;
    }

    @Override
    public String toString() {
      return refused ? "refused" : value;
    }
  }
}
