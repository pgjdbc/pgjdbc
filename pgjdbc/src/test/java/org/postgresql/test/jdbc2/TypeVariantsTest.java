/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;

import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * With {@code preparedStatementCacheTypeVariants > 1}, one SQL text keeps a server-prepared
 * statement per parameter-type signature: alternating bind types reuses the two statements
 * instead of re-preparing on every switch, and a signature beyond the budget evicts the least
 * recently used statement.
 */
class TypeVariantsTest extends BaseTest4 {

  private static final String MARKER = "/*TypeVariantsTest*/";

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.PREPARED_STATEMENT_CACHE_TYPE_VARIANTS.set(props, "2");
  }

  @Test
  void alternatingTypesKeepBothStatements() throws SQLException {
    PreparedStatement ps = con.prepareStatement("SELECT " + MARKER + " ?");
    ps.unwrap(PGStatement.class).setPrepareThreshold(1);

    executeWithInt(ps, 1);
    executeWithString(ps, "a");
    Set<String> names = new TreeSet<>(serverStatementNames());
    assertEquals(2, names.size(), "one statement per signature expected");

    for (int i = 0; i < 5; i++) {
      executeWithInt(ps, i);
      executeWithString(ps, "s" + i);
    }
    assertEquals(names, new TreeSet<>(serverStatementNames()),
        "alternating types must reuse the two statements, not re-prepare");
    ps.close();
  }

  @Test
  void thirdSignatureEvictsLeastRecentlyUsed() throws SQLException {
    PreparedStatement ps = con.prepareStatement("SELECT " + MARKER + " ?");
    ps.unwrap(PGStatement.class).setPrepareThreshold(1);

    executeWithInt(ps, 1);
    List<String> afterInt = serverStatementNames();
    executeWithString(ps, "a");

    ps.setLong(1, 5L);
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
    }

    List<String> names = serverStatementNames();
    assertEquals(2, names.size(), "the budget of two statements must hold");
    assertFalse(names.containsAll(afterInt),
        "the int4 statement was least recently used and must have been evicted");
    ps.close();
  }

  private void executeWithInt(PreparedStatement ps, int value) throws SQLException {
    ps.setInt(1, value);
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(value, rs.getInt(1));
    }
  }

  private void executeWithString(PreparedStatement ps, String value) throws SQLException {
    ps.setString(1, value);
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(value, rs.getString(1));
    }
  }

  private List<String> serverStatementNames() throws SQLException {
    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT name FROM pg_prepared_statements"
                + " WHERE statement LIKE '%" + MARKER + "%' ORDER BY name")) {
      List<String> names = new ArrayList<>();
      while (rs.next()) {
        names.add(rs.getString(1));
      }
      return names;
    }
  }
}
