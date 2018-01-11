/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatabaseMetaDataTest {

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.dropSequence(_conn, "sercoltest_a_seq");
    TestUtil.createTable(_conn, "sercoltest", "a serial, b int");
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropSequence(_conn, "sercoltest_a_seq");
    TestUtil.dropTable(_conn, "sercoltest");
    TestUtil.closeDB(_conn);
  }

  @Test
  public void testGetClientInfoProperties() throws Exception {
    DatabaseMetaData dbmd = _conn.getMetaData();

    ResultSet rs = dbmd.getClientInfoProperties();
    if (!TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v9_0)) {
      assertTrue(!rs.next());
      return;
    }

    assertTrue(rs.next());
    assertEquals("ApplicationName", rs.getString("NAME"));
  }

  @Test
  public void testGetColumnsForAutoIncrement() throws Exception {
    DatabaseMetaData dbmd = _conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "sercoltest", "%");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));

    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));

    assertTrue(!rs.next());
  }

  @Test
  public void testGetSchemas() throws SQLException {
    DatabaseMetaData dbmd = _conn.getMetaData();

    ResultSet rs = dbmd.getSchemas("", "publ%");

    assertTrue(rs.next());
    assertEquals("public", rs.getString("TABLE_SCHEM"));
    assertNull(rs.getString("TABLE_CATALOG"));
    assertTrue(!rs.next());
  }

  @Test
  public void testGetFunctionsWithBlankPatterns() throws SQLException {
    int minFuncCount = 1000;
    DatabaseMetaData dbmd = _conn.getMetaData();
    ResultSet rs = dbmd.getFunctions("", "", "");
    int count = assertGetFunctionRS(rs);
    assertThat(count > minFuncCount, is(true));

    // Should be same as blank pattern
    ResultSet rs2 = dbmd.getFunctions(null, null, null);
    int count2 = assertGetFunctionRS(rs2);
    assertThat(count2 > minFuncCount, is(true));
    assertThat(count2, is(count));

    // Catalog parameter has no affect on our getFunctions filtering
    ResultSet rs3 = dbmd.getFunctions("ANYTHING_WILL_WORK", null, null);
    int count3 = assertGetFunctionRS(rs3);
    assertThat(count3 > minFuncCount, is(true));
    assertThat(count3, is(count));

    // Filter by schema
    ResultSet rs4 = dbmd.getFunctions("", "pg_catalog", null);
    int count4 = assertGetFunctionRS(rs4);
    assertThat(count4 > minFuncCount, is(true));

    // Filter by schema and function name
    ResultSet rs5 = dbmd.getFunctions("", "pg_catalog", "abs");
    int count5 = assertGetFunctionRS(rs5);
    assertThat(count5 >= 1, is(true));

    // Filter by function name only
    rs5 = dbmd.getFunctions("", "", "abs");
    count5 = assertGetFunctionRS(rs5);
    assertThat(count5 >= 1, is(true));

    rs.close();
    rs2.close();
    rs3.close();
    rs4.close();
    rs5.close();
  }

  /** Assert some basic result from ResultSet of a GetFunctions method. Return the total row count. */
  private int assertGetFunctionRS(ResultSet rs) throws SQLException {
    // There should be at least one row
    assertThat(rs.next(), is(true));
    assertThat(rs.getString("FUNCTION_CAT"), is(System.getProperty("database")));
    assertThat(rs.getString("FUNCTION_SCHEM"), notNullValue());
    assertThat(rs.getString("FUNCTION_NAME"), notNullValue());
    assertThat(rs.getShort("FUNCTION_TYPE") >= 0, is(true));
    assertThat(rs.getString("SPECIFIC_NAME"), notNullValue());

    // Ensure there is enough column and column value retrieve by index should be same as column name (ordered)
    assertThat(rs.getMetaData().getColumnCount(), is(6));
    assertThat(rs.getString(1), is(rs.getString("FUNCTION_CAT")));
    assertThat(rs.getString(2), is(rs.getString("FUNCTION_SCHEM")));
    assertThat(rs.getString(3), is(rs.getString("FUNCTION_NAME")));
    assertThat(rs.getString(4), is(rs.getString("REMARKS")));
    assertThat(rs.getShort(5), is(rs.getShort("FUNCTION_TYPE")));
    assertThat(rs.getString(6), is(rs.getString("SPECIFIC_NAME")));

    // Get all result and assert they are ordered per javadoc spec:
    //   FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME and SPECIFIC_NAME
    List<String> result = new ArrayList<String>();
    do {
      result.add(rs.getString("FUNCTION_CAT")
              + " "
              + rs.getString("FUNCTION_SCHEM")
              + " "
              + rs.getString("FUNCTION_NAME")
              + " "
              + rs.getString("SPECIFIC_NAME"));
    } while (rs.next());

    List<String> orderedResult = new ArrayList<String>(result);
    Collections.sort(orderedResult);
    assertThat(result, is(orderedResult));

    return result.size();
  }

  @Test
  public void testGetFunctionsWithSpecificTypes() throws SQLException {
    // These function creation are borrow from jdbc2/DatabaseMetaDataTest
    // We modify to ensure new function created are returned by getFunctions()

    DatabaseMetaData dbmd = _conn.getMetaData();
    if (TestUtil.haveMinimumServerVersion(_conn, ServerVersion.v8_4)) {
      Statement stmt = _conn.createStatement();
      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
      ResultSet rs = dbmd.getFunctions("", "", "getfunc_f1");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f1"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short)DatabaseMetaData.functionNoTable));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f1(int, varchar)");

      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
      rs = dbmd.getFunctions("", "", "getfunc_f3");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f3"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short)DatabaseMetaData.functionNoTable));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f3(int, varchar)");

      // RETURNS TABLE requires PostgreSQL 8.4+
      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f5() RETURNS TABLE (i int) LANGUAGE sql AS 'SELECT 1'");

      rs = dbmd.getFunctions("", "", "getfunc_f5");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f5"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short)DatabaseMetaData.functionReturnsTable));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f5()");
    } else {
      // For PG 8.3 or 8.2 it will resulted in unknown function type
      Statement stmt = _conn.createStatement();
      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
      ResultSet rs = dbmd.getFunctions("", "", "getfunc_f1");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f1"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short)DatabaseMetaData.functionResultUnknown));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f1(int, varchar)");

      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
      rs = dbmd.getFunctions("", "", "getfunc_f3");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f3"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short)DatabaseMetaData.functionResultUnknown));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f3(int, varchar)");
    }
  }
}
