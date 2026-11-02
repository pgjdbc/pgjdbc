/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.PGStatement;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@ParameterizedClass
@MethodSource("data")
public class GeneratedKeysTest extends BaseTest4 {
  public enum ReturningInQuery {
    A("a"),
    AB("a", "b"),
    STAR("*"),
    NO();
    final String[] columns;

    ReturningInQuery(String... columns) {
      this.columns = columns;
    }

    public int columnsReturned() {
      if (columns.length == 1 && columns[0].charAt(0) == '*') {
        return 100500; // does not matter much, the meaning is "every possible column"
      }
      return columns.length;
    }

    public String getClause() {
      if (columnsReturned() == 0) {
        return "";
      }
      StringBuilder sb = new StringBuilder(" returning ");
      for (int i = 0; i < columns.length; i++) {
        String column = columns[i];
        if (i != 0) {
          sb.append(", ");
        }
        sb.append(column);
      }
      return sb.toString();
    }
  }

  private final ReturningInQuery returningInQuery;
  private final String returningClause;

  public GeneratedKeysTest(ReturningInQuery returningInQuery, BinaryMode binaryMode) throws Exception {
    this.returningInQuery = returningInQuery;
    this.returningClause = returningInQuery.getClause();
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (ReturningInQuery returningInQuery : ReturningInQuery.values()) {
      for (BinaryMode binaryMode : BinaryMode.values()) {
        ids.add(new Object[]{returningInQuery, binaryMode});
      }
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTempTable(con, "genkeys", "a serial, b varchar(5), c int");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "genkeys");
    super.tearDown();
  }

  @Test
  public void testGeneratedKeys() throws SQLException {
    testGeneratedKeysWithSuffix("");
  }

  private void testGeneratedKeysWithSuffix(String suffix) throws SQLException {
    Statement stmt = con.createStatement();
    int count = stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)" + returningClause + suffix,
        Statement.RETURN_GENERATED_KEYS);
    assertEquals(1, count);
    ResultSet rs = stmt.getGeneratedKeys();
    assert1a2(rs);
  }

  private void assert1a2(ResultSet rs) throws SQLException {
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertEquals(1, rs.getInt("a"));
    if (returningInQuery.columnsReturned() >= 2) {
      assertEquals("a", rs.getString(2));
      assertEquals("a", rs.getString("b"));
    }
    if (returningInQuery.columnsReturned() >= 3) {
      assertEquals("2", rs.getString(3));
      assertEquals(2, rs.getInt("c"));
    }
    assertFalse(rs.next());
  }

  @Test
  public void testStatementUpdateCount() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)" + returningClause,
        Statement.RETURN_GENERATED_KEYS);
    assertEquals(1, stmt.getUpdateCount());
    assertNull(stmt.getResultSet());
    assertFalse(stmt.getMoreResults());
  }

  @Test
  public void testCloseStatementClosesRS() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)" + returningClause,
        Statement.RETURN_GENERATED_KEYS);
    ResultSet rs = stmt.getGeneratedKeys();
    stmt.close();
    assertTrue(rs.isClosed(), "statement was closed, thus the resultset should be closed as well");
    try {
      rs.next();
      fail("Can't operate on a closed result set.");
    } catch (SQLException sqle) {
    }
  }

  @Test
  public void testReturningWithTrailingSemicolon() throws SQLException {
    testGeneratedKeysWithSuffix("; ");
  }

  @Test
  public void testEmptyRSWithoutReturning() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      int count =
          stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)" + returningClause + "; ",
              Statement.NO_GENERATED_KEYS);
      assertEquals(1, count);
      if (returningInQuery.columnsReturned() > 0) {
        fail("A result was returned when none was expected error should happen when executing executeUpdate('... returning ...')");
      }
    } catch (SQLException e) {
      if (returningInQuery.columnsReturned() > 0 && "0100E".equals(e.getSQLState())) {
        // A result was returned when none was expected
        return; // just as expected
      }
      throw e;
    }
    ResultSet rs = stmt.getGeneratedKeys();
    assertFalse(rs.next(), "Statement.NO_GENERATED_KEYS => stmt.getGeneratedKeys() should be empty");
  }

  @Test
  public void testMultipleRows() throws SQLException {
    Statement stmt = con.createStatement();
    int count = stmt.executeUpdate(
        "INSERT INTO genkeys VALUES (1, 'a', 2), (2, 'b', 4)" + returningClause + "; ",
        new String[]{"c", "b"});
    assertEquals(2, count);
    ResultSet rs = stmt.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB1(rs);
    assertTrue(rs.next());
    assertCB2(rs);
    assertFalse(rs.next());
  }

  @Test
  public void testSerialWorks() throws SQLException {
    Statement stmt = con.createStatement();
    int count = stmt.executeUpdate(
        "INSERT/*fool parser*/ INTO genkeys (b,c) VALUES ('a', 2), ('b', 4)" + returningClause + "; ",
        new String[]{"a"});
    assertEquals(2, count);
    ResultSet rs = stmt.getGeneratedKeys();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertFalse(rs.next());
  }

  @Test
  public void testUpdate() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 3)");
    stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
    stmt.executeUpdate("UPDATE genkeys SET c=2 WHERE a = 1" + returningClause,
        new String[]{"c", "b"});
    ResultSet rs = stmt.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB1(rs);
    assertFalse(rs.next());
  }

  @Test
  public void testWithInsertInsert() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v9_1);
    Statement stmt = con.createStatement();
    int count = stmt.executeUpdate(
        "WITH x as (INSERT INTO genkeys (b,c) VALUES ('a', 2) returning c) insert into genkeys(a,b,c) VALUES (1, 'a', 2)" + returningClause + "",
        new String[]{"c", "b"});
    assertEquals(1, count);
    ResultSet rs = stmt.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB1(rs);
    assertFalse(rs.next());
  }

  @Test
  public void testWithInsertSelect() throws SQLException {
    assumeMinimumServerVersion(ServerVersion.v9_1);
    assumeTrue(returningInQuery != ReturningInQuery.NO);
    Statement stmt = con.createStatement();
    int count = stmt.executeUpdate(
        "WITH x as (INSERT INTO genkeys(a,b,c) VALUES (1, 'a', 2) " + returningClause
            + ") select * from x",
        new String[]{"c", "b"});
    assertEquals(-1, count, "rowcount");
    // TODO: should SELECT produce rows through getResultSet or getGeneratedKeys?
    ResultSet rs = stmt.getResultSet();
    assertTrue(rs.next());
    assertCB1(rs);
    assertFalse(rs.next());
  }

  @Test
  public void testDelete() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)");
    stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
    stmt.executeUpdate("DELETE FROM genkeys WHERE a = 1" + returningClause,
        new String[]{"c", "b"});
    ResultSet rs = stmt.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB1(rs);
    assertFalse(rs.next());
  }

  @Test
  public void testPSUpdate() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', -3)");
    stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
    stmt.close();

    PreparedStatement ps =
        con.prepareStatement("UPDATE genkeys SET c=? WHERE a = ?" + returningClause, new String[]{"c", "b"});
    ps.setInt(1, 2);
    ps.setInt(2, 1);
    assertEquals(1, ps.executeUpdate());
    ResultSet rs = ps.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB1(rs);
    assertFalse(rs.next());
  }

  @Test
  public void testPSDelete() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)");
    stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 4)");
    stmt.close();

    PreparedStatement ps =
        con.prepareStatement("DELETE FROM genkeys WHERE a = ?" + returningClause, new String[]{"c", "b"});

    ps.setInt(1, 1);
    assertEquals(1, ps.executeUpdate());
    ResultSet rs = ps.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB1(rs);
    assertFalse(rs.next());

    ps.setInt(1, 2);
    assertEquals(1, ps.executeUpdate());
    rs = ps.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB2(rs);
    assertFalse(rs.next());
  }

  private void assertCB1(ResultSet rs) throws SQLException {
    ResultSetMetaData rsmd = rs.getMetaData();
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= rsmd.getColumnCount(); i++) {
      if (i > 1) {
        sb.append(", ");
      }
      sb.append(rsmd.getColumnName(i));
    }
    String columnNames = sb.toString();
    switch (returningInQuery) {
      case NO:
        assertEquals("c, b", columnNames, "Two columns should be returned since returning clause was empty and {c, b} was requested via API");
        assertEquals(2, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertEquals(2, rs.getInt("c"));
        assertEquals("a", rs.getString("b"));
        break;
      case A:
        assertEquals("a", columnNames, "Just one column should be returned since returning clause was " + returningClause);
        assertEquals(1, rs.getInt(1));
        assertEquals(1, rs.getInt("a"));
        break;
      case AB:
        assertEquals("a, b", columnNames, "Two columns should be returned since returning clause was " + returningClause);
        assertEquals(1, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertEquals(1, rs.getInt("a"));
        assertEquals("a", rs.getString("b"));
        break;
      case STAR:
        assertEquals("a, b, c", columnNames, "Three columns should be returned since returning clause was " + returningClause);
        assertEquals(1, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertEquals(2, rs.getInt(3));
        assertEquals(1, rs.getInt("a"));
        assertEquals("a", rs.getString("b"));
        assertEquals(2, rs.getInt("c"));
        break;
      default:
        fail("Unexpected test kind: " + returningInQuery);
    }
  }

  private void assertCB2(ResultSet rs) throws SQLException {
    switch (returningInQuery) {
      case NO:
        assertEquals(2, rs.getMetaData().getColumnCount(), "Two columns should be returned since returning clause was empty and {c, b} was requested via API");
        assertEquals(4, rs.getInt(1));
        assertEquals("b", rs.getString(2));
        break;
      case A:
        assertEquals(1, rs.getMetaData().getColumnCount(), "Just one column should be returned since returning clause was " + returningClause);
        assertEquals(2, rs.getInt(1));
        break;
      case AB:
        assertEquals(2, rs.getMetaData().getColumnCount(), "Two columns should be returned since returning clause was " + returningClause);
        assertEquals(2, rs.getInt(1));
        assertEquals("b", rs.getString(2));
        break;
      case STAR:
        assertEquals(3, rs.getMetaData().getColumnCount(), "Three columns should be returned since returning clause was " + returningClause);
        assertEquals(2, rs.getInt(1));
        assertEquals("b", rs.getString(2));
        assertEquals(4, rs.getInt(3));
        break;
      default:
        fail("Unexpected test kind: " + returningInQuery);
    }
  }

  @Test
  public void testGeneratedKeysCleared() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)" + returningClause + "; ", Statement.RETURN_GENERATED_KEYS);
    ResultSet rs = stmt.getGeneratedKeys();
    assertTrue(rs.next());
    try {
      stmt.executeUpdate("INSERT INTO genkeys VALUES (2, 'b', 3)" + returningClause);
      if (returningInQuery.columnsReturned() > 0) {
        fail("A result was returned when none was expected error should happen when executing executeUpdate('... returning ...')");
      }
    } catch (SQLException e) {
      if (returningInQuery.columnsReturned() > 0 && "0100E".equals(e.getSQLState())) {
        // A result was returned when none was expected
        return; // just as expected
      }
      throw e;
    }
    rs = stmt.getGeneratedKeys();
    assertFalse(rs.next());
  }

  @Test
  public void testBatchGeneratedKeys() throws SQLException {
    PreparedStatement ps = con.prepareStatement("INSERT INTO genkeys(c) VALUES (?)" + returningClause + "",
        Statement.RETURN_GENERATED_KEYS);
    ps.setInt(1, 4);
    ps.addBatch();
    ps.setInt(1, 7);
    ps.addBatch();
    ps.executeBatch();
    ResultSet rs = ps.getGeneratedKeys();
    assertTrue(rs.next(), "getGeneratedKeys.next() should be non-empty");
    assertEquals(1, rs.getInt("a"));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt("a"));
    assertFalse(rs.next());
  }

  private PreparedStatement prepareSelect() throws SQLException {
    PreparedStatement ps;
    String sql = "select c from genkeys";
    switch (returningInQuery) {
      case NO:
        ps = con.prepareStatement(sql);
        break;
      case STAR:
        ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        break;
      default:
        ps = con.prepareStatement(sql, returningInQuery.columns);
    }
    return ps;
  }

  /**
   * Multi-statement SQL cannot report generated keys: it becomes a CompositeQuery, whose
   * getSqlCommand() is null. Requesting them used to append RETURNING to every DML statement
   * anyway. Statement splitting is off for a non-parameterized query below extended mode, and
   * there the parser also drops the separating semicolon, so the RETURNING clause landed in the
   * middle of the concatenated SQL and the server answered 42601. Every mode is exercised
   * because the entry points failed differently in each.
   */
  @Test
  public void multiStatementIgnoresGeneratedKeys() throws Exception {
    assumeTrue(returningInQuery == ReturningInQuery.NO,
        "the test drives the RETURNING clause itself");
    String sql = "INSERT INTO genkeys VALUES (1, 'a', 2); INSERT INTO genkeys VALUES (2, 'b', 4)";

    for (PreferQueryMode mode : new PreferQueryMode[]{PreferQueryMode.SIMPLE,
        PreferQueryMode.EXTENDED_FOR_PREPARED, PreferQueryMode.EXTENDED}) {
      Properties props = new Properties();
      updateProperties(props);
      PGProperty.PREFER_QUERY_MODE.set(props, mode.value());
      try (Connection modeCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(modeCon, "genkeys", "a serial, b varchar(5), c int");

        // Both statements insert one row, so whichever count the driver reports has to be 1
        try (PreparedStatement ps = modeCon.prepareStatement(sql, new String[]{"c"})) {
          assertEquals(1, ps.executeUpdate(), () -> mode + ": executeUpdate of " + sql);
          assertFalse(ps.getGeneratedKeys().next(),
              () -> mode + ": generated keys are not reported for multi-statement SQL");
        }
        assertEquals(2, countGenkeys(modeCon), () -> mode + ": both statements must run");

        try (Statement stmt = modeCon.createStatement()) {
          stmt.executeUpdate("DELETE FROM genkeys");
          assertEquals(1, stmt.executeUpdate(sql, new String[]{"c"}),
              () -> mode + ": executeUpdate of " + sql);
          assertFalse(stmt.getGeneratedKeys().next(),
              () -> mode + ": generated keys are not reported for multi-statement SQL");
        }
        assertEquals(2, countGenkeys(modeCon), () -> mode + ": both statements must run");
      }
    }
  }

  /**
   * A comment after the final semicolon counts as a statement, so the query reports no generated
   * keys - in every mode, and before this change as well. The update count is what used to be
   * wrong: RETURNING was appended to SQL the driver then classified as BLANK, so a row came back
   * that nothing read and executeUpdate answered -1.
   */
  @Test
  public void trailingCommentKeepsUpdateCount() throws Exception {
    assumeTrue(returningInQuery == ReturningInQuery.NO,
        "the test drives the RETURNING clause itself");

    for (String sql : new String[]{"INSERT INTO genkeys VALUES (1, 'a', 2); -- done",
        "INSERT INTO genkeys VALUES (1, 'a', 2); /* done */"}) {
      try (Statement stmt = con.createStatement()) {
        stmt.executeUpdate("DELETE FROM genkeys");
        assertEquals(1, stmt.executeUpdate(sql, new String[]{"c"}),
            () -> "executeUpdate of " + sql);
        assertFalse(stmt.getGeneratedKeys().next(),
            () -> "a trailing comment counts as a statement, so no keys are reported: " + sql);
      }
      assertEquals(1, countGenkeys(con), () -> "the statement must run: " + sql);
    }
  }

  private static int countGenkeys(Connection con) throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT count(*) FROM genkeys")) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  public void selectWithGeneratedKeysViaPreparedExecuteQuery() throws SQLException {
    PreparedStatement ps = prepareSelect();
    ResultSet rs = ps.executeQuery();
    assertFalse(rs.next(), "genkeys table is empty, thus rs.next() should return false");
    ps.close();
  }

  @Test
  public void selectWithGeneratedKeysViaPreparedExecute() throws SQLException {
    PreparedStatement ps = prepareSelect();
    ps.execute();
    ResultSet rs = ps.getResultSet();
    assertFalse(rs.next(), "genkeys table is empty, thus rs.next() should return false");
    ps.close();
  }

  @Test
  public void selectWithGeneratedKeysViaNonPrepared() throws SQLException {
    Statement s = con.createStatement();
    String sql = "select c from genkeys";
    ResultSet rs;
    switch (returningInQuery) {
      case NO:
        s.execute(sql);
        rs = s.getResultSet();
        break;
      case STAR:
        s.execute(sql, Statement.RETURN_GENERATED_KEYS);
        rs = s.getResultSet();
        break;
      default:
        s.execute(sql, returningInQuery.columns);
        rs = s.getResultSet();
    }
    assertNotNull(rs, "SELECT statement should return results via getResultSet, not getGeneratedKeys");
    assertFalse(rs.next(), "genkeys table is empty, thus rs.next() should return false");
    s.close();
  }

  @Test
  public void breakDescribeOnFirstServerPreparedExecution() throws SQLException {
    // Test code is adapted from https://github.com/pgjdbc/pgjdbc/issues/811#issuecomment-352468388

    PreparedStatement ps =
        con.prepareStatement("insert into genkeys(b) values(?)" + returningClause,
            Statement.RETURN_GENERATED_KEYS);
    ps.setString(1, "TEST");

    // The below "prepareThreshold - 1" executions ensure that bind failure would happen
    // exactly on prepareThreshold execution (the first one when server flips to server-prepared)
    int prepareThreshold = ps.unwrap(PGStatement.class).getPrepareThreshold();
    for (int i = 0; i < prepareThreshold - 1; i++) {
      ps.executeUpdate();
    }
    try {
      // Send a value that's too long on the 5th request
      ps.setString(1, "TESTTESTTEST");
      ps.executeUpdate();
    } catch (SQLException e) {
      // Expected error: org.postgresql.util.PSQLException: ERROR: value
      // too long for type character varying(10)
      if (!PSQLState.STRING_DATA_RIGHT_TRUNCATION.getState().equals(e.getSQLState())) {
        throw e;
      }
    }
    // Send a valid value on the next request
    ps.setString(1, "TEST");
    ps.executeUpdate();
  }

}
