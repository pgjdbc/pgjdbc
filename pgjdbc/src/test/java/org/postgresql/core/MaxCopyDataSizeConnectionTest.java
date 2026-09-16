/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.copy.CopyManager;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The {@code maxCopyDataSize} connection property reaches the {@link PGStream} of an open
 * connection, and a COPY TO STDOUT that receives a CopyData message over the limit fails with the
 * limit message and closes the connection.
 *
 * <p>While the property is unset the stream applies 64 MB (64000000 bytes), unless its mode is
 * {@link ProtocolHardeningMode#DISABLE}; the two tests of that case set the mode on the
 * connection's stream. A set value applies in either mode, so its tests leave the mode the JVM
 * chose. Invalid values are covered by {@link MaxServerTextMessageSizeConnectionTest}.</p>
 *
 * <p>Needs a running server.</p>
 */
class MaxCopyDataSizeConnectionTest {

  private static final String CONFIGURED_LIMIT =
      "Protocol error. CopyData message has length {0}, which exceeds the maxCopyDataSize limit of {1} bytes.";

  /** Sends one row of 1000 letters, which arrives as a CopyData message of length 1005. */
  private static final String COPY_ONE_ROW = "COPY (SELECT repeat('a', 1000)) TO STDOUT";

  private static PGStream streamOf(Connection con) throws SQLException {
    return ((QueryExecutorBase) con.unwrap(BaseConnection.class).getQueryExecutor()).pgStream;
  }

  private static Properties withMaxCopyDataSize(String value) {
    Properties props = new Properties();
    PGProperty.MAX_COPY_DATA_SIZE.set(props, value);
    return props;
  }

  @Test
  void anUnsetPropertyLeavesTheBuiltInLimit() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      PGStream stream = streamOf(con);
      stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);

      stream.checkCopyDataSize(64000000);
      PSQLException e = assertThrowsExactly(PSQLException.class,
          () -> stream.checkCopyDataSize(64000001));

      assertEquals(GT.tr(
          "Protocol error. CopyData message has length {0}, which exceeds the built-in limit of {1} bytes. Raise the {2} connection property if the backend legitimately sends more, or set -D{3}=disable to skip these limits altogether.",
          "64000001", "64000000", "maxCopyDataSize", "pgjdbc.protocolHardeningMode"),
          e.getMessage());
    }
  }

  @Test
  void anUnsetPropertyLeavesNoLimitUnderDisable() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      PGStream stream = streamOf(con);
      stream.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);

      stream.checkCopyDataSize(1073741823);

      assertFalse(stream.isClosed(), "isClosed()");
    }
  }

  @Test
  void aConnectionPropertySetsTheLimit() throws SQLException {
    try (Connection con = TestUtil.openDB(withMaxCopyDataSize("1000"))) {
      PGStream stream = streamOf(con);

      stream.checkCopyDataSize(1000);
      PSQLException e = assertThrowsExactly(PSQLException.class,
          () -> stream.checkCopyDataSize(1001));

      assertEquals(GT.tr(CONFIGURED_LIMIT, "1001", "1000"), e.getMessage());
    }
  }

  @Test
  void aDataSourceSetterSetsTheLimit() throws SQLException {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    BaseDataSourceTest.setupDataSource(ds);
    ds.setMaxCopyDataSize("2M");

    try (Connection con = ds.getConnection()) {
      PGStream stream = streamOf(con);
      PSQLException e = assertThrowsExactly(PSQLException.class,
          () -> stream.checkCopyDataSize(2000001));

      assertAll(
          () -> assertEquals("2M", ds.getMaxCopyDataSize()),
          () -> assertEquals(GT.tr(CONFIGURED_LIMIT, "2000001", "2000000"), e.getMessage()));
    }
  }

  @Test
  void theDataSourceGetterReturnsNullWhileUnset() {
    assertNull(new PGSimpleDataSource().getMaxCopyDataSize());
  }

  @Test
  void aCopyOutAtTheLimitCompletes() throws Exception {
    try (Connection con = TestUtil.openDB(withMaxCopyDataSize("1005"))) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();

      long rows = new CopyManager(con.unwrap(BaseConnection.class)).copyOut(COPY_ONE_ROW, out);

      assertAll(
          () -> assertEquals(1, rows, "rows copied"),
          () -> assertEquals(1001, out.size(), "bytes copied"));
    }
  }

  /**
   * The limit message is the message of the exception the caller catches, not of its cause.
   */
  @Test
  void aCopyOutOverTheLimitFailsWithTheLimitMessageAndClosesTheConnection() throws Exception {
    try (Connection con = TestUtil.openDB(withMaxCopyDataSize("1000"))) {
      CopyManager copyManager = new CopyManager(con.unwrap(BaseConnection.class));

      PSQLException e = assertThrowsExactly(PSQLException.class,
          () -> copyManager.copyOut(COPY_ONE_ROW, new ByteArrayOutputStream()));

      assertAll(
          () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
          () -> assertEquals(GT.tr(CONFIGURED_LIMIT, "1005", "1000"), e.getMessage()),
          () -> assertTrue(con.isClosed(), "isClosed()"));
    }
  }
}
