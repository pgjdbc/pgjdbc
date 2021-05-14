/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jre8;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.core.Is.is;
import static org.junit.Assume.assumeTrue;
import static org.postgresql.core.ServerVersion.v10;
import static org.postgresql.test.TestUtil.haveMinimumServerVersion;

import org.postgresql.core.BaseConnection;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;

@RunWith(Parameterized.class)
public class StreamResultSetTest extends BaseTest4 {
  // backend seems to use 8kB buffer for results.
  // Use enough rows and and padding to force results into multiple packets
  public static final int GENERATED_ROWS = 5;
  private static final double SLEEP = 1.0;
  public static final long EXPECTED_DURATION = (long) (GENERATED_ROWS * SLEEP * 1000);
  private static final String SELECT_SLEEP_BETWEEN_ROWS;

  static {
    char[] filler = new char[8192];
    Arrays.fill(filler, 'x');
    String fillerText = new String(filler);
    SELECT_SLEEP_BETWEEN_ROWS =
        "select *,pg_sleep(" + SLEEP + "),clock_timestamp(),'" + fillerText + "' from "
            + "generate_series(1, " + GENERATED_ROWS + ")";
  }

  @Parameterized.Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  public StreamResultSetTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();

    BaseConnection pg = con.unwrap(BaseConnection.class);
    assumeTrue("streamResults supported", pg.streamResults());
  }

  private PreparedStatement prepareStatementWithSlowResults() throws SQLException {
    // statement that sleeps one millisecond between each rows
    return con.prepareStatement(SELECT_SLEEP_BETWEEN_ROWS);
  }

  @Test
  public void testStreamedResults() throws Exception {
    PreparedStatement statement = prepareStatementWithSlowResults();

    // the test query seems to work differently on older servers by not pacing the response rows
    // the streaming features works nicely though
    assumeTrue(haveMinimumServerVersion(con, v10));

    ResultReader r = new ResultReader();
    r.run(statement);

    // verify that the there was a delay between the client seeing the first and last row
    assertThat("timeBetweenFirstAndLastRow", r.timeBetweenFirstAndLastRow,
        greaterThan(EXPECTED_DURATION / 2));
    // verify that the first row still appeared in reasonable time
    assertThat("timeToFirstRow", r.timeToFirstRow, lessThan(EXPECTED_DURATION / 2));

    assertThat("maxDeltaBetweenServerAndLocal", r.maxDeltaBetweenServerAndLocal,
        lessThan((long) (SLEEP * 1000 * 2)));
  }

  @Test
  public void statementReExecuteCleansUpResources() throws Exception {
    PreparedStatement statement = con.prepareStatement("select * from generate_series(1,10)");
    ResultSet rsOld = statement.executeQuery();

    // read few first items to get stream initialized
    rsOld.next();
    assertThat(rsOld.getInt(1), is(1));
    rsOld.next();
    assertThat(rsOld.getInt(1), is(2));

    ResultSet rsNew = statement.executeQuery();
    assertThat(rsOld.isClosed(), is(true));

    rsNew.next();
    assertThat(rsNew.getInt(1), is(1));
    rsNew.next();
    assertThat(rsNew.getInt(1), is(2));
  }

  @Test
  public void differentStatementExecuteCleansUpResources() throws Exception {
    PreparedStatement statement = con.prepareStatement("select 1,* from generate_series(1,10)");
    try (PreparedStatement statement2 = con.prepareStatement(
        "select 2,* from generate_series(1, 10)")) {
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

      for (int i = 3; i <= 10; i++) {
        results.next();
        assertThat(results.getInt(1), is(1));
        assertThat(results.getInt(2), is(i));
        results2.next();
        assertThat(results2.getInt(1), is(2));
        assertThat(results2.getInt(2), is(i));
      }
    }
  }
}
