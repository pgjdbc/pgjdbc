/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.Version;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Properties;
import java.util.function.Supplier;

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
    UNSPECIFIED, VARCHAR
  }

  protected @Nullable Connection con;
  protected @Nullable BinaryMode binaryMode;
  private @Nullable ReWriteBatchedInserts reWriteBatchedInserts;
  protected @Nullable PreferQueryMode preferQueryMode;
  private @Nullable StringType stringType;

  protected void updateProperties(Properties props) {
    if (binaryMode == BinaryMode.FORCE) {
      forceBinary(props);
    }
    if (reWriteBatchedInserts != null) {
      PGProperty.REWRITE_BATCHED_INSERTS.set(props,
          reWriteBatchedInserts == ReWriteBatchedInserts.YES);
    }
    if (stringType != null) {
      PGProperty.STRING_TYPE.set(props, stringType.name().toLowerCase(Locale.ROOT));
    }
  }

  protected static void forceBinary(Properties props) {
    PGProperty.PREPARE_THRESHOLD.set(props, -1);
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, Oid.BOOL);
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

  public static void assumeCallableStatementsSupported(Connection con) throws SQLException {
    PreferQueryMode preferQueryMode = con.unwrap(PGConnection.class).getPreferQueryMode();
    Assume.assumeTrue("callable statements are not fully supported in simple protocol execution mode",
        preferQueryMode != PreferQueryMode.SIMPLE);
  }

  public void assumeCallableStatementsSupported() {
    Assume.assumeTrue("callable statements are not fully supported in simple protocol execution mode",
        preferQueryMode != PreferQueryMode.SIMPLE);
  }

  public void assumeBinaryModeRegular() {
    Assume.assumeTrue(binaryMode == BinaryMode.REGULAR);
  }

  public void assumeBinaryModeForce() {
    Assume.assumeTrue(binaryMode == BinaryMode.FORCE);
    Assume.assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);
  }

  public void assumeNotSimpleQueryMode() {
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

  protected void assertBinaryForReceive(int oid, boolean expected, Supplier<String> message) throws SQLException {
    assertEquals(message.get() + ", useBinaryForReceive(oid=" + oid + ")", expected,
        con.unwrap(BaseConnection.class).getQueryExecutor().useBinaryForReceive(oid));
  }

  protected void assertBinaryForSend(int oid, boolean expected, Supplier<String> message) throws SQLException {
    assertEquals(message.get() + ", useBinaryForSend(oid=" + oid + ")", expected,
        con.unwrap(BaseConnection.class).getQueryExecutor().useBinaryForSend(oid));
  }
}
