/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.util.Arrays;

@RunWith(Parameterized.class)
public class ReplaceProcessingTest extends BaseTest4 {

  @Parameterized.Parameter(0)
  public String input;
  @Parameterized.Parameter(1)
  public String expected;

  @Parameterized.Parameters(name = "input={0}, expected={1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"SELECT {fn timestampadd(SQL_TSI_YEAR, ?, {fn now()})}", "SELECT (CAST( $1||' year' as interval)+ now())"},
        {"SELECT {fn timestampadd(SQL_TSI_MONTH, ?, {fn now()})}", "SELECT (CAST( $1||' month' as interval)+ now())"},
        {"SELECT {fn timestampadd(SQL_TSI_DAY, ?, {fn now()})}", "SELECT (CAST( $1||' day' as interval)+ now())"},
        {"SELECT {fn timestampadd(SQL_TSI_WEEK, ?, {fn now()})}", "SELECT (CAST( $1||' week' as interval)+ now())"},
        {"SELECT {fn timestampadd(SQL_TSI_MINUTE, ?, {fn now()})}", "SELECT (CAST( $1||' minute' as interval)+ now())"},
        {"SELECT {fn timestampadd(SQL_TSI_SECOND, ?, {fn now()})}", "SELECT (CAST( $1||' second' as interval)+ now())"},
        {"SELECT {fn user()}", "SELECT user"},
        {"SELECT {fn ifnull(?,?)}", "SELECT coalesce($1,$2)"},
        {"SELECT {fn database()}", "SELECT current_database()"},
        // Not yet supported
        // {"{fn timestampadd(SQL_TSI_QUARTER, ?, {fn now()})}", "(CAST( $1||' quarter' as interval)+ now())"},
        // {"{fn timestampadd(SQL_TSI_FRAC_SECOND, ?, {fn now()})}", "(CAST( $1||' second' as interval)+ now())"},
    });
  }

  @Test
  public void run() throws SQLException {
    Assert.assertEquals(input, expected, con.nativeSQL(input));
  }
}
