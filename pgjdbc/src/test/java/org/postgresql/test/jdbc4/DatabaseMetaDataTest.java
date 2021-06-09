/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.SlowTests;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Assume;
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

    try (ResultSet rs = dbmd.getFunctions("", "hasfunctions","")) {
      List<CatalogObject> list = assertFunctionRSAndReturnList(rs);
      assertEquals("There should be one function in the hasfunctions schema", list.size(), 1);
      assertListContains("getFunctions('', 'hasfunctions', '') must contain addfunction", list, "hasfunctions", "addfunction");
    }

    try (ResultSet rs = dbmd.getFunctions("", "hasfunctions", "addfunction")) {
      List<CatalogObject> list = assertFunctionRSAndReturnList(rs);
      assertEquals("There should be one function in the hasfunctions schema with name addfunction", list.size(), 1);
      assertListContains("getFunctions('', 'hasfunctions', 'addfunction') must contain addfunction", list, "hasfunctions", "addfunction");
    }

    try (ResultSet rs = dbmd.getFunctions("", "nofunctions","")) {
      boolean hasFunctions = rs.next();
      assertFalse("There should be no functions in the nofunctions schema", hasFunctions);
    }
  }

  @Test
  public void testGetFunctionsInSchemaForProcedures() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getFunctions should not return procedures for PostgreSQL versions 11+
    // On older installation we do not create the procedures so the below schemas should all be empty
    DatabaseMetaData dbmd = conn.getMetaData();

    // Search for functions in schema "hasprocedures"
    try (ResultSet rs = dbmd.getFunctions("", "hasprocedures", null)) {
      assertFalse("The hasprocedures schema not return procedures from getFunctions", rs.next());
    }
    // Search for functions in schema "noprocedures" (which should never expect records)
    try (ResultSet rs = dbmd.getFunctions("", "noprocedures", null)) {
      assertFalse("The noprocedures schema should not have functions", rs.next());
    }
    // Search for functions by procedure name "addprocedure"
    try (ResultSet rs = dbmd.getFunctions("", "hasprocedures", "addprocedure")) {
      assertFalse("Should not return procedures from getFunctions by schema + name", rs.next());
    }
  }

  @Test
  public void testGetProceduresInSchemaForFunctions() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getProcedures should not return functions for PostgreSQL versions 11+
    DatabaseMetaData dbmd = conn.getMetaData();

    // Search for procedures in schema "hasfunctions" (which should expect a record only for PostgreSQL < 11)
    try (ResultSet rs = dbmd.getProcedures("", "hasfunctions",null)) {
      if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
        assertFalse("PostgreSQL11+ should not return functions from getProcedures", rs.next());
      } else {
        // PostgreSQL prior to 11 should return functions from getProcedures
        assertProcedureRS(rs);
      }
    }

    // Search for procedures in schema "nofunctions" (which should never expect records)
    try (ResultSet rs = dbmd.getProcedures("", "nofunctions", null)) {
      assertFalse("getProcedures(...) should not return procedures for schema nofunctions", rs.next());
    }

    // Search for procedures by function name "addfunction" within schema "hasfunctions" (which should expect a record for PostgreSQL < 11)
    try (ResultSet rs = dbmd.getProcedures("", "hasfunctions", "addfunction")) {
      if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
        assertFalse("PostgreSQL11+ should not return functions from getProcedures", rs.next());
      } else {
        // PostgreSQL prior to 11 should return functions from getProcedures
        assertProcedureRS(rs);
      }
    }

    // Search for procedures by function name "addfunction" within schema "nofunctions"  (which should never expect records)
    try (ResultSet rs = dbmd.getProcedures("", "nofunctions", "addfunction")) {
      assertFalse("getProcedures(...) should not return procedures for schema nofunctions + addfunction", rs.next());
    }
  }

  @Test
  public void testGetProceduresInSchemaForProcedures() throws SQLException {
    // Only run this test for PostgreSQL version 11+; assertions for versions prior would be vacuously true as we don't create a procedure in the setup for older versions
    Assume.assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11));

    DatabaseMetaData dbmd = conn.getMetaData();

    try (ResultSet rs = dbmd.getProcedures("", "hasprocedures", null)) {
      int count = assertProcedureRS(rs);
      assertTrue("getProcedures() should be non-empty for the hasprocedures schema", count == 1);
    }

    try (ResultSet rs = dbmd.getProcedures("", "noprocedures", null)) {
      assertFalse("getProcedures() should be empty for the hasprocedures schema", rs.next());
    }

    try (ResultSet rs = dbmd.getProcedures("", "hasfunctions", null)) {
      assertFalse("getProcedures() should be empty for the nofunctions schema", rs.next());
    }

    try (ResultSet rs = dbmd.getProcedures("", "nofunctions", null)) {
      assertFalse("getProcedures() should be empty for the nofunctions schema", rs.next());
    }
  }

  @Test
  @Category(SlowTests.class)
  public void testGetFunctionsWithBlankPatterns() throws SQLException {
    int minFuncCount = 1000;
    DatabaseMetaData dbmd = conn.getMetaData();

    final int totalCount;
    try (ResultSet rs = dbmd.getFunctions("", "", "")) {
      List<CatalogObject> list = assertFunctionRSAndReturnList(rs);
      totalCount = list.size(); // Rest of this test will validate against this value
      assertThat(totalCount > minFuncCount, is(true));
      assertListContains("getFunctions('', '', '') must contain addfunction", list, "hasfunctions", "addfunction");
    }

    // Should be same as blank pattern
    try (ResultSet rs = dbmd.getFunctions(null, null, null)) {
      int count = assertGetFunctionRS(rs);
      assertThat(count, is(totalCount));
    }

    // Catalog parameter has no affect on our getFunctions filtering
    try (ResultSet rs = dbmd.getFunctions("ANYTHING_WILL_WORK", null, null)) {
      int count = assertGetFunctionRS(rs);
      assertThat(count, is(totalCount));
    }

    // Filter by schema
    try (ResultSet rs = dbmd.getFunctions("", "pg_catalog", null)) {
      int count = assertGetFunctionRS(rs);
      assertThat(count > minFuncCount, is(true));
    }

    // Filter by schema and function name
    try (ResultSet rs = dbmd.getFunctions("", "pg_catalog", "abs")) {
      int count = assertGetFunctionRS(rs);
      assertThat(count >= 1, is(true));
    }

    // Filter by function name only
    try (ResultSet rs = dbmd.getFunctions("", "", "abs")) {
      int count = assertGetFunctionRS(rs);
      assertThat(count >= 1, is(true));
    }
  }

  private static class CatalogObject implements Comparable<CatalogObject> {
    private final String catalog;
    private final String schema;
    private final String name;
    private final String specificName;

    private CatalogObject(String catalog, String schema, String name, String specificName) {
      this.catalog = catalog;
      this.schema = schema;
      this.name = name;
      this.specificName = specificName;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + ((catalog == null) ? 0 : catalog.hashCode());
      result = prime * result + ((name == null) ? 0 : name.hashCode());
      result = prime * result + ((schema == null) ? 0 : schema.hashCode());
      result = prime * result + ((specificName == null) ? 0 : specificName.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      } else if (obj == this) {
        return true;
      }
      return compareTo((CatalogObject)obj) == 0;
    }

    @Override
    public int compareTo(CatalogObject other) {
      int comp = catalog.compareTo(other.catalog);
      if (comp != 0) {
        return comp;
      }
      comp = schema.compareTo(other.schema);
      if (comp != 0) {
        return comp;
      }
      comp = name.compareTo(other.name);
      if (comp != 0) {
        return comp;
      }
      comp = specificName.compareTo(other.specificName);
      if (comp != 0) {
        return comp;
      }
      return 0;
    }
  }

  /** Assert some basic result from ResultSet of a GetFunctions method. Return the total row count. */
  private int assertGetFunctionRS(ResultSet rs) throws SQLException {
    return assertFunctionRSAndReturnList(rs).size();
  }

  private List<CatalogObject> assertFunctionRSAndReturnList(ResultSet rs) throws SQLException {
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
    List<CatalogObject> result = new ArrayList<>();
    do {
      CatalogObject obj = new CatalogObject(
          rs.getString("FUNCTION_CAT"),
          rs.getString("FUNCTION_SCHEM"),
          rs.getString("FUNCTION_NAME"),
          rs.getString("SPECIFIC_NAME"));
      result.add(obj);
    } while (rs.next());

    List<CatalogObject> orderedResult = new ArrayList<>(result);
    Collections.sort(orderedResult);
    assertThat(result, is(orderedResult));

    return result;
  }

  private int assertProcedureRS(ResultSet rs) throws SQLException {
    return assertProcedureRSAndReturnList(rs).size();
  }

  private List<CatalogObject> assertProcedureRSAndReturnList(ResultSet rs) throws SQLException {
    // There should be at least one row
    assertThat(rs.next(), is(true));
    assertThat(rs.getString("PROCEDURE_CAT"), nullValue());
    assertThat(rs.getString("PROCEDURE_SCHEM"), notNullValue());
    assertThat(rs.getString("PROCEDURE_NAME"), notNullValue());
    assertThat(rs.getShort("PROCEDURE_TYPE") >= 0, is(true));
    assertThat(rs.getString("SPECIFIC_NAME"), notNullValue());

    // Ensure there is enough column and column value retrieve by index should be same as column name (ordered)
    assertThat(rs.getMetaData().getColumnCount(), is(9));
    assertThat(rs.getString(1), is(rs.getString("PROCEDURE_CAT")));
    assertThat(rs.getString(2), is(rs.getString("PROCEDURE_SCHEM")));
    assertThat(rs.getString(3), is(rs.getString("PROCEDURE_NAME")));
    // Per JDBC spec, indexes 4, 5, and 6 are reserved for future use
    assertThat(rs.getString(7), is(rs.getString("REMARKS")));
    assertThat(rs.getShort(8), is(rs.getShort("PROCEDURE_TYPE")));
    assertThat(rs.getString(9), is(rs.getString("SPECIFIC_NAME")));

    // Get all result and assert they are ordered per javadoc spec:
    //   FUNCTION_CAT, FUNCTION_SCHEM, FUNCTION_NAME and SPECIFIC_NAME
    List<CatalogObject> result = new ArrayList<>();
    do {
      CatalogObject obj = new CatalogObject(
          rs.getString("PROCEDURE_CAT"),
          rs.getString("PROCEDURE_SCHEM"),
          rs.getString("PROCEDURE_NAME"),
          rs.getString("SPECIFIC_NAME"));
      result.add(obj);
    } while (rs.next());

    List<CatalogObject> orderedResult = new ArrayList<>(result);
    Collections.sort(orderedResult);
    assertThat(result, is(orderedResult));

    return result;
  }

  private void assertListContains(String message, List<CatalogObject> list, String schema, String name) throws SQLException {
    boolean found = list.stream().anyMatch(item -> item.schema.equals(schema) && item.name.equals(name));
    assertTrue(message + "; schema=" + schema + " name=" + name, found);
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
