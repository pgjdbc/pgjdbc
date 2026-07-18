/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.util.Properties;

/**
 * Verifies that a deeply nested {@code row(row(...))} value is received in
 * binary format and bypasses the server-side text serializer that fails with
 * {@code "string buffer exceeds maximum allowed length (1073741823 bytes)"}.
 *
 * <p>PostgreSQL's {@code record_out} doubles-up quoting at every nesting
 * level, so the text representation grows exponentially with depth. The
 * binary wire format writes each level as a fixed {@code [oid][len][data]}
 * header, so size grows linearly with depth.</p>
 */
public class NestedRecordBinaryTransferTest extends BaseTest4 {

  private static final int NESTING_DEPTH = 32;

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    // Force binary on the first execute (sends an explicit Describe before the
    // first Bind). The anonymous record OID (2249) is part of the default
    // binary-receive set, so no explicit BINARY_TRANSFER_ENABLE is needed.
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    // The simple query protocol cannot negotiate binary format codes, so the
    // server would still go through record_out and blow up.
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);
  }

  @Test
  public void nestedRowSucceedsInBinary() throws SQLException {
    String sql = buildNestedRow(NESTING_DEPTH);
    try (PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "row should be returned");
      // The outer column is the anonymous record (OID 2249).
      assertEquals("record", rs.getMetaData().getColumnTypeName(1),
          "outer column type");

      Object outer = rs.getObject(1);
      assertNotNull(outer, "outer record");
      Struct struct = assertInstanceOf(Struct.class, outer,
          "binary record should decode into java.sql.Struct");

      // Walk all NESTING_DEPTH levels; the innermost attribute is the int 1.
      Object current = struct;
      for (int i = 0; i < NESTING_DEPTH; i++) {
        Struct s = assertInstanceOf(Struct.class, current,
            () -> "expected Struct at every nesting level");
        Object[] attrs = s.getAttributes();
        assertEquals(1, attrs.length, "each row has exactly one attribute");
        current = attrs[0];
      }
      assertNotNull(current, "innermost value should not be null");
      assertEquals(1, ((Number) current).intValue(), "innermost value");
    }
  }

  /**
   * Verifies that {@code array[row(...)]} — an array whose element type is the
   * anonymous record OID (2249), reported by the server as {@code record[]} —
   * is received in binary without an explicit opt-in, decoded element-by-element
   * into {@link Struct} values, and that {@link Struct#getAttributes()} recovers
   * the field types from the self-describing wire format.
   */
  @Test
  public void arrayOfRowSucceedsInBinary() throws SQLException {
    try (PreparedStatement ps =
             con.prepareStatement("SELECT array[row(1, 2), row(3, 4)]");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "row should be returned");
      assertEquals("_record", rs.getMetaData().getColumnTypeName(1), "outer column type");

      Object[] elements = (Object[]) rs.getArray(1).getArray();
      assertEquals(2, elements.length, "array length");

      Struct first = assertInstanceOf(Struct.class, elements[0],
          "binary record element should decode into java.sql.Struct");
      Object[] firstAttrs = first.getAttributes();
      assertEquals(2, firstAttrs.length, "row(1, 2) attribute count");
      assertEquals(1, ((Number) firstAttrs[0]).intValue(), "row(1, 2) first attribute");
      assertEquals(2, ((Number) firstAttrs[1]).intValue(), "row(1, 2) second attribute");

      Struct second = assertInstanceOf(Struct.class, elements[1], "second element");
      assertEquals(3, ((Number) second.getAttributes()[0]).intValue(), "row(3, 4) first attribute");
      assertEquals(4, ((Number) second.getAttributes()[1]).intValue(), "row(3, 4) second attribute");
    }
  }

  /**
   * Verifies that a binary anonymous record rebuilds its {@code record_out} text
   * literal from the wire-synthesized field types, including {@code record_out}
   * quote doubling for values that contain commas and quotes.
   */
  @Test
  public void anonymousRecordRebuildsTextLiteral() throws SQLException {
    // Cast the literal to text so the record field carries a concrete OID
    // (an uncast literal is the typeless 'unknown' pseudo-type, which has no
    // binary codec and would decode to raw bytes).
    try (PreparedStatement ps = con.prepareStatement("SELECT row(1, 'a,\"b'::text)");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "row should be returned");
      // record_out doubles the embedded quote: (1,"a,""b").
      assertEquals("(1,\"a,\"\"b\")", rs.getString(1), "rebuilt record literal");
    }
  }

  /**
   * A binary anonymous record whose field is itself a record must rebuild its
   * {@code record_out} text literal instead of collapsing to {@code null}.
   *
   * <p>Reconstruction re-resolves each field's type by the OID carried on the
   * wire; a nested record field reports OID 2249, which resolves to the
   * fieldless {@code record} pseudo-type. The rebuild must therefore fall back
   * to the nested {@link Struct}'s own wire-synthesized fields rather than fail
   * the attribute-count check against the fieldless type — a failure
   * {@link org.postgresql.jdbc.PgStruct#getValue()} would otherwise swallow
   * into a {@code null} literal. This is the {@code getString}/{@code getValue}
   * path that {@link #nestedRowSucceedsInBinary()} (which walks
   * {@code getAttributes()}) never exercises.</p>
   */
  @Test
  public void nestedRecordRebuildsTextLiteral() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT row(1, row(2, 3))");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "row should be returned");
      // The attributes themselves decode correctly; only the literal rebuild was broken.
      Struct outer = assertInstanceOf(Struct.class, rs.getObject(1), "outer record");
      assertInstanceOf(Struct.class, outer.getAttributes()[1], "nested record attribute");
      // record_out quotes the nested record because it contains commas and parentheses.
      assertEquals("(1,\"(2,3)\")", rs.getString(1), "rebuilt nested record literal");
    }
    // A deeper nesting exercises record_out's quote doubling at every level: the rebuild recurses
    // through each PgStruct's own fields, so the escaping must compound exactly as the server's does.
    try (PreparedStatement ps = con.prepareStatement("SELECT row(row(row(1)))");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "row should be returned");
      assertEquals("(\"(\"\"(1)\"\")\")", rs.getString(1), "rebuilt deeply nested record literal");
    }
  }

  /**
   * Sanity check: without binary opt-in for the record OID, the same query
   * goes through {@code record_out} on the server and overflows the 1 GiB
   * stringinfo buffer. This proves {@link #nestedRowSucceedsInBinary()}
   * really exercises the binary path.
   */
  @Test
  public void nestedRowFailsInText() throws Exception {
    Properties textProps = new Properties();
    // Do NOT inherit BINARY_TRANSFER_ENABLE / forceBinary from updateProperties.
    // Explicitly opt the record OID out so binary cannot kick in even after
    // server-side prepare.
    PGProperty.BINARY_TRANSFER_DISABLE.set(textProps, Oid.RECORD);
    try (Connection textCon = TestUtil.openDB(textProps);
         Statement st = textCon.createStatement()) {
      PSQLException ex = assertThrows(PSQLException.class,
          () -> st.executeQuery(buildNestedRow(NESTING_DEPTH)),
          "deeply nested row(...) should overflow record_out in text mode");
      String msg = ex.getMessage();
      assertTrue(msg != null && msg.contains("string buffer"),
          () -> "expected 'string buffer' overflow error, got: " + msg);
    }
  }

  /**
   * Builds {@code SELECT row(row(...row(1)...))} with the requested depth.
   */
  private static String buildNestedRow(int depth) {
    StringBuilder sb = new StringBuilder("SELECT ");
    for (int i = 0; i < depth; i++) {
      sb.append("row(");
    }
    sb.append('1');
    for (int i = 0; i < depth; i++) {
      sb.append(')');
    }
    return sb.toString();
  }
}
