/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static java.lang.Math.max;
import static java.lang.System.currentTimeMillis;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.junit.Assume.assumeTrue;
import static org.postgresql.core.ServerVersion.v10;
import static org.postgresql.test.TestUtil.createTempTable;
import static org.postgresql.test.TestUtil.haveMinimumServerVersion;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

public class StreamResultSetTest extends BaseTest4 {
  // backend seems to use 8kB buffer for results.
  // Use enough rows and and padding to force results into multiple packets
  private static final int GENERATED_ROWS = 5;
  private static final double SLEEP = 1.0;
  private static final long EXPECTED_DURATION = (long) (GENERATED_ROWS * SLEEP * 1000);
  private static final String SELECT_SLEEP_BETWEEN_ROWS;

  static {
    char[] filler = new char[8192];
    Arrays.fill(filler,'x');
    String fillerText = new String(filler);
    SELECT_SLEEP_BETWEEN_ROWS = "select *,pg_sleep(" + SLEEP + "),clock_timestamp(),'" + fillerText + "' from series";
  }

  private PreparedStatement statement;

  @Override
  public void setUp() throws Exception {
    super.setUp();

    BaseConnection pg = con.unwrap(BaseConnection.class);
    assumeTrue(pg.streamResults());
  }

  private static PreparedStatement prepareTable(Connection con) throws SQLException {
    createTempTable(con, "series", "val int");
    try (Statement s = con.createStatement()) {
      s.execute("insert into series select * from generate_series(1," + GENERATED_ROWS + ")");
    }
    // statement that sleeps one milliscond between each rows
    return con.prepareStatement(SELECT_SLEEP_BETWEEN_ROWS);
  }

  @Test
  public void testStreamedResults() throws Exception {
    statement = prepareTable(con);

    // the test query seems to work differently on older servers by not pacing the response rows
    // the streaming features works nicely though
    assumeTrue(haveMinimumServerVersion(con, v10));

    ResultReader r = new ResultReader();
    r.run(statement);

    // verify that the there was a delay between the client seeing the first and last row
    assertThat("timeBetweenFirstAndLastRow", r.timeBetweenFirstAndLastRow, greaterThan(EXPECTED_DURATION / 2));
    // verify that the first row still appeared in reasonable time
    assertThat("timeToFirstRow", r.timeToFirstRow, lessThan(EXPECTED_DURATION / 2));

    assertThat("maxDeltaBetweenServerAndLocal", r.maxDeltaBetweenServerAndLocal, lessThan((long) (SLEEP * 1000 * 2)));
  }

  @Test
  public void streamingDisabledBuffersAllRows() throws Exception {
    Properties props = new Properties();
    updateProperties(props);
    PGProperty.STREAM_RESULTS.set(props, false);
    Connection noStreamConn = TestUtil.openDB(props);

    ResultReader r = new ResultReader();
    r.run(prepareTable(noStreamConn));

    // verify that the rows could be processed without much delays (allow some random gc time)
    assertThat("timeBetweenFirstAndLastRow", r.timeBetweenFirstAndLastRow, lessThan(300L));
    // verify that the first row took at least the expected time
    assertThat("timeToFirstRow", r.timeToFirstRow, greaterThan(EXPECTED_DURATION));

    assertThat("maxDeltaBetweenServerAndLocal", r.maxDeltaBetweenServerAndLocal, greaterThan(EXPECTED_DURATION / 2));
  }

  @Test
  public void statementReExecuteCleansUpResources() throws Exception {
    statement = con.prepareStatement("select * from generate_series(1,10)");
    ResultSet results = statement.executeQuery();

    // read few first items to get stream initialized
    results.next();
    assertThat(results.getInt(1), is(1));
    results.next();
    assertThat(results.getInt(1), is(2));

    ResultSet results2 = statement.executeQuery();
    assertThat(results.isClosed(), is(true));

    results2.next();
    assertThat(results2.getInt(1), is(1));
    results2.next();
    assertThat(results2.getInt(1), is(2));
  }

  @Test
  public void differentStatementExecuteCleansUpResources() throws Exception {
    statement = con.prepareStatement("select 1,* from generate_series(1,10)");
    try (PreparedStatement statement2 = con.prepareStatement("select 2,* from generate_series(1,10)")) {
      ResultSet results = statement.executeQuery();

      // read few first items to get stream initialized
      results.next();
      assertThat(results.getInt(1), is(1));
      assertThat(results.getInt(2), is(1));
      results.next();
      assertThat(results.getInt(1), is(1));
      assertThat(results.getInt(2), is(2));

      ResultSet results2 = statement2.executeQuery();
      assertThat(results.isClosed(), is(false));

      results2.next();
      assertThat(results2.getInt(1), is(2));
      assertThat(results2.getInt(2), is(1));
      results2.next();
      assertThat(results2.getInt(1), is(2));
      assertThat(results2.getInt(2), is(2));
    }
  }

  static class ResultReader {
    long startTime;
    long firstRowTime;
    long timeBetweenFirstAndLastRow;
    long timeToFirstRow;
    long maxDeltaBetweenServerAndLocal;

    public void run(PreparedStatement statement) throws SQLException {
      int count = 0;
      long serverFirstRowTime = 0;
      long serverLastRowTime = 0;
      startTime = currentTimeMillis();
      try (ResultSet results = statement.executeQuery()) {
        while (results.next()) {
          int value = results.getInt(1);
          long serverTime = results.getTimestamp(3).getTime();
          long delta = currentTimeMillis() - serverTime;
          maxDeltaBetweenServerAndLocal = max(delta, maxDeltaBetweenServerAndLocal);
          if (firstRowTime == 0) {
            firstRowTime = currentTimeMillis();
            serverFirstRowTime = serverTime;
          }
          serverLastRowTime = serverTime;
          assertThat(value, is(++count));
        }
        timeBetweenFirstAndLastRow = currentTimeMillis() - firstRowTime;
        timeToFirstRow = firstRowTime - startTime;
      }
      assertThat("count", count, is(GENERATED_ROWS));
      // verify that our query actually caused a delay between rows on the server side
      assertThat("serverSideDelay", serverLastRowTime - serverFirstRowTime, greaterThan(EXPECTED_DURATION / 2));
    }
  }
}
