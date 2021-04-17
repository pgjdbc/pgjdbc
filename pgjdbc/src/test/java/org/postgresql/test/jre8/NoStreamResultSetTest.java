/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jre8;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assume.assumeFalse;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class NoStreamResultSetTest extends BaseTest4 {
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

  public NoStreamResultSetTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.STREAM_RESULTS.set(props, false);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();

    BaseConnection pg = con.unwrap(BaseConnection.class);
    assumeFalse("streamResults should be disabled", pg.streamResults());
  }

  private PreparedStatement prepareStatementWithSlowResults() throws SQLException {
    // statement that sleeps one millisecond between each rows
    return con.prepareStatement(SELECT_SLEEP_BETWEEN_ROWS);
  }

  @Test
  public void streamingDisabledBuffersAllRows() throws Exception {
    ResultReader r = new ResultReader();
    r.run(prepareStatementWithSlowResults());

    // verify that the rows could be processed without much delays (allow some random gc time)
    assertThat("timeBetweenFirstAndLastRow", r.timeBetweenFirstAndLastRow, lessThan(300L));
    // verify that the first row took at least the expected time
    assertThat("timeToFirstRow", r.timeToFirstRow, greaterThan(EXPECTED_DURATION));

    assertThat("maxDeltaBetweenServerAndLocal", r.maxDeltaBetweenServerAndLocal,
        greaterThan(EXPECTED_DURATION / 2));
  }
}
