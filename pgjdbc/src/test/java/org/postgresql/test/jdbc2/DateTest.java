/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/*
 * Some simple tests based on problems reported by users. Hopefully these will help prevent previous
 * problems from re-occurring ;-)
 *
 */
public class DateTest {
  private Connection con;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createTable(con, "testdate", "dt date");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(con, "testdate");
    TestUtil.closeDB(con);
  }

  /*
   * Tests the time methods in ResultSet
   */
  @Test
  public void testGetDate() throws SQLException {
    Statement stmt = con.createStatement();

    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1950-02-07'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1970-06-02'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1999-08-11'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2001-02-13'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1950-04-02'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1970-11-30'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1988-01-01'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2003-07-09'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1934-02-28'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1969-04-03'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1982-08-03'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2012-03-15'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1912-05-01'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1971-12-15'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'1984-12-03'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'2000-01-01'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'3456-01-01'")));
    assertEquals(1, stmt.executeUpdate(TestUtil.insertSQL("testdate", "'0101-01-01 BC'")));

    /* dateTest() contains all of the tests */
    dateTest();

    assertEquals(18, stmt.executeUpdate("DELETE FROM " + "testdate"));
    stmt.close();
  }

  /*
   * Tests the time methods in PreparedStatement
   */
  @Test
  public void testSetDate() throws SQLException {
    Statement stmt = con.createStatement();
    PreparedStatement ps = con.prepareStatement(TestUtil.insertSQL("testdate", "?"));

    ps.setDate(1, makeDate(1950, 2, 7));
    assertEquals(1, ps.executeUpdate());

    ps.setDate(1, makeDate(1970, 6, 2));
    assertEquals(1, ps.executeUpdate());

    ps.setDate(1, makeDate(1999, 8, 11));
    assertEquals(1, ps.executeUpdate());

    ps.setDate(1, makeDate(2001, 2, 13));
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Timestamp.valueOf("1950-04-02 12:00:00"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Timestamp.valueOf("1970-11-30 3:00:00"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Timestamp.valueOf("1988-01-01 13:00:00"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Timestamp.valueOf("2003-07-09 12:00:00"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, "1934-02-28", java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, "1969-04-03", java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, "1982-08-03", java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, "2012-03-15", java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Date.valueOf("1912-05-01"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Date.valueOf("1971-12-15"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Date.valueOf("1984-12-03"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Date.valueOf("2000-01-01"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    ps.setObject(1, java.sql.Date.valueOf("3456-01-01"), java.sql.Types.DATE);
    assertEquals(1, ps.executeUpdate());

    // We can't use valueOf on BC dates.
    ps.setObject(1, makeDate(-100, 1, 1));
    assertEquals(1, ps.executeUpdate());

    ps.close();

    dateTest();

    assertEquals(18, stmt.executeUpdate("DELETE FROM testdate"));
    stmt.close();
  }

  /*
   * Try to demonstrate that timestamps (which underly the date and time types as returned by the
   * JDBC API) are thread-safe over a shared connection.
   *
   * From Issue No. 921:
   *
   *    Starting with version 9.4-1208, and still present with version 42.1.4, we're finding that
   *    calls to org.postgresql.jdbc.PgResultSet#getDate(int,java.util.Calendar) in separate threads
   *    sharing the same connection may return corrupt data under certain conditions.
   *
   * This test attempts to replicate one such condition. On my local laptop, this happens by the
   * fifth iteration of the main loop (number of iterations being configurable via the ITERATIONS
   * constant), so just capping it at twice that: ten.
   */
  @Test
  public void testDateThreadSafety() throws Exception {

    final int ITERATIONS = 10;
    final int YEAR = 2017;

    class A implements Callable<Integer> {
      @Override
      public Integer call() throws Exception {
        PreparedStatement statement = null;
        try {
          statement = con.prepareStatement(
              "SELECT unnest(array_fill('12/31/7777'::DATE, ARRAY[5000]))");
          ResultSet resultSet = null;
          try {
            resultSet = statement.executeQuery();
            while (resultSet.next()) {
              resultSet.getDate(1); // corrupt B's call to getDate()
            }
          } finally {
            if (resultSet != null) {
              resultSet.close();
            }
          }
        } finally {
          if (statement != null) {
            statement.close();
          }
        }
        return YEAR;
      }
    }

    class B implements Callable<Integer> {
      @Override
      public Integer call() throws Exception {
        Statement statement = null;
        try {
          statement = con.createStatement();
          ResultSet resultSet = null;
          try {
            resultSet = statement.executeQuery(
                String.format("SELECT unnest(array_fill('8/10/%d'::date, ARRAY[5000]))", YEAR));
            while (resultSet.next()) {
              Date d = resultSet.getDate(1);
              int year = 1900 + d.getYear();
              if (year != YEAR) {
                return year;
              }
            }
          } finally {
            if (resultSet != null) {
              resultSet.close();
            }
          }
        } finally {
          if (statement != null) {
            statement.close();
          }
        }
        return YEAR;
      }
    }

    List<Callable<Integer>> callables = Arrays.asList(new A(), new B());
    for (int i = 0; i < ITERATIONS; ++i) {
      ExecutorService e = Executors.newFixedThreadPool(callables.size());
      for (Future<Integer> future : e.invokeAll(callables)) {
        int year = future.get();
        Assert.assertEquals(YEAR, year);
      }
      e.shutdown();
      e.awaitTermination(3, TimeUnit.MINUTES);
    }

  }

  /*
   * Helper for the date tests. It tests what should be in the db
   */
  private void dateTest() throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs;
    java.sql.Date d;

    rs = st.executeQuery(TestUtil.selectSQL("testdate", "dt"));
    assertNotNull(rs);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1950, 2, 7), d);


    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1970, 6, 2), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1999, 8, 11), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2001, 2, 13), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1950, 4, 2), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1970, 11, 30), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1988, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2003, 7, 9), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1934, 2, 28), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1969, 4, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1982, 8, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2012, 3, 15), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1912, 5, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1971, 12, 15), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(1984, 12, 3), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(2000, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(3456, 1, 1), d);

    assertTrue(rs.next());
    d = rs.getDate(1);
    assertNotNull(d);
    assertEquals(makeDate(-100, 1, 1), d);

    assertTrue(!rs.next());

    rs.close();
    st.close();
  }

  private java.sql.Date makeDate(int y, int m, int d) {
    return new java.sql.Date(y - 1900, m - 1, d);
  }
}
