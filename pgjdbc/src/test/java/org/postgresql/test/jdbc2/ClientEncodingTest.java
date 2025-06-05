/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;

@ParameterizedClass
@MethodSource("data")
public class ClientEncodingTest extends BaseTest4 {

  @Parameter
  public boolean allowEncodingChanges;

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.ALLOW_ENCODING_CHANGES.set(props, allowEncodingChanges);
  }

  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {true},
        {false}
    });
  }

  @Test
  public void setEncodingUtf8() throws SQLException {
    // UTF-8 is a default encoding, so it should always be safe to set encoding to UTF-8
    setEncoding("UTF-8");

    checkConnectionSanity();
  }

  @Test
  public void setEncodingAscii() throws SQLException {
    try {
      setEncoding("sql_ascii");
      if (!allowEncodingChanges) {
        fail("allowEncodingChanges is false, thus set client_encoding=aql_ascii is expected to fail");
      }
    } catch (SQLException e) {
      if (!allowEncodingChanges && !PSQLState.CONNECTION_FAILURE.getState()
          .equals(e.getSQLState())) {
        throw e;
      }
      assertTrue(con.isClosed(), "Connection should be closed on client_encoding change");
      return;
    }

    checkConnectionSanity();
  }

  private void checkConnectionSanity() throws SQLException {
    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("select 'abc' as x");
    rs.next();
    assertEquals("abc", rs.getString(1));
    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(st);
  }

  private void setEncoding(String encoding) throws SQLException {
    Statement st = con.createStatement();
    st.execute("set client_encoding='" + encoding + "'");
    TestUtil.closeQuietly(st);
  }
}
