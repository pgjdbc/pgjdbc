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
    // Force binary on the first execute (sends an explicit Describe before
    // the first Bind) and opt the anonymous record OID (2249) into the
    // binary-receive set, which is empty for record types by default.
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.RECORD);
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
   * Sanity check: without binary opt-in for the record OID, the same query
   * goes through {@code record_out} on the server and overflows the 1 GiB
   * stringinfo buffer. This proves {@link #nestedRowSucceedsInBinary()}
   * really exercises the binary path.
   */
  @Test
  public void nestedRowFailsInText() throws Exception {
    Properties textProps = new Properties();
    TestUtil.initDriver();
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
