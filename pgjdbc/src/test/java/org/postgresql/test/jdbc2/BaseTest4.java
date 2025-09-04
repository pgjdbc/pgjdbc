/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.Version;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

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

  public enum TimestamptzAlways {
   YES, NO
  }

  protected @Nullable Connection con;
  protected @Nullable BinaryMode binaryMode;
  private @Nullable ReWriteBatchedInserts reWriteBatchedInserts;
  protected @Nullable PreferQueryMode preferQueryMode;
  private @Nullable StringType stringType;
  private @Nullable TimestamptzAlways timestamptzAlways;

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
    if (timestamptzAlways  == TimestamptzAlways.YES) {
      PGProperty.SQL_TIMESTAMPTZ_ALWAYS.set(props, true);
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

  public void setTimestamptzAlways(TimestamptzAlways timestamptzAlways) {
    this.timestamptzAlways = timestamptzAlways;
  }

  /**
   * Ensures {@link #setUp()} is called for each subclass.
   * Note fore test implementation: override {@code setUp()} instead.
   * JUnit 5 requires all overridden methods to be annotated with {@code @BeforeEach} which
   * is hard to maintain. If the annotation is missing, then JUnit won't consider the method
   * for setup.
   * @throws Exception if setup fails
   */
  @BeforeEach
  final void beforeEach() throws Exception {
    setUp();
  }

  /**
   * Ensures {@link #tearDown()} is called for each subclass.
   * Note fore test implementation: override {@code tearDown()} instead.
   * JUnit 5 requires all overridden methods to be annotated with {@code @AfterEach} which
   * is hard to maintain. If the annotation is missing, then JUnit won't consider the method
   * for tear down.
   * @throws Exception if setup fails
   */
  @AfterEach
  final void afterEach() throws Exception {
    tearDown();
  }

  /**
   * Prepares the test environment.
   * Note: it might be worth moving "create table" statements to {@code @BeforeAll} methods,
   * so the test creates the table only once, not once for every test method.
   * Dot not add {@code @BeforeEach} annotation when overriding the method.
   * @throws Exception if setup fails
   */
  protected void setUp() throws Exception {
    Properties props = new Properties();
    updateProperties(props);
    con = TestUtil.openDB(props);
    PGConnection pg = con.unwrap(PGConnection.class);
    preferQueryMode = pg == null ? PreferQueryMode.EXTENDED : pg.getPreferQueryMode();
  }

  /**
   * Cleans up the test environment.
   * Dot not add {@code @AfterEach} annotation when overriding the method.
   * @throws SQLException if teardown fails
   */
  protected void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  public static void assumeCallableStatementsSupported(Connection con) throws SQLException {
    PreferQueryMode preferQueryMode = con.unwrap(PGConnection.class).getPreferQueryMode();
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "callable statements are not fully supported in simple protocol execution mode");
  }

  public void assumeCallableStatementsSupported() {
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE, "callable statements are not fully supported in simple protocol execution mode");
  }

  public void assumeBinaryModeRegular() {
    assumeTrue(binaryMode == BinaryMode.REGULAR);
  }

  public void assumeBinaryModeForce() {
    assumeTrue(binaryMode == BinaryMode.FORCE);
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);
  }

  public void assumeNotSimpleQueryMode() {
    assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);
  }

  /**
   * Shorthand for {@code Assume.assumeTrue(TestUtil.haveMinimumServerVersion(conn, version)}.
   */
  public void assumeMinimumServerVersion(String message, Version version) throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, version), message);
  }

  /**
   * Shorthand for {@code Assume.assumeTrue(TestUtil.haveMinimumServerVersion(conn, version)}.
   */
  public void assumeMinimumServerVersion(Version version) throws SQLException {
    assumeTrue(TestUtil.haveMinimumServerVersion(con, version));
  }

  protected void assertBinaryForReceive(int oid, boolean expected, Supplier<String> message) throws SQLException {
    assertEquals(
        expected,
        con.unwrap(BaseConnection.class).getQueryExecutor().useBinaryForReceive(oid),
        () -> message.get() + ", useBinaryForReceive(oid=" + oid + ")");
  }

  protected void assertBinaryForSend(int oid, boolean expected, Supplier<String> message) throws SQLException {
    assertEquals(
        expected,
        con.unwrap(BaseConnection.class).getQueryExecutor().useBinaryForSend(oid),
        () -> message.get() + ", useBinaryForSend(oid=" + oid + ")");
  }
}
