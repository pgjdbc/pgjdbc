/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class ParameterInjectionTest {
  private interface ParameterBinder {
    void bind(PreparedStatement stmt) throws SQLException;
  }

  private void testParamInjection(ParameterBinder bindPositiveOne, ParameterBinder bindNegativeOne)
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
