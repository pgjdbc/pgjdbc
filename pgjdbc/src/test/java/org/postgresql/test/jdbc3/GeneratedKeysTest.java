/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.PGStatement;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
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

  @Parameterized.Parameters(name = "returningInQuery = {0}, binary = {1}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
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
    assertTrue(!rs.next());
  }

  @Test
  public void testStatementUpdateCount() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)" + returningClause,
        Statement.RETURN_GENERATED_KEYS);
    assertEquals(1, stmt.getUpdateCount());
    assertNull(stmt.getResultSet());
    assertTrue(!stmt.getMoreResults());
  }

  @Test
  public void testCloseStatementClosesRS() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.executeUpdate("INSERT INTO genkeys VALUES (1, 'a', 2)" + returningClause,
        Statement.RETURN_GENERATED_KEYS);
    ResultSet rs = stmt.getGeneratedKeys();
    stmt.close();
    assertTrue("statement was closed, thus the resultset should be closed as well", rs.isClosed());
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
        fail(
            "A result was returned when none was expected error should happen when executing executeUpdate('... returning ...')");
      }
    } catch (SQLException e) {
      if (returningInQuery.columnsReturned() > 0 && "0100E".equals(e.getSQLState())) {
        // A result was returned when none was expected
        return; // just as expected
      }
      throw e;
    }
    ResultSet rs = stmt.getGeneratedKeys();
    assertFalse("Statement.NO_GENERATED_KEYS => stmt.getGeneratedKeys() should be empty", rs.next());
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
    assertTrue(!rs.next());
  }

  @Test
  public void testSerialWorks() throws SQLException {
    Statement stmt = con.createStatement();
    int count = stmt.executeUpdate(
        "INSERT INTO genkeys (b,c) VALUES ('a', 2), ('b', 4)" + returningClause + "; ",
        new String[]{"a"});
    assertEquals(2, count);
    ResultSet rs = stmt.getGeneratedKeys();
    assertTrue(rs.next());
    assertEquals(1, rs.getInt(1));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertTrue(!rs.next());
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
    assertTrue(!rs.next());
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
    assertTrue(!rs.next());
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
    assertTrue(!rs.next());
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
    assertTrue(!rs.next());
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
    assertTrue(!rs.next());

    ps.setInt(1, 2);
    assertEquals(1, ps.executeUpdate());
    rs = ps.getGeneratedKeys();
    assertTrue(rs.next());
    assertCB2(rs);
    assertTrue(!rs.next());
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
        assertEquals("Two columns should be returned since returning clause was empty and {c, b} was requested via API",
            "c, b", columnNames);
        assertEquals(2, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertEquals(2, rs.getInt("c"));
        assertEquals("a", rs.getString("b"));
        break;
      case A:
        assertEquals("Just one column should be returned since returning clause was " + returningClause,
            "a", columnNames);
        assertEquals(1, rs.getInt(1));
        assertEquals(1, rs.getInt("a"));
        break;
      case AB:
        assertEquals("Two columns should be returned since returning clause was " + returningClause,
            "a, b", columnNames);
        assertEquals(1, rs.getInt(1));
        assertEquals("a", rs.getString(2));
        assertEquals(1, rs.getInt("a"));
        assertEquals("a", rs.getString("b"));
        break;
      case STAR:
        assertEquals("Three columns should be returned since returning clause was " + returningClause,
            "a, b, c", columnNames);
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
        assertEquals("Two columns should be returned since returning clause was empty and {c, b} was requested via API",
            2, rs.getMetaData().getColumnCount());
        assertEquals(4, rs.getInt(1));
        assertEquals("b", rs.getString(2));
        break;
      case A:
        assertEquals("Just one column should be returned since returning clause was " + returningClause,
            1, rs.getMetaData().getColumnCount());
        assertEquals(2, rs.getInt(1));
        break;
      case AB:
        assertEquals("Two columns should be returned since returning clause was " + returningClause,
            2, rs.getMetaData().getColumnCount());
        assertEquals(2, rs.getInt(1));
        assertEquals("b", rs.getString(2));
        break;
      case STAR:
        assertEquals("Three columns should be returned since returning clause was " + returningClause,
            3, rs.getMetaData().getColumnCount());
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
    assertTrue(!rs.next());
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
    assertTrue("getGeneratedKeys.next() should be non-empty", rs.next());
    assertEquals(1, rs.getInt("a"));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt("a"));
    assertTrue(!rs.next());
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

  @Test
  public void selectWithGeneratedKeysViaPreparedExecuteQuery() throws SQLException {
    PreparedStatement ps = prepareSelect();
    ResultSet rs = ps.executeQuery();
    assertFalse("genkeys table is empty, thus rs.next() should return false", rs.next());
    ps.close();
  }

  @Test
  public void selectWithGeneratedKeysViaPreparedExecute() throws SQLException {
    PreparedStatement ps = prepareSelect();
    ps.execute();
    ResultSet rs = ps.getResultSet();
    assertFalse("genkeys table is empty, thus rs.next() should return false", rs.next());
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
    assertNotNull("SELECT statement should return results via getResultSet, not getGeneratedKeys", rs);
    assertFalse("genkeys table is empty, thus rs.next() should return false", rs.next());
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

