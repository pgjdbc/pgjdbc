/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.core.Field;
import org.postgresql.core.Oid;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Covers the binary-transfer testing wildcards and the per-execution format upgrade:
 *
 * <ul>
 *   <li>{@code binaryTransferEnable=*} forces binary receive for every column, bypassing the
 *       capability check (and so fails loudly for a type the driver cannot decode in binary).</li>
 *   <li>{@code binaryTransferDisable=*} forces text everywhere and overrides {@code enable=*}.</li>
 *   <li>A repeatedly executed prepared statement upgrades a column from text to binary on the second
 *       execution, once the type's capability has been warmed by the first execution.</li>
 * </ul>
 */
public class BinaryTransferWildcardTest {

  private static final String COMPOSITE = "wildcard_ab_t";

  @BeforeAll
  static void setUp() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      assumeExtended(con);
      TestUtil.createCompositeType(con, COMPOSITE, "a int4, b int4");
    }
  }

  @AfterAll
  static void tearDown() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropType(con, COMPOSITE);
    }
  }

  private static void assumeExtended(Connection con) throws SQLException {
    assumeTrue(con.unwrap(PgConnection.class).getPreferQueryMode() != PreferQueryMode.SIMPLE,
        "binary transfer needs the extended protocol");
  }

  private static Connection openExtended(Properties props) throws SQLException {
    Connection con = TestUtil.openDB(props);
    assumeExtended(con);
    return con;
  }

  private static int format(ResultSet rs, int column) throws SQLException {
    return ((PGResultSetMetaData) rs.getMetaData()).getFormat(column);
  }

  // ---------------------------------------------------------------------------
  // binaryTransferEnable=*
  // ---------------------------------------------------------------------------

  @Test
  void enableWildcardForcesBinaryOnFirstExecution() throws SQLException {
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(Field.BINARY_FORMAT, format(rs, 1),
          "enable=* must force binary on the very first execution");
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void enableWildcardForcesBinaryOnFirstExecutionWithDefaultThreshold() throws SQLException {
    // No prepareThreshold override, so the statement is not described before the first Bind and
    // query.getFields() is null there. enable=* must still force binary via a single result-format
    // code that the Bind applies to every column.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(Field.BINARY_FORMAT, format(rs, 1),
          "enable=* must force binary on the first execution even without a prior describe");
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void enableWildcardStaysBinaryAcrossManyExecutions() throws SQLException {
    // Regression (codex review): enable=* binds binary before the first describe, the describe then
    // reports binary formats, and setFields() resets hasBinaryFields. A later cached execution must
    // keep requesting binary to match the cached field metadata, not revert to text and mis-decode.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select 42")) {
      for (int i = 1; i <= 10; i++) {
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next(), "row at execution " + i);
          assertEquals(Field.BINARY_FORMAT, format(rs, 1), "binary format at execution " + i);
          assertEquals(42, rs.getInt(1), "value at execution " + i);
        }
      }
    }
  }

  @Test
  void enableWildcardLoadsUncachedComposite() throws SQLException {
    // Regression: resolving an uncached composite under enable=* used to overflow the stack. The
    // pg_type lookup runs under the same enable=*, so its regproc columns (typsend, typreceive — not
    // built-in types) came back binary; decoding one re-entered the type cache for the same
    // not-yet-loaded type and recursed. The lookup now casts those columns to text, a built-in type,
    // so loading the composite completes.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select row(3, 4)::" + COMPOSITE);
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals("(3,4)", rs.getString(1));
    }
  }

  @Test
  void enableWildcardForcesBinaryForTextOnlyType() throws SQLException {
    // txid_snapshot has txid_snapshot_send on the server but no binary codec in the driver, so it is
    // normally received as text. enable=* forces binary receive anyway (bypassing the capability
    // check) — the intended diagnostic: it surfaces types whose binary path the driver does not
    // support.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select '10:20:14,15'::txid_snapshot");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(Field.BINARY_FORMAT, format(rs, 1),
          "enable=* must force binary even for a type the driver has no binary codec for");
    }
  }

  // ---------------------------------------------------------------------------
  // binaryTransferDisable=*
  // ---------------------------------------------------------------------------

  @Test
  void disableWildcardForcesTextEverywhere() throws SQLException {
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_DISABLE.set(props, "*");
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select 1, now()");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(Field.TEXT_FORMAT, format(rs, 1), "disable=* must force int4 to text");
      assertEquals(Field.TEXT_FORMAT, format(rs, 2), "disable=* must force timestamptz to text");
      assertEquals(1, rs.getInt(1));
    }
  }

  @Test
  void disableWildcardOverridesEnableWildcard() throws SQLException {
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.BINARY_TRANSFER_DISABLE.set(props, "*");
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select 1");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(Field.TEXT_FORMAT, format(rs, 1), "disable=* must override enable=*");
    }
  }

  @Test
  void perTypeDisableOverridesEnableWildcard() throws SQLException {
    // A specific binaryTransferDisable entry must keep its type text even though enable=* would
    // otherwise force binary, honouring the disable-overrides-enable contract.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.BINARY_TRANSFER_DISABLE.set(props, String.valueOf(Oid.INT4));
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select 1::int4 a, 1::int8 b");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(Field.TEXT_FORMAT, format(rs, 1), "disabled int4 must stay text under enable=*");
      assertEquals(Field.BINARY_FORMAT, format(rs, 2), "int8 is still forced binary by enable=*");
    }
  }

  @Test
  void perTypeDisableHonoredOnFirstUndescribedExecution() throws SQLException {
    // With the default threshold the first execution binds before the columns are described, so the
    // wildcard would otherwise blanket-force binary with a single result-format code. A per-type
    // disable must still keep its type text even on that first execution.
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "*");
    PGProperty.BINARY_TRANSFER_DISABLE.set(props, String.valueOf(Oid.INT4));
    try (Connection con = openExtended(props);
         PreparedStatement ps = con.prepareStatement("select 1::int4");
         ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(Field.TEXT_FORMAT, format(rs, 1),
          "disabled int4 must stay text on the first undescribed execution under enable=*");
      assertEquals(1, rs.getInt(1));
    }
  }

  // ---------------------------------------------------------------------------
  // Re-execution upgrades a cold-on-first-use type to binary
  // ---------------------------------------------------------------------------

  @Test
  void repeatedExecutionUpgradesWarmedTypesToBinary() throws SQLException {
    Properties props = new Properties();
    // prepareThreshold=-1 server-prepares from the first execution and (pre-fix) freezes the text
    // format chosen while the user types were still cold.
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    try (Connection con = openExtended(props)) {
      assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_2),
          "int4range was introduced in PostgreSQL 9.2");
      // Both types have a dynamic, installation-dependent OID, so their binary capability is unknown
      // at the first Bind and the driver must fall back to text; reading the row warms the memo and a
      // later execution upgrades them to binary.
      String sql = "select row(1,2)::" + COMPOSITE + " c1, '[1,2]'::int4range c2";
      try (PreparedStatement ps = con.prepareStatement(sql)) {
        // Exec #1: both are cold at the first Bind, so both are text. Read every column to warm their
        // capability memos.
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(Field.TEXT_FORMAT, format(rs, 1), "exec#1 composite text");
          assertEquals(Field.TEXT_FORMAT, format(rs, 2), "exec#1 int4range text");
          // getObject materialises each column through Field.initializePgType, warming its capability.
          assertNotNull(rs.getObject(1));
          assertNotNull(rs.getObject(2));
        }
        // Exec #2: the warmed composite and int4range upgrade to binary. Values still decode correctly
        // against the promoted formats.
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(Field.BINARY_FORMAT, format(rs, 1), "exec#2 composite must upgrade to binary");
          assertEquals(Field.BINARY_FORMAT, format(rs, 2), "exec#2 int4range must upgrade to binary");
          assertNotNull(rs.getObject(1));
          assertEquals("[1,3)", rs.getString(2));
        }
        // Exec #3: stable, no oscillation.
        try (ResultSet rs = ps.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(Field.BINARY_FORMAT, format(rs, 1), "exec#3 composite stays binary");
          assertEquals(Field.BINARY_FORMAT, format(rs, 2), "exec#3 int4range stays binary");
        }
      }
    }
  }
}
