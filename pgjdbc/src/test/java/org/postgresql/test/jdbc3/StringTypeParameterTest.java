/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PSQLState;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Properties;

@RunWith(Parameterized.class)
public class StringTypeParameterTest extends BaseTest4 {
  private static final String UNSPECIFIED_STRING_TYPE = "unspecified";

  private final String stringType;

  public StringTypeParameterTest(String stringType) {
    this.stringType = stringType;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    // Assume enum supported
    Assume.assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_3));
    TestUtil.createEnumType(con, "mood", "'happy', 'sad'");
    TestUtil.createTable(con, "stringtypetest", "m mood");
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    if (stringType != null) {
      props.put("stringtype", stringType);
    }
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "stringtypetest");
    TestUtil.dropType(con, "mood");
    super.tearDown();
  }

  @Parameterized.Parameters(name = "stringType = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<Object[]>();
    for (String stringType : new String[]{null, "varchar", UNSPECIFIED_STRING_TYPE}) {
      ids.add(new Object[]{stringType});
    }
    return ids;
  }

  @Test
  public void testVarcharAsEnum() throws Exception {
    Assume.assumeFalse(UNSPECIFIED_STRING_TYPE.equals(stringType));
    Assume.assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);

    PreparedStatement update = con.prepareStatement("insert into stringtypetest (m) values (?)");
    for (int i = 0; i < 2; i++) {
      update.clearParameters();
      if (i == 0) {
        update.setString(1, "sad");
      } else {
        update.setObject(1, "sad", Types.VARCHAR);
      }
      try {
        update.executeUpdate();
        fail("Expected 'column \"m\" is of type mood but expression is of type character varying', "
            + (i == 0 ? "setString(1, \"sad\")" : "setObject(1, \"sad\", Types.VARCHAR)"));
      } catch (SQLException e) {
        // Exception exception is
        // ERROR: column "m" is of type mood but expression is of type character varying
        if (!PSQLState.DATATYPE_MISMATCH.getState().equals(e.getSQLState())) {
          throw e;
        }
      }
    }
    TestUtil.closeQuietly(update);
  }

  @Test
  public void testOtherAsEnum() throws Exception {
    PreparedStatement update = con.prepareStatement("insert into stringtypetest (m) values (?)");
    update.setObject(1, "happy", Types.OTHER);
    update.executeUpdate();
    // all good
    TestUtil.closeQuietly(update);
  }

  @Test
  public void testMultipleEnumBinds() throws Exception {
    Assume.assumeFalse(UNSPECIFIED_STRING_TYPE.equals(stringType));
    Assume.assumeTrue(preferQueryMode != PreferQueryMode.SIMPLE);

    PreparedStatement query =
        con.prepareStatement("select * from stringtypetest where m = ? or m = ?");
    query.setString(1, "sad");
    query.setObject(2, "sad", Types.VARCHAR);
    try {
      query.executeQuery();
      fail("Expected 'operator does not exist: mood = character varying'");
    } catch (SQLException e) {
      // Exception exception is
      // ERROR: operator does not exist: mood = character varying
      if (!PSQLState.UNDEFINED_FUNCTION.getState().equals(e.getSQLState())) {
        throw e;
      }
    }
    TestUtil.closeQuietly(query);
  }

  @Test
  public void testParameterUnspecified() throws Exception {
    Assume.assumeTrue(UNSPECIFIED_STRING_TYPE.equals(stringType));

    PreparedStatement update = con.prepareStatement("insert into stringtypetest (m) values (?)");
    update.setString(1, "happy");
    update.executeUpdate();
    // all good

    update.clearParameters();
    update.setObject(1, "happy", Types.VARCHAR);
    update.executeUpdate();
    // all good
    update.close();

    PreparedStatement query = con.prepareStatement("select * from stringtypetest where m = ?");
    query.setString(1, "happy");
    ResultSet rs = query.executeQuery();
    assertTrue(rs.next());
    assertEquals("happy", rs.getObject("m"));
    rs.close();

    query.clearParameters();
    query.setObject(1, "happy", Types.VARCHAR);
    rs = query.executeQuery();
    assertTrue(rs.next());
    assertEquals("happy", rs.getObject("m"));

    // all good
    rs.close();
    query.close();
  }
}
