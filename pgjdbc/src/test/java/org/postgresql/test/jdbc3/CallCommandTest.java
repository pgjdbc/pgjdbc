/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class CallCommandTest extends BaseTest4 {

  @Parameterized.Parameter(0)
  public PreferQueryMode preferQueryMode;

  @Parameterized.Parameters(name = "preferQueryMode={0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (PreferQueryMode mode : PreferQueryMode.values()) {
      ids.add(new Object[]{mode});
    }
    return ids;
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.PREFER_QUERY_MODE.set(props, preferQueryMode.value());
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeMinimumServerVersion(ServerVersion.from("11.0"));
    Statement st = con.createStatement();
    st.executeUpdate("CREATE OR REPLACE PROCEDURE my_proc(INOUT results text)\n"
        + "LANGUAGE 'plpgsql'\n"
        + "AS $BODY$\n"
        + "BEGIN\n"
        + "    select 'test' into results;\n"
        + "END;\n"
        + "$BODY$;");
  }

  @Override
  public void tearDown() throws SQLException {
    Statement st = con.createStatement();
    st.executeUpdate("DROP PROCEDURE my_proc(INOUT results text)");
    super.tearDown();
  }

  @Test
  public void simpleStatement() throws Throwable {
    Statement st = con.createStatement();
    ResultSet rs = st.executeQuery("CALL my_proc('whatever')");
    rs.next();
    Assert.assertEquals("test", rs.getString(1));
    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(st);
  }

  @Test
  public void preparedStatement() throws Throwable {
    PreparedStatement st = con.prepareStatement("CALL my_proc('whatever')");
    ResultSet rs = st.executeQuery();
    rs.next();
    Assert.assertEquals("test", rs.getString(1));
    TestUtil.closeQuietly(rs);
    TestUtil.closeQuietly(st);
  }
}
