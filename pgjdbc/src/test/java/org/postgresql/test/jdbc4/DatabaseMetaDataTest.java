/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.SlowTests;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DatabaseMetaDataTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.dropSequence(conn, "sercoltest_a_seq");
    TestUtil.createTable(conn, "sercoltest", "a serial, b int");
    TestUtil.createSchema(conn, "hasfunctions");
    TestUtil.createSchema(conn, "nofunctions");
    TestUtil.createSchema(conn, "hasprocedures");
    TestUtil.createSchema(conn, "noprocedures");
    TestUtil.execute("create function hasfunctions.addfunction (integer, integer) "
        + "RETURNS integer AS 'select $1 + $2;' LANGUAGE SQL IMMUTABLE", conn);
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
      TestUtil.execute("create procedure hasprocedures.addprocedure() "
          + "LANGUAGE plpgsql AS $$ BEGIN SELECT 1; END; $$", conn);
    }
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropSequence(conn, "sercoltest_a_seq");
    TestUtil.dropTable(conn, "sercoltest");
    TestUtil.dropSchema(conn, "hasfunctions");
    TestUtil.dropSchema(conn, "nofunctions");
    TestUtil.dropSchema(conn, "hasprocedures");
    TestUtil.dropSchema(conn, "noprocedures");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testGetClientInfoProperties() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getClientInfoProperties();
    if (!TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_0)) {
      assertTrue(!rs.next());
      return;
    }

    assertTrue(rs.next());
    assertEquals("ApplicationName", rs.getString("NAME"));
  }

  @Test
  public void testGetColumnsForAutoIncrement() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

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
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getSchemas("", "publ%");

    assertTrue(rs.next());
    assertEquals("public", rs.getString("TABLE_SCHEM"));
    assertNull(rs.getString("TABLE_CATALOG"));
    assertTrue(!rs.next());
  }

  @Test
  public void testGetFunctionsInSchemaForFunctions() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();
    ResultSet rs = dbmd.getFunctions("", "hasfunctions","");
    int count = assertGetFunctionRS(rs);
    assertThat( count, is(1));

    Statement statement = conn.createStatement();
    statement.execute("set search_path=hasfunctions");

    rs = dbmd.getFunctions("", "","addfunction");
    assertThat( assertGetFunctionRS(rs), is(1) );

    statement.execute("set search_path=nofunctions");

    rs = dbmd.getFunctions("", "","addfunction");
    assertFalse(rs.next());

    statement.execute("reset search_path");
    statement.close();

    rs = dbmd.getFunctions("", "nofunctions",null);
    assertFalse(rs.next());

  }

  @Test
  public void testGetFunctionsInSchemaForProcedures() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getFunctions should not return procedures for PostgreSQL versions 11+
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {

      DatabaseMetaData dbmd = conn.getMetaData();
      Statement statement = conn.createStatement();

      // Search for functions in schema "hasprocedures"
      ResultSet rs = dbmd.getFunctions("", "hasprocedures", null);
      Boolean recordFound = rs.next();
      assertEquals("PostgreSQL11+ should not return procedures from getFunctions", recordFound, false);

      // Search for functions in schema "noprocedures" (which should never expect records)
      rs = dbmd.getFunctions("", "noprocedures", null);
      recordFound = rs.next();
      assertFalse(recordFound);

      // Search for functions by procedure name "addprocedure" within schema "hasprocedures"
      statement.execute("set search_path=hasprocedures");
      rs = dbmd.getFunctions("", "", "addprocedure");
      recordFound = rs.next();
      assertEquals("PostgreSQL11+ should not return procedures from getFunctions", recordFound, false);

      // Search for functions by procedure name "addprocedure" within schema "noprocedures"  (which should never expect records)
      statement.execute("set search_path=noprocedures");
      rs = dbmd.getProcedures("", "", "addprocedure");
      recordFound = rs.next();
      assertFalse(recordFound);

      statement.close();
    }
  }

  @Test
  public void testGetProceduresInSchemaForFunctions() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getProcedures should not return functions for PostgreSQL versions 11+

    DatabaseMetaData dbmd = conn.getMetaData();
    Statement statement = conn.createStatement();

    // Search for procedures in schema "hasfunctions" (which should expect a record only for PostgreSQL < 11)
    ResultSet rs = dbmd.getProcedures("", "hasfunctions",null);
    Boolean recordFound = rs.next();
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
      assertEquals("PostgreSQL11+ should not return functions from getProcedures", recordFound, false);
    } else {
      assertEquals("PostgreSQL prior to 11 should return functions from getProcedures", recordFound, true);
    }

    // Search for procedures in schema "nofunctions" (which should never expect records)
    rs = dbmd.getProcedures("", "nofunctions",null);
    recordFound = rs.next();
    assertFalse(recordFound);

    // Search for procedures by function name "addfunction" within schema "hasfunctions" (which should expect a record for PostgreSQL < 11)
    statement.execute("set search_path=hasfunctions");
    rs = dbmd.getProcedures("", "","addfunction");
    recordFound = rs.next();
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
      assertEquals("PostgreSQL11+ should not return functions from getProcedures", recordFound, false);
    } else {
      assertEquals("PostgreSQL prior to 11 should return functions from getProcedures", recordFound, true);
    }

    // Search for procedures by function name "addfunction" within schema "nofunctions"  (which should never expect records)
    statement.execute("set search_path=nofunctions");
    rs = dbmd.getProcedures("", "","addfunction");
    recordFound = rs.next();
    assertFalse(recordFound);

    statement.close();
  }

  @Test
  public void testGetProceduresInSchemaForProcedures() throws SQLException {
    // Only run this test for PostgreSQL version 11+; assertions for versions prior would be vacuously true as we don't create a procedure in the setup for older versions
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
      DatabaseMetaData dbmd = conn.getMetaData();
      Statement statement = conn.createStatement();

      ResultSet rs = dbmd.getProcedures("", "hasprocedures", null);
      assertTrue(rs.next());

      rs = dbmd.getProcedures("", "nofunctions", null);
      assertFalse(rs.next());

      statement.execute("set search_path=hasprocedures");
      rs = dbmd.getProcedures("", "", "addprocedure");
      assertTrue(rs.next());

      statement.execute("set search_path=noprocedures");
      rs = dbmd.getProcedures("", "", "addprocedure");
      assertFalse(rs.next());

      statement.close();
    }
  }

  @Test
  @Category(SlowTests.class)
  public void testGetFunctionsWithBlankPatterns() throws SQLException {
    int minFuncCount = 1000;
    DatabaseMetaData dbmd = conn.getMetaData();
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

    DatabaseMetaData dbmd = conn.getMetaData();
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_4)) {
      Statement stmt = conn.createStatement();
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
      Statement stmt = conn.createStatement();
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

  @Test
  public void testSortedDataTypes() throws SQLException {
    // https://github.com/pgjdbc/pgjdbc/issues/716
    DatabaseMetaData dbmd = conn.getMetaData();
    ResultSet rs = dbmd.getTypeInfo();
    int lastType = Integer.MIN_VALUE;
    while (rs.next()) {
      int type = rs.getInt("DATA_TYPE");
      assertTrue(lastType <= type);
      lastType = type;
    }
  }
}
