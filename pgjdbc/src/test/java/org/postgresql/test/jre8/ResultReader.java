/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jre8;

import static java.lang.Math.max;
import static java.lang.System.currentTimeMillis;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.core.Is.is;

import org.hamcrest.core.Is;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

class ResultReader {
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
    assertThat("count", count, Is.is(StreamResultSetTest.GENERATED_ROWS));
    // verify that our query actually caused a delay between rows on the server side
    assertThat("serverSideDelay", serverLastRowTime - serverFirstRowTime,
        greaterThan(StreamResultSetTest.EXPECTED_DURATION / 2));
  }
}
