/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.Oid;
import org.postgresql.jdbc.PreferQueryMode;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Properties;

/**
 * Verifies that a temporal attribute of a binary {@code record} is decoded straight from its slice
 * of the shared composite buffer, through the temporal codec's slice-aware
 * {@code decodeBinary(byte[], offset, length, ...)} override rather than a per-field copy.
 *
 * <p>A leading {@code int} attribute keeps the {@code timestamptz} fields at a non-zero offset
 * within the buffer, so a wrong offset would surface as a wrong instant.</p>
 */
public class CompositeTemporalBinaryTest extends BaseTest4 {

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    // Force binary on the first execute and opt the anonymous record OID (2249) into the
    // binary-receive set, which is empty for record types by default.
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.RECORD);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    // The simple query protocol cannot negotiate binary format codes.
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);
  }

  @Test
  public void timestamptzAttributesDecodeFromSlice() throws SQLException {
    // timestamptz is an instant, so these assertions hold regardless of the JVM / session zone.
    Timestamp whole = Timestamp.from(Instant.parse("2024-01-15T10:30:00Z"));
    Timestamp fractional = Timestamp.from(Instant.parse("1999-12-31T23:59:59.123456Z"));

    String sql = "SELECT row(42,"
        + " TIMESTAMPTZ '2024-01-15 10:30:00+00',"
        + " TIMESTAMPTZ '1999-12-31 23:59:59.123456+00')";
    try (PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next(), "row should be returned");
      assertEquals("record", rs.getMetaData().getColumnTypeName(1), "outer column type");

      Struct struct = assertInstanceOf(Struct.class, rs.getObject(1),
          "binary record should decode into java.sql.Struct");
      Object[] attrs = struct.getAttributes();
      assertEquals(3, attrs.length, "row has three attributes");
      assertEquals(42, ((Number) attrs[0]).intValue(),
          "leading int keeps the timestamptz fields at a non-zero slice offset");

      Timestamp first = assertInstanceOf(Timestamp.class, attrs[1], "first timestamptz attribute");
      assertEquals(whole, first, "timestamptz decoded from composite slice");

      Timestamp second = assertInstanceOf(Timestamp.class, attrs[2], "second timestamptz attribute");
      assertEquals(fractional, second,
          "fractional-second timestamptz decoded from composite slice");
    }
  }
}
