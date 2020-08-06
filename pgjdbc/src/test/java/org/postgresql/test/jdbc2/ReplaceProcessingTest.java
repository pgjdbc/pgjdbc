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
        {"{fn timestampadd(SQL_TSI_YEAR, ?, {fn now()})}", "(CAST( $1||' year' as interval)+ now())"},
        {"{fn timestampadd(SQL_TSI_MONTH, ?, {fn now()})}", "(CAST( $1||' month' as interval)+ now())"},
        {"{fn timestampadd(SQL_TSI_DAY, ?, {fn now()})}", "(CAST( $1||' day' as interval)+ now())"},
        {"{fn timestampadd(SQL_TSI_WEEK, ?, {fn now()})}", "(CAST( $1||' week' as interval)+ now())"},
        {"{fn timestampadd(SQL_TSI_MINUTE, ?, {fn now()})}", "(CAST( $1||' minute' as interval)+ now())"},
        {"{fn timestampadd(SQL_TSI_SECOND, ?, {fn now()})}", "(CAST( $1||' second' as interval)+ now())"},
        {"{fn user()}", "user"},
        {"{fn ifnull(?,?)}", "coalesce($1,$2)"},
        {"{fn database()}", "current_database()"},
        // Not yet supported
        // {"{fn timestampadd(SQL_TSI_QUATER, ?, {fn now()})}", "(CAST( $1||' quater' as interval)+ now())"},
        // {"{fn timestampadd(SQL_TSI_FRAC_SECOND, ?, {fn now()})}", "(CAST( $1||' second' as interval)+ now())"},
    });
  }

  @Test
  public void run() throws SQLException {
    Assert.assertEquals(input, expected, con.nativeSQL(input));
  }
}
