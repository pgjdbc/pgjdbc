/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.PGProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * The {@code maxServerTextMessageSize} connection property reaches the {@link PGStream} of an
 * open connection, and a {@code maxServerTextMessageSize}, {@code maxResultBuffer}, or
 * {@code maxCopyDataSize} value that is not a positive size fails the connection attempt with
 * SQLState 22023.
 *
 * <p>While {@code maxServerTextMessageSize} is unset, the stream applies 64 MB (64000000
 * bytes).</p>
 *
 * <p>Needs a running server.</p>
 */
class MaxServerTextMessageSizeConnectionTest {

  private static PGStream streamOf(Connection con) throws SQLException {
    return ((QueryExecutorBase) con.unwrap(BaseConnection.class).getQueryExecutor()).pgStream;
  }

  @Test
  void anUnsetPropertyLeavesTheDefaultLimit() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      assertEquals(64000000, streamOf(con).getMaxServerTextMessageSize());
    }
  }

  @Test
  void aConnectionPropertySetsTheLimit() throws SQLException {
    Properties props = new Properties();
    PGProperty.MAX_SERVER_TEXT_MESSAGE_SIZE.set(props, "1234567");

    try (Connection con = TestUtil.openDB(props)) {
      assertEquals(1234567, streamOf(con).getMaxServerTextMessageSize());
    }
  }

  @Test
  void aDataSourceSetterSetsTheLimit() throws SQLException {
    PGSimpleDataSource ds = new PGSimpleDataSource();
    BaseDataSourceTest.setupDataSource(ds);
    ds.setMaxServerTextMessageSize("2M");

    try (Connection con = ds.getConnection()) {
      assertAll(
          () -> assertEquals("2M", ds.getMaxServerTextMessageSize()),
          () -> assertEquals(2000000, streamOf(con).getMaxServerTextMessageSize()),
          () -> assertEquals(-1, streamOf(con).getMaxResultBuffer(), "maxResultBuffer"));
    }
  }

  @Test
  void theDataSourceGetterReturnsNullWhileUnset() {
    assertNull(new PGSimpleDataSource().getMaxServerTextMessageSize());
  }

  @ParameterizedTest
  @ValueSource(strings = {"maxServerTextMessageSize", "maxResultBuffer", "maxCopyDataSize"})
  void aSizeThatIsNotPositiveFailsTheConnection(String propertyName) {
    Properties props = new Properties();
    props.setProperty(propertyName, "-1");

    SQLException e = assertThrows(SQLException.class, () -> TestUtil.openDB(props).close());

    assertAll(
        () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState()),
        () -> assertEquals(GT.tr(
            "The {0} connection property must be a positive size, but its value is {1}. Give a byte count such as 150M or a share of the heap such as 10p, or leave the property unset.",
            propertyName, "-1"), e.getMessage()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"maxServerTextMessageSize", "maxResultBuffer", "maxCopyDataSize"})
  void aValueOutsideTheSizeSyntaxFailsTheConnection(String propertyName) {
    Properties props = new Properties();
    props.setProperty(propertyName, "abcM");

    SQLException e = assertThrows(SQLException.class, () -> TestUtil.openDB(props).close());

    assertAll(
        () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState()),
        () -> assertEquals(GT.tr(
            "The {0} connection property has the value {1}, which is not a valid size. Give a byte count such as 150M or a share of the heap with the p suffix, such as 10p.",
            propertyName, "abcM"), e.getMessage()));
  }
}
