/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static java.lang.Math.max;
import static java.lang.System.nanoTime;
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
import org.postgresql.util.Constants;

import org.junit.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class StreamResultSetTest extends BaseTest4 {
  // backend seems to use 8kB buffer for results.
  // Use enough rows and and padding to force results into multiple packets
  private static final int GENERATED_ROWS = 500;
  private static final double SLEEP = 0.002;
  private static final long MIN_EXPECTED_DURATION = (long) (GENERATED_ROWS * SLEEP * 1000);
  private static final String SELECT_SLEEP_BETWEEN_ROWS = "select *,pg_sleep(" + SLEEP + "),clock_timestamp(),'filler-text-123456789-abcdefghijklmnopqrstuvxyz' from series";

  private PreparedStatement statement;

  @Override
  public void setUp() throws Exception {
    super.setUp();

    BaseConnection pg = con.unwrap(BaseConnection.class);
    assumeTrue(pg.streamResults());

    statement = prepareTable(con);
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
    readResults();
  }

  @Test
  public void statementReExecuteCleansUpResources() throws Exception {
    ResultSet results = statement.executeQuery();

    // read few first items
    results.next();
    results.next();

    // re-execute the same statement -> should close result set
    // query second time to make sure the connection state is still correct after partial streaming
    readResults(true);

    assertThat(results.isClosed(), is(true));
  }

  @Test
  public void differentStatementExecuteCleansUpResources() throws Exception {
    try (PreparedStatement statement2 = con.prepareStatement(SELECT_SLEEP_BETWEEN_ROWS)) {
      ResultSet results = statement2.executeQuery();

      // read few first items
      results.next();
      results.next();

      // re-execute the different statement -> must not close the previous result set
      // query second time to make sure the connection state is still correct after partial streaming
      readResults(true);

      assertThat(results.isClosed(), is(false));

      results.next();
      results.next();
    }
  }

  private void readResults() throws SQLException {
    readResults(false);
  }

  private void readResults(boolean waitForPrevious) throws SQLException {
    // the test query seems to work differently on older servers by not pacing the response rows
    // the streaming features works nicely though
    assumeTrue(haveMinimumServerVersion(con, v10));

    ResultReader r = new ResultReader();
    r.run(statement);

    // verify that the there was a delay between the client seeing the first and last row
    assertThat(r.timeBetweenFirstAndLastRow, greaterThan(MIN_EXPECTED_DURATION / 2));
    // verify that the first row still appeared in reasonable time
    assertThat(r.timeToFirstRow, lessThan((waitForPrevious ? MIN_EXPECTED_DURATION : 0 ) + MIN_EXPECTED_DURATION / 2));

    assertThat(r.maxDeltaBetweenServerAndLocal, lessThan(300L));
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
      startTime = nanoTime()/Constants.NANOS_PER_MILLISECOND;
      try (ResultSet results = statement.executeQuery()) {
        while (results.next()) {
          int value = results.getInt(1);
          long serverTime = results.getTimestamp(3).getTime();
          long delta = nanoTime()/Constants.NANOS_PER_MILLISECOND - serverTime;
          maxDeltaBetweenServerAndLocal = max(delta, maxDeltaBetweenServerAndLocal);
          if (firstRowTime == 0) {
            firstRowTime = nanoTime()/Constants.NANOS_PER_MILLISECOND;
            serverFirstRowTime = serverTime;
          }
          serverLastRowTime = serverTime;
          assertThat(value, is(++count));
        }
        timeBetweenFirstAndLastRow = nanoTime()/Constants.NANOS_PER_MILLISECOND - firstRowTime;
        timeToFirstRow = firstRowTime - startTime;
      }
      assertThat(count, is(GENERATED_ROWS));
      // verify that our query actually caused a delay between rows on the server side
      assertThat(serverLastRowTime - serverFirstRowTime, greaterThan(MIN_EXPECTED_DURATION));
    }
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
    assertThat(r.timeBetweenFirstAndLastRow, lessThan(200L));
    // verify that the first row took at least the expected time
    assertThat(r.timeToFirstRow, greaterThan(MIN_EXPECTED_DURATION));

    assertThat(r.maxDeltaBetweenServerAndLocal, greaterThan(MIN_EXPECTED_DURATION));
  }
}
