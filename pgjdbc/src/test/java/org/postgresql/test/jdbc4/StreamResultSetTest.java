/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.junit.Test;
import org.postgresql.test.jdbc2.BaseTest4;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static java.lang.System.currentTimeMillis;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.postgresql.test.TestUtil.createTempTable;

public class StreamResultSetTest extends BaseTest4 {
  private ResultSet results;
  private PreparedStatement statement;

  private static final int COUNT = 1000;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    createTempTable(con, "series", "val int");
    try (Statement s = con.createStatement()) {
      s.execute("insert into series select * from generate_series(1," + COUNT + ")");
    }
    statement = con.prepareStatement("select *,pg_sleep(0.001) from series");
  }

  @Test
  public void testStreamedResults() throws Exception {
    readResults();
    // query second time to make sure the state is correct after streaming
    readResults();
  }

  @Test
  public void closeCleansUpResourcesOnClose() throws Exception {
    results = statement.executeQuery();

    // read few first items
    results.next();
    results.next();

    // close the result set before reading all rows
    results.close();

    // query second time to make sure the connection state is still correct after partial streaming
    readResults();
  }

  private void readResults() throws SQLException {
    results = statement.executeQuery();
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
    results.close();
    assertThat(count, is(COUNT));
    assertThat(timeBetweenFirstAndLastRow, greaterThan(1_000L));
    assertThat(timeToFirstRow, lessThan(1_000L));
  }
}
