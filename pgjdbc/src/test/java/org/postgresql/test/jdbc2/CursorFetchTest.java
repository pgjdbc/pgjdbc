/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * Tests for using non-zero setFetchSize().
 */
@ParameterizedClass
@MethodSource("data")
public class CursorFetchTest extends BaseTest4 {

  public CursorFetchTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.createTable(con, "test_fetch", "value integer");
    con.setAutoCommit(false);
  }

  @Override
  public void tearDown() throws SQLException {
    if (!con.getAutoCommit()) {
      con.rollback();
    }

    con.setAutoCommit(true);
    TestUtil.dropTable(con, "test_fetch");
    super.tearDown();
  }

  protected void createRows(int count) throws Exception {
    PreparedStatement stmt = con.prepareStatement("insert into test_fetch(value) values(?)");
    for (int i = 0; i < count; i++) {
      stmt.setInt(1, i);
      stmt.executeUpdate();
    }
  }

  // Test various fetchsizes.
  @Test
  public void testBasicFetch() throws Exception {
    createRows(100);

    PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
    int[] testSizes = {0, 1, 49, 50, 51, 99, 100, 101};
    for (int testSize : testSizes) {
      stmt.setFetchSize(testSize);
      assertEquals(testSize, stmt.getFetchSize());

      ResultSet rs = stmt.executeQuery();
      if (!con.unwrap(PGConnection.class).getAdaptiveFetch()) {
        assertEquals(testSize, rs.getFetchSize(), "ResultSet.fetchSize should not change after query execution");
      }

      int count = 0;
      while (rs.next()) {
        assertEquals(count, rs.getInt(1), () -> "query value error with fetch size " + testSize);
        ++count;
      }

      assertEquals(100, count, () -> "total query size error with fetch size " + testSize);
    }
  }

  // Similar, but for scrollable resultsets.
  @Test
  public void testScrollableFetch() throws Exception {
    createRows(100);

    PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value",
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);

    int[] testSizes = {0, 1, 49, 50, 51, 99, 100, 101};
    for (int testSize : testSizes) {
      stmt.setFetchSize(testSize);
      assertEquals(testSize, stmt.getFetchSize());

      ResultSet rs = stmt.executeQuery();
      if (!con.unwrap(PGConnection.class).getAdaptiveFetch()) {
        assertEquals(testSize, rs.getFetchSize(), "ResultSet.fetchSize should not change after query execution");
      }

      for (int j = 0; j <= 50; j++) {
        assertTrue(rs.next(), "ran out of rows at position " + j + " with fetch size " + testSize);
        assertEquals(j, rs.getInt(1), () -> "query value error with fetch size " + testSize);
      }

      int position = 50;
      for (int j = 1; j < 100; j++) {
        for (int k = 0; k < j; k++) {
          if (j % 2 == 0) {
            ++position;
            assertTrue(rs.next(), "ran out of rows doing a forward fetch on iteration " + j + "/" + k
                + " at position " + position + " with fetch size " + testSize);
          } else {
            --position;
            assertTrue(rs.previous(), "ran out of rows doing a reverse fetch on iteration " + j + "/" + k
                + " at position " + position + " with fetch size " + testSize);
          }

          assertEquals(position, rs.getInt(1), "query value error on iteration " + j + "/" + k + " with fetch size " + testSize);
        }
      }
    }
  }

  @Test
  public void testScrollableAbsoluteFetch() throws Exception {
    createRows(100);

    PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value",
        ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY);

    int[] testSizes = {0, 1, 49, 50, 51, 99, 100, 101};
    for (int testSize : testSizes) {
      stmt.setFetchSize(testSize);
      assertEquals(testSize, stmt.getFetchSize());

      ResultSet rs = stmt.executeQuery();
      if (!con.unwrap(PGConnection.class).getAdaptiveFetch()) {
        assertEquals(testSize, rs.getFetchSize(), "ResultSet.fetchSize should not change after query execution");
      }

      int position = 50;
      assertTrue(rs.absolute(position + 1), "ran out of rows doing an absolute fetch at " + position + " with fetch size "
          + testSize);
      assertEquals(position, rs.getInt(1), () -> "query value error with fetch size " + testSize);

      for (int j = 1; j < 100; j++) {
        if (j % 2 == 0) {
          position += j;
        } else {
          position -= j;
        }

        assertTrue(rs.absolute(position + 1), "ran out of rows doing an absolute fetch at " + position + " on iteration " + j
            + " with fetchsize" + testSize);
        assertEquals(position, rs.getInt(1), () -> "query value error with fetch size " + testSize);
      }
    }
  }

  //
  // Tests for ResultSet.setFetchSize().
  //

  // test one:
  // -set fetchsize = 0
  // -run query (all rows should be fetched)
  // -set fetchsize = 50 (should have no effect)
  // -process results
  @Test
  public void testResultSetFetchSizeOne() throws Exception {
    createRows(100);

    PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
    stmt.setFetchSize(0);
    ResultSet rs = stmt.executeQuery();
    rs.setFetchSize(50); // Should have no effect.

    int count = 0;
    while (rs.next()) {
      assertEquals(count, rs.getInt(1));
      ++count;
    }

    assertEquals(100, count);
  }

  // test two:
  // -set fetchsize = 25
  // -run query (25 rows fetched)
  // -set fetchsize = 0
  // -process results:
  // --process 25 rows
  // --should do a FETCH ALL to get more data
  // --process 75 rows
  @Test
  public void testResultSetFetchSizeTwo() throws Exception {
    createRows(100);

    PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
    stmt.setFetchSize(25);
    ResultSet rs = stmt.executeQuery();
    rs.setFetchSize(0);

    int count = 0;
    while (rs.next()) {
      assertEquals(count, rs.getInt(1));
      ++count;
    }

    assertEquals(100, count);
  }

  // test three:
  // -set fetchsize = 25
  // -run query (25 rows fetched)
  // -set fetchsize = 50
  // -process results:
  // --process 25 rows. should NOT hit end-of-results here.
  // --do a FETCH FORWARD 50
  // --process 50 rows
  // --do a FETCH FORWARD 50
  // --process 25 rows. end of results.
  @Test
  public void testResultSetFetchSizeThree() throws Exception {
    createRows(100);

    PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
    stmt.setFetchSize(25);
    ResultSet rs = stmt.executeQuery();
    rs.setFetchSize(50);

    int count = 0;
    while (rs.next()) {
      assertEquals(count, rs.getInt(1));
      ++count;
    }

    assertEquals(100, count);
  }

  // test four:
  // -set fetchsize = 50
  // -run query (50 rows fetched)
  // -set fetchsize = 25
  // -process results:
  // --process 50 rows.
  // --do a FETCH FORWARD 25
  // --process 25 rows
  // --do a FETCH FORWARD 25
  // --process 25 rows. end of results.
  @Test
  public void testResultSetFetchSizeFour() throws Exception {
    createRows(100);

    PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
    stmt.setFetchSize(50);
    ResultSet rs = stmt.executeQuery();
    rs.setFetchSize(25);

    int count = 0;
    while (rs.next()) {
      assertEquals(count, rs.getInt(1));
      ++count;
    }

    assertEquals(100, count);
  }

  @Test
  public void testSingleRowResultPositioning() throws Exception {
    String msg;
    createRows(1);

    int[] sizes = {0, 1, 10};
    for (int size : sizes) {
      Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
      stmt.setFetchSize(size);

      // Create a one row result set.
      ResultSet rs = stmt.executeQuery("select * from test_fetch order by value");

      msg = "before-first row positioning error with fetchsize=" + size;
      assertTrue(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      msg = "row 1 positioning error with fetchsize=" + size;
      assertTrue(rs.next(), msg);

      assertFalse(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertTrue(rs.isFirst(), msg);
      assertTrue(rs.isLast(), msg);
      assertEquals(0, rs.getInt(1), msg);

      msg = "after-last row positioning error with fetchsize=" + size;
      assertFalse(rs.next(), msg);

      assertFalse(rs.isBeforeFirst(), msg);
      assertTrue(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      rs.close();
      stmt.close();
    }
  }

  @Test
  public void testMultiRowResultPositioning() throws Exception {
    String msg;

    createRows(100);

    int[] sizes = {0, 1, 10, 100};
    for (int size : sizes) {
      Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
      stmt.setFetchSize(size);

      ResultSet rs = stmt.executeQuery("select * from test_fetch order by value");
      msg = "before-first row positioning error with fetchsize=" + size;
      assertTrue(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      for (int j = 0; j < 100; j++) {
        msg = "row " + j + " positioning error with fetchsize=" + size;
        assertTrue(rs.next(), msg);
        assertEquals(j, rs.getInt(1), msg);

        assertFalse(rs.isBeforeFirst(), msg);
        assertFalse(rs.isAfterLast(), msg);
        if (j == 0) {
          assertTrue(rs.isFirst(), msg);
        } else {
          assertFalse(rs.isFirst(), msg);
        }

        if (j == 99) {
          assertTrue(rs.isLast(), msg);
        } else {
          assertFalse(rs.isLast(), msg);
        }
      }

      msg = "after-last row positioning error with fetchsize=" + size;
      assertFalse(rs.next(), msg);

      assertFalse(rs.isBeforeFirst(), msg);
      assertTrue(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      rs.close();
      stmt.close();
    }
  }

  // Test odd queries that should not be transformed into cursor-based fetches.
  @Test
  public void testInsert() throws Exception {
    // INSERT should not be transformed.
    PreparedStatement stmt = con.prepareStatement("insert into test_fetch(value) values(1)");
    stmt.setFetchSize(100); // Should be meaningless.
    stmt.executeUpdate();
  }

  @Test
  public void testMultistatement() throws Exception {
    // Queries with multiple statements should not be transformed.

    createRows(100); // 0 .. 99
    PreparedStatement stmt = con.prepareStatement(
        "insert into test_fetch(value) values(100); select * from test_fetch order by value");
    stmt.setFetchSize(10);

    assertFalse(stmt.execute()); // INSERT
    assertTrue(stmt.getMoreResults()); // SELECT
    ResultSet rs = stmt.getResultSet();
    int count = 0;
    while (rs.next()) {
      assertEquals(count, rs.getInt(1));
      ++count;
    }

    assertEquals(101, count);
  }

  // if the driver tries to use a cursor with autocommit on
  // it will fail because the cursor will disappear partway
  // through execution
  @Test
  public void testNoCursorWithAutoCommit() throws Exception {
    createRows(10); // 0 .. 9
    con.setAutoCommit(true);
    Statement stmt = con.createStatement();
    stmt.setFetchSize(3);
    ResultSet rs = stmt.executeQuery("SELECT * FROM test_fetch ORDER BY value");
    int count = 0;
    while (rs.next()) {
      assertEquals(count++, rs.getInt(1));
    }

    assertEquals(10, count);
  }

  @Test
  public void testGetRow() throws SQLException {
    Statement stmt = con.createStatement();
    stmt.setFetchSize(1);
    ResultSet rs = stmt.executeQuery("SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3");
    int count = 0;
    while (rs.next()) {
      count++;
      assertEquals(count, rs.getInt(1));
      assertEquals(count, rs.getRow());
    }
    assertEquals(3, count);
  }

  // isLast() may change the results of other positioning methods as it has to
  // buffer some more results. This tests avoid using it so as to test robustness
  // other positioning methods
  @Test
  public void testRowResultPositioningWithoutIsLast() throws Exception {
    String msg;

    int rowCount = 4;
    createRows(rowCount);

    int[] sizes = {1, 2, 3, 4, 5};
    for (int size : sizes) {
      Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
      stmt.setFetchSize(size);

      ResultSet rs = stmt.executeQuery("select * from test_fetch order by value");
      msg = "before-first row positioning error with fetchsize=" + size;
      assertTrue(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);

      for (int j = 0; j < rowCount; j++) {
        msg = "row " + j + " positioning error with fetchsize=" + size;
        assertTrue(rs.next(), msg);
        assertEquals(j, rs.getInt(1), msg);

        assertFalse(rs.isBeforeFirst(), msg);
        assertFalse(rs.isAfterLast(), msg);
        if (j == 0) {
          assertTrue(rs.isFirst(), msg);
        } else {
          assertFalse(rs.isFirst(), msg);
        }
      }

      msg = "after-last row positioning error with fetchsize=" + size;
      assertFalse(rs.next(), msg);

      assertFalse(rs.isBeforeFirst(), msg);
      assertTrue(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      rs.close();
      stmt.close();
    }
  }

  // Empty resultsets require all row positioning methods to return false
  @Test
  public void testNoRowResultPositioning() throws Exception {
    int[] sizes = {0, 1, 50, 100};
    for (int size : sizes) {
      Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
      stmt.setFetchSize(size);

      ResultSet rs = stmt.executeQuery("select * from test_fetch order by value");
      Supplier<String> msg = () -> "no row (empty resultset) positioning error with fetchsize=" + size;
      assertFalse(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      assertFalse(rs.next(), msg);
      assertFalse(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      rs.close();
      stmt.close();
    }
  }

  // Empty resultsets require all row positioning methods to return false
  @Test
  public void testScrollableNoRowResultPositioning() throws Exception {
    int[] sizes = {0, 1, 50, 100};
    for (int size : sizes) {
      Statement stmt =
          con.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE);
      stmt.setFetchSize(size);

      ResultSet rs = stmt.executeQuery("select * from test_fetch order by value");
      Supplier<String> msg = () -> "no row (empty resultset) positioning error with fetchsize=" + size;
      assertFalse(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      assertFalse(rs.first(), msg);
      assertFalse(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      assertFalse(rs.next(), msg);
      assertFalse(rs.isBeforeFirst(), msg);
      assertFalse(rs.isAfterLast(), msg);
      assertFalse(rs.isFirst(), msg);
      assertFalse(rs.isLast(), msg);

      rs.close();
      stmt.close();
    }
  }
}
