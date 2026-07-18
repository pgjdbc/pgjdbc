/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc.codec;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.jdbc.codec.ParityHarness.NO_PARAMS;
import static org.postgresql.jdbc.codec.ParityHarness.assertParityEquals;

import org.postgresql.PGConnection;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Text/binary parity matrix for the array edge cases that historically diverge: a SQL NULL array, an
 * empty array, NULL elements, multiple dimensions, custom lower bounds, and ragged (non-rectangular)
 * input.
 *
 * <p>Decode cases drive identical server bytes through {@link ParityHarness}'s text and binary
 * connections via a literal {@code SELECT}; encode cases round-trip a Java array built with
 * {@link Connection#createArrayOf} back through {@code SELECT ?::int4[]}. Both assert
 * {@code binary == text == original}.</p>
 *
 * <p>{@code int4[]} and {@code text[]} are both in the driver's default binary-receive set, so the
 * binary connection needs no extra opt-in. The ragged cases live in their own tests because the
 * expected outcome is a rejection, not a value.</p>
 */
class ArrayParityMatrixTest {

  private static Connection text;
  private static Connection binary;

  @BeforeAll
  static void setUpClass() throws Exception {
    text = ParityHarness.openText();
    // Force binary receive so a single execute exercises the binary array decode rather than the
    // capability path's text fallback for a cold memo.
    binary = ParityHarness.openBinary(
        ParityHarness.oids(Oid.INT4, Oid.INT4_ARRAY, Oid.TEXT, Oid.TEXT_ARRAY));
    if (binary.unwrap(PGConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE) {
      assertTrue(ParityHarness.binaryActiveFor(binary, Oid.INT4_ARRAY),
          "int4[] must be received in binary on the binary connection");
      assertTrue(ParityHarness.binaryActiveFor(binary, Oid.TEXT_ARRAY),
          "text[] must be received in binary on the binary connection");
    }
  }

  @AfterAll
  static void tearDownClass() throws Exception {
    TestUtil.closeDB(text);
    TestUtil.closeDB(binary);
  }

  private static DynamicTest decode(String name, String sql, Object expected) {
    return DynamicTest.dynamicTest(name, () -> assertParityEquals(text, binary, sql, NO_PARAMS, expected));
  }

  private static DynamicTest encode(String name, String sql, ParityHarness.Binder binder,
      Object expected) {
    return DynamicTest.dynamicTest(name, () -> assertParityEquals(text, binary, sql, binder, expected));
  }

  @TestFactory
  List<DynamicTest> decodeMatrix() {
    List<DynamicTest> t = new ArrayList<>();

    // The five matrix cells, on int4[].
    t.add(decode("int4/null", "SELECT NULL::int4[]", null));
    t.add(decode("int4/empty", "SELECT '{}'::int4[]", new Integer[0]));
    t.add(decode("int4/null-elements", "SELECT '{1,NULL,3}'::int4[]", new Integer[]{1, null, 3}));
    t.add(decode("int4/2d", "SELECT '{{1,2},{3,4}}'::int4[]",
        new Integer[][]{{1, 2}, {3, 4}}));
    t.add(decode("int4/3d", "SELECT '{{{1,2},{3,4}},{{5,6},{7,8}}}'::int4[]",
        new Integer[][][]{{{1, 2}, {3, 4}}, {{5, 6}, {7, 8}}}));
    // A custom lower bound is dropped by the driver in both formats, leaving a plain 1-based array.
    t.add(decode("int4/custom-lower-bound", "SELECT '[2:4]={10,20,30}'::int4[]",
        new Integer[]{10, 20, 30}));

    // Repeat the element-shape cells on text[] to exercise the string leaf, including quoting.
    t.add(decode("text/null-elements-and-meta", "SELECT '{a,NULL,\"c,d\",\"{e}\"}'::text[]",
        new String[]{"a", null, "c,d", "{e}"}));
    t.add(decode("text/2d", "SELECT '{{a,b},{c,d}}'::text[]",
        new String[][]{{"a", "b"}, {"c", "d"}}));

    return t;
  }

  @TestFactory
  List<DynamicTest> encodeMatrix() {
    List<DynamicTest> t = new ArrayList<>();

    t.add(encode("int4/1d", "SELECT ?::int4[]",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("int4", new Integer[]{1, 2, 3})),
        new Integer[]{1, 2, 3}));
    t.add(encode("int4/empty", "SELECT ?::int4[]",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("int4", new Integer[0])),
        new Integer[0]));
    t.add(encode("int4/null-elements", "SELECT ?::int4[]",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("int4", new Integer[]{1, null, 3})),
        new Integer[]{1, null, 3}));
    t.add(encode("int4/null-param", "SELECT ?::int4[]",
        ps -> ps.setNull(1, Types.ARRAY), null));
    // Runtime-nested Object[]: createArrayOf("int4", new Object[]{new Object[]{...}}) is an Object[]
    // by class but a 2-D int4[][] at runtime, so it must round-trip like a typed Integer[][].
    t.add(encode("int4/runtime-nested-object", "SELECT ?::int4[]",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("int4",
            new Object[]{new Object[]{1, 2}, new Object[]{3, 4}})),
        new Integer[][]{{1, 2}, {3, 4}}));
    t.add(encode("text/meta", "SELECT ?::text[]",
        ps -> ps.setArray(1, ps.getConnection().createArrayOf("text",
            new String[]{"a", "b,c", "d\"e", "{f}", null})),
        new String[]{"a", "b,c", "d\"e", "{f}", null}));

    return t;
  }

  /**
   * A ragged array literal is rejected by the server with the same {@code malformed array literal}
   * error over both wire formats — there is no valid value to disagree on.
   */
  @Test
  void raggedLiteralRejectedOnBothConnections() {
    String sql = "SELECT '{{1,2},{3}}'::int4[]";
    for (Connection con : new Connection[]{text, binary}) {
      SQLException ex = assertThrows(SQLException.class,
          () -> ParityHarness.decodeFirst(con, sql, NO_PARAMS),
          () -> "ragged literal should be rejected on " + con);
      assertNotNull(ex.getMessage(), "rejection should carry a message");
    }
  }

  /**
   * A ragged (non-rectangular) Java array is rejected by the driver's array encoder before it reaches
   * the wire, rather than being silently truncated or padded. This holds both for a statically typed
   * {@code Integer[][]} and for the runtime-nested {@code Object[]} shape, whose dimensions
   * {@code computeDimensions} discovers at runtime ({@code validateRectangular} then enforces a
   * rectangular shape).
   */
  @Test
  void raggedTypedArrayEncodeRejected() {
    assertRaggedRejected(new Integer[][]{{1, 2}, {3}});
    assertRaggedRejected(new Object[]{new Object[]{1, 2}, new Object[]{3}});
  }

  private static void assertRaggedRejected(Object ragged) {
    SQLException ex = assertThrows(SQLException.class, () -> {
      try (PreparedStatement ps = text.prepareStatement("SELECT ?::int4[]")) {
        ps.setArray(1, text.createArrayOf("int4", (Object[]) ragged));
        ps.executeQuery();
      }
    });
    assertNotNull(ex.getMessage(), "rejection should carry a message");
  }

  /**
   * {@code getObject(col, byte[].class)} asks for a {@code byte[]} that an {@code int4[]} or
   * {@code text[]} column can never produce: {@code byte[].class.isArray()} is true, so the request
   * lands in the array-decode branch, but no element mapping yields a {@code byte[]}. The driver must
   * refuse rather than hand back the element mapping ({@code Integer[]} / {@code String[]}), which
   * would raise a {@link ClassCastException} at the {@code getObject(int, Class)} call site. Holds
   * over both wire formats.
   */
  @Test
  void getObjectAsByteArrayRefusedOnArrayColumn() {
    for (String sql : new String[]{"SELECT '{1,2,3}'::int4[]", "SELECT '{a,b}'::text[]"}) {
      for (Connection con : new Connection[]{text, binary}) {
        SQLException ex = assertThrows(SQLException.class,
            () -> ParityHarness.decodeFirstAs(con, sql, byte[].class),
            () -> "getObject(byte[].class) on " + sql + " should refuse on " + con);
        assertNotNull(ex.getMessage(), "rejection should carry a message");
      }
    }
  }

  /**
   * The refusal above must not catch the one array whose leaf genuinely is a {@code byte[]}: a
   * {@code bytea[]} column maps to a {@code byte[][]}, so {@code getObject(col, byte[][].class)} still
   * decodes through the shared walker and returns it.
   */
  @Test
  void getObjectAsByteMatrixStillDecodesByteaArray() throws SQLException {
    String sql = "SELECT '{\"\\\\x0102\",\"\\\\xff\"}'::bytea[]";
    for (Connection con : new Connection[]{text, binary}) {
      Object decoded = ParityHarness.decodeFirstAs(con, sql, byte[][].class);
      assertNotNull(decoded, () -> "bytea[] should decode to byte[][] on " + con);
      byte[][] matrix = (byte[][]) decoded;
      assertArrayEquals(new byte[]{1, 2}, matrix[0], () -> "first bytea element on " + con);
      assertArrayEquals(new byte[]{(byte) 0xff}, matrix[1], () -> "second bytea element on " + con);
    }
  }
}
