/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.Version;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public class BaseTest4 {

  public enum BinaryMode {
    REGULAR, FORCE
  }

  public enum ReWriteBatchedInserts {
    YES, NO
  }

  public enum AutoCommit {
    YES, NO
  }

  public enum StringType {
    UNSPECIFIED, VARCHAR;
  }

  protected Connection con;
  private BinaryMode binaryMode;
  private ReWriteBatchedInserts reWriteBatchedInserts;
  protected PreferQueryMode preferQueryMode;
  private StringType stringType;

  protected void updateProperties(Properties props) {
    if (binaryMode == BinaryMode.FORCE) {
      forceBinary(props);
    }
    if (reWriteBatchedInserts == ReWriteBatchedInserts.YES) {
      PGProperty.REWRITE_BATCHED_INSERTS.set(props, true);
    }
    if (stringType != null) {
      PGProperty.STRING_TYPE.set(props, stringType.name().toLowerCase());
    }
  }

  protected void forceBinary(Properties props) {
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
  }

  public final void setBinaryMode(BinaryMode binaryMode) {
    this.binaryMode = binaryMode;
  }

  public StringType getStringType() {
    return stringType;
  }

  public void setStringType(StringType stringType) {
    this.stringType = stringType;
  }

  public void setReWriteBatchedInserts(
      ReWriteBatchedInserts reWriteBatchedInserts) {
    this.reWriteBatchedInserts = reWriteBatchedInserts;
  }

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    updateProperties(props);
    con = TestUtil.openDB(props);
    PGConnection pg = con.unwrap(PGConnection.class);
    preferQueryMode = pg == null ? PreferQueryMode.EXTENDED : pg.getPreferQueryMode();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  public void assumeByteaSupported() {
    Assume.assumeTrue("bytea is not supported in simple protocol execution mode",
        preferQueryMode.compareTo(PreferQueryMode.EXTENDED) >= 0);
  }

  public void assumeCallableStatementsSupported() {
    Assume.assumeTrue("callable statements are not fully supported in simple protocol execution mode",
        preferQueryMode.compareTo(PreferQueryMode.EXTENDED) >= 0);
  }

  public void assumeBinaryModeRegular() {
    Assume.assumeTrue(binaryMode == BinaryMode.REGULAR);
  }

  public void assumeBinaryModeForce() {
    Assume.assumeTrue(binaryMode == BinaryMode.FORCE);
    Assume.assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);
  }

  /**
   * Shorthand for {@code Assume.assumeTrue(TestUtil.haveMinimumServerVersion(conn, version)}.
   */
  public void assumeMinimumServerVersion(String message, Version version) throws SQLException {
    Assume.assumeTrue(message, TestUtil.haveMinimumServerVersion(con, version));
  }

  /**
   * Shorthand for {@code Assume.assumeTrue(TestUtil.haveMinimumServerVersion(conn, version)}.
   */
  public void assumeMinimumServerVersion(Version version) throws SQLException {
    Assume.assumeTrue(TestUtil.haveMinimumServerVersion(con, version));
  }

}
