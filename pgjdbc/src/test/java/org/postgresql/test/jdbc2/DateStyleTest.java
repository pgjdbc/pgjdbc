/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

@ParameterizedClass(name = "dateStyle={0}, shouldPass={1}")
@MethodSource("data")
public class DateStyleTest extends BaseTest4 {

  @Parameter(0)
  public String dateStyle;

  @Parameter(1)
  public boolean shouldPass;

  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {"iso, mdy", true},
        {"ISO", true},
        {"ISO,ymd", true},
        {"PostgreSQL", false}
    });
  }

  @Test
  public void connect() throws SQLException {
    Statement st = con.createStatement();
    try {
      st.execute("set DateStyle='" + dateStyle + "'");
      if (!shouldPass) {
        fail("Set DateStyle=" + dateStyle + " should not be allowed");
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
