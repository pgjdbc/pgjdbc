/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.util.Arrays;

@ParameterizedClass(name = "input={0}, expected={1}")
@MethodSource("data")
public class ReplaceProcessingTest extends BaseTest4 {

  @Parameter(0)
  public String input;
  @Parameter(1)
  public String expected;

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
        // {"{fn timestampadd(SQL_TSI_QUARTER, ?, {fn now()})}", "(CAST( $1||' quarter' as interval)+ now())"},
        // {"{fn timestampadd(SQL_TSI_FRAC_SECOND, ?, {fn now()})}", "(CAST( $1||' second' as interval)+ now())"},
    });
  }

  @Test
  public void run() throws SQLException {
    assertEquals(expected, con.nativeSQL(input), input);
  }
}
