/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static java.lang.System.currentTimeMillis;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.postgresql.test.TestUtil.createTempTable;

import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public class StreamResultSetTest extends BaseTest4 {
  private static final int GENERATED_ROWS = 1000;
  private static final String SELECT_SLEEP_BETWEEN_ROWS = "select *,pg_sleep(0.001) from series";

  private PreparedStatement statement;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    createTempTable(con, "series", "val int");
    try (Statement s = con.createStatement()) {
      s.execute("insert into series select * from generate_series(1," + GENERATED_ROWS + ")");
    }
    // statement that sleeps one milliscond between each rows
    statement = con.prepareStatement(SELECT_SLEEP_BETWEEN_ROWS);
  }

  @Test
  public void testStreamedResults() throws Exception {
    readResults();
    // query second time to make sure the state is correct after streaming
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
    readResults();

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
      readResults();

      assertThat(results.isClosed(), is(false));

      results.next();
      results.next();
    }
  }

  private void readResults() throws SQLException {
    try (ResultSet results = statement.executeQuery()) {
      int count = 0;
      long startTime = currentTimeMillis();
      long fistRowTime = 0;
      while (results.next()) {
        int value = results.getInt(1);
        if (fistRowTime == 0) {
          fistRowTime = currentTimeMillis();
        }
        assertThat(value, is(++count));
      }
      long timeBetweenFirstAndLastRow = currentTimeMillis() - fistRowTime;
      long timeToFirstRow = fistRowTime - startTime;

      assertThat(count, is(GENERATED_ROWS));
      assertThat(timeBetweenFirstAndLastRow, greaterThan(1_000L));
      assertThat(timeToFirstRow, lessThan(1_000L));
    }
  }
}
