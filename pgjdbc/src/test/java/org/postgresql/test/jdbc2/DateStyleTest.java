/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

@RunWith(Parameterized.class)
public class DateStyleTest extends BaseTest4 {

  @Parameterized.Parameter(0)
  public String dateStyle;

  @Parameterized.Parameter(1)
  public boolean shouldPass;

  @Parameterized.Parameters(name = "dateStyle={0}, shouldPass={1}")
  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"iso, mdy", true},
        {"ISO", true},
        {"ISO,ymd", true},
        {"PostgreSQL", false}
    });
  }

  @Test
  public void conenct() throws SQLException {
    Statement st = con.createStatement();
    try {
      st.execute("set DateStyle='" + dateStyle + "'");
      if (!shouldPass) {
        Assert.fail("Set DateStyle=" + dateStyle + " should not be allowed");
      }
    } catch (SQLException e) {
      if (shouldPass) {
        throw new IllegalStateException("Set DateStyle=" + dateStyle
            + " should be fine, however received " + e.getMessage(), e);
      }
      if (PSQLState.CONNECTION_FAILURE.getState().equals(e.getSQLState())) {
        return;
      }
      throw new IllegalStateException("Set DateStyle=" + dateStyle
          + " should result in CONNECTION_FAILURE error, however received " + e.getMessage(), e);
    } finally {
      TestUtil.closeQuietly(st);
    }
  }
}
