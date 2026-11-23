/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class ParameterInjectionTest {
  private interface ParameterBinder {
    void bind(PreparedStatement stmt) throws SQLException;
  }

  private static void testParamInjection(ParameterBinder bindPositiveOne, ParameterBinder bindNegativeOne)
      throws SQLException {
    try (Connection conn = TestUtil.openDB()) {
      {
        PreparedStatement stmt = conn.prepareStatement("SELECT -?");
        bindPositiveOne.bind(stmt);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getMetaData().getColumnCount(),
              "number of result columns must match");
          int value = rs.getInt(1);
          assertEquals(-1, value);
        }
        bindNegativeOne.bind(stmt);
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next());
          assertEquals(1, rs.getMetaData().getColumnCount(),
              "number of result columns must match");
          int value = rs.getInt(1);
          assertEquals(1, value);
        }
      }
      {
        PreparedStatement stmt = conn.prepareStatement("SELECT -?, ?");
        bindPositiveOne.bind(stmt);
        stmt.setString(2, "\nWHERE false --");
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "ResultSet should contain a row");
          assertEquals(2, rs.getMetaData().getColumnCount(),
              "rs.getMetaData().getColumnCount(");
          int value = rs.getInt(1);
          assertEquals(-1, value);
        }

        bindNegativeOne.bind(stmt);
        stmt.setString(2, "\nWHERE false --");
        try (ResultSet rs = stmt.executeQuery()) {
          assertTrue(rs.next(), "ResultSet should contain a row");
          assertEquals(2, rs.getMetaData().getColumnCount(), "rs.getMetaData().getColumnCount(");
          int value = rs.getInt(1);
          assertEquals(1, value);
        }

      }
    }
  }

  @EnabledForServerVersionRange(lt = "19")
  @Test
  public void simpleModeStringAfterStandardConformingStringsOff() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREFER_QUERY_MODE.set(props, "simple");
    String value = "\\'), (SELECT $$injected$$) --";
    try (Connection conn = TestUtil.openDB(props)) {
      conn.setAutoCommit(false);
      PreparedStatement stmt = conn.prepareStatement("SET LOCAL standard_conforming_strings = off; SELECT ?");
      stmt.setString(1, value);
      stmt.execute();
      assertTrue(stmt.getMoreResults());
      try (ResultSet rs = stmt.getResultSet()) {
        assertTrue(rs.next());
        assertEquals(1, rs.getMetaData().getColumnCount(), "number of result columns must match");
        assertEquals(value, rs.getString(1));
      }
      conn.rollback();
    }
  }

  @EnabledForServerVersionRange(lt = "19")
  @Test
  public void simpleModeQueryParsedBeforeStandardConformingStringsOff() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREFER_QUERY_MODE.set(props, "simple");
    String sql = "SELECT ?, '\\', ?";
    String value = ", (SELECT $$injected$$) --";
    try (Connection conn = TestUtil.openDB(props);
         Statement setStmt = conn.createStatement();
         PreparedStatement open = conn.prepareStatement(sql);
         PreparedStatement noBackslash = conn.prepareStatement("SELECT ?, ''")) {
      open.setString(1, "a");
      open.setString(2, "b");
      open.executeQuery().close();
      try (PreparedStatement cached = conn.prepareStatement(sql)) {
        cached.setString(1, "a");
        cached.setString(2, "b");
        cached.executeQuery().close();
      }
      setStmt.execute("SET standard_conforming_strings = off");

      PSQLException e = assertThrows(PSQLException.class, () -> conn.prepareStatement(sql));
      assertEquals(PSQLState.SYNTAX_ERROR.getState(), e.getSQLState());

      open.setString(2, value);
      e = assertThrows(PSQLException.class, open::executeQuery);
      assertEquals(PSQLState.OBJECT_NOT_IN_STATE.getState(), e.getSQLState());

      noBackslash.setString(1, value);
      try (ResultSet rs = noBackslash.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(2, rs.getMetaData().getColumnCount(), "number of result columns must match");
        assertEquals(value, rs.getString(1));
      }
    }
  }

  @EnabledForServerVersionRange(lt = "19")
  @Test
  public void simpleModeYenSignInEucJp() throws SQLException {
    Properties props = new Properties();
    PGProperty.PREFER_QUERY_MODE.set(props, "simple");
    PGProperty.ALLOW_ENCODING_CHANGES.set(props, true);
    String value = "\u00a5'), (SELECT $$injected$$) --"; // yen sign
    try (Connection conn = TestUtil.openDB(props);
         Statement setStmt = conn.createStatement()) {
      setStmt.execute("SET standard_conforming_strings = off");
      setStmt.execute("SET client_encoding = 'EUC_JP'");
      PreparedStatement stmt = conn.prepareStatement("SELECT ?");
      stmt.setString(1, value);
      try (ResultSet rs = stmt.executeQuery()) {
        assertTrue(rs.next());
        assertEquals(1, rs.getMetaData().getColumnCount(), "number of result columns must match");
      }
    }
  }

  @Test
  public void handleInt2() throws SQLException {
    testParamInjection(
        stmt -> {
          stmt.setShort(1, (short) 1);
        },
        stmt -> {
          stmt.setShort(1, (short) -1);
        }
    );
  }

  @Test
  public void handleInt4() throws SQLException {
    testParamInjection(
        stmt -> {
          stmt.setInt(1, 1);
        },
        stmt -> {
          stmt.setInt(1, -1);
        }
    );
  }

  @Test
  public void handleBigInt() throws SQLException {
    testParamInjection(
        stmt -> {
          stmt.setLong(1, (long) 1);
        },
        stmt -> {
          stmt.setLong(1, (long) -1);
        }
    );
  }

  @Test
  public void handleNumeric() throws SQLException {
    testParamInjection(
        stmt -> {
          stmt.setBigDecimal(1, new BigDecimal("1"));
        },
        stmt -> {
          stmt.setBigDecimal(1, new BigDecimal("-1"));
        }
    );
  }

  @Test
  public void handleFloat() throws SQLException {
    testParamInjection(
        stmt -> {
          stmt.setFloat(1, 1);
        },
        stmt -> {
          stmt.setFloat(1, -1);
        }
    );
  }

  @Test
  public void handleDouble() throws SQLException {
    testParamInjection(
        stmt -> {
          stmt.setDouble(1, 1);
        },
        stmt -> {
          stmt.setDouble(1, -1);
        }
    );
  }
}
