/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGStatement;

import org.junit.jupiter.api.Test;

import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * One-shot executions (describe-only ones in particular) must not disturb the named
 * server-prepared statement: a {@code getParameterMetaData()} call with parameter types that do
 * not match the prepared statement describes via the unnamed statement instead of destroying and
 * re-preparing the named one.
 */
class OneShotDescribeTest extends BaseTest4 {

  private static final String MARKER = "/*OneShotDescribeTest*/";

  @Test
  void parameterMetaDataWithDifferentTypeKeepsServerStatement() throws SQLException {
    PreparedStatement ps = con.prepareStatement("SELECT " + MARKER + " ?");
    ps.unwrap(PGStatement.class).setPrepareThreshold(1);

    ps.setInt(1, 1);
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(1, rs.getInt(1));
    }

    List<String> namesBefore = serverStatementNames();
    assertEquals(1, namesBefore.size(),
        "one named server statement expected after crossing prepareThreshold");

    // Different parameter type: the describe must go through the unnamed statement and report
    // the new type, leaving the named statement (prepared for int4) alone.
    ps.setString(1, "42");
    ParameterMetaData pmd = ps.getParameterMetaData();
    assertEquals(Types.VARCHAR, pmd.getParameterType(1),
        "describe with a varchar parameter reports varchar");

    assertEquals(namesBefore, serverStatementNames(),
        "describe with a mismatching type must not destroy the named statement");

    // The named statement is still valid for its original type: no re-prepare happens.
    ps.setInt(1, 2);
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
      assertEquals(2, rs.getInt(1));
    }

    assertEquals(namesBefore, serverStatementNames(),
        "executing with the original type reuses the same named statement");
    ps.close();
  }

  @Test
  void parameterMetaDataWithMatchingTypeUsesNamedStatement() throws SQLException {
    PreparedStatement ps = con.prepareStatement("SELECT " + MARKER + " ?");
    ps.unwrap(PGStatement.class).setPrepareThreshold(1);

    ps.setInt(1, 1);
    try (ResultSet rs = ps.executeQuery()) {
      assertTrue(rs.next());
    }
    List<String> namesBefore = serverStatementNames();

    ps.setInt(1, 2);
    ParameterMetaData pmd = ps.getParameterMetaData();
    assertEquals(Types.INTEGER, pmd.getParameterType(1));

    assertEquals(namesBefore, serverStatementNames(),
        "describe with the matching type reuses the named statement");
    ps.close();
  }

  private List<String> serverStatementNames() throws SQLException {
    List<String> names = new ArrayList<>();
    try (Statement st = con.createStatement();
        ResultSet rs = st.executeQuery(
            "SELECT name FROM pg_prepared_statements"
                + " WHERE statement LIKE '%" + MARKER + "%' ORDER BY name")) {
      while (rs.next()) {
        names.add(rs.getString(1));
      }
    }
    return names;
  }
}
