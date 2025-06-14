/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

class DatabaseMetaDataTest {

  private Connection conn;

  @BeforeEach
  void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.dropSequence(conn, "sercoltest_a_seq");
    TestUtil.createTable(conn, "sercoltest", "a serial, b int");
    TestUtil.createSchema(conn, "hasfunctions");
    TestUtil.createSchema(conn, "nofunctions");
    TestUtil.createSchema(conn, "hasprocedures");
    TestUtil.createSchema(conn, "noprocedures");
    TestUtil.execute(conn, "create function hasfunctions.addfunction (integer, integer) "
        + "RETURNS integer AS 'select $1 + $2;' LANGUAGE SQL IMMUTABLE");
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
      TestUtil.execute(conn, "create procedure hasprocedures.addprocedure() "
          + "LANGUAGE plpgsql AS $$ BEGIN SELECT 1; END; $$");
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.dropSequence(conn, "sercoltest_a_seq");
    TestUtil.dropTable(conn, "sercoltest");
    TestUtil.dropSchema(conn, "hasfunctions");
    TestUtil.dropSchema(conn, "nofunctions");
    TestUtil.dropSchema(conn, "hasprocedures");
    TestUtil.dropSchema(conn, "noprocedures");
    TestUtil.closeDB(conn);
  }

  @Test
  void getClientInfoProperties() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getClientInfoProperties();
    if (!TestUtil.haveMinimumServerVersion(conn, ServerVersion.v9_0)) {
      assertFalse(rs.next());
      return;
    }

    assertTrue(rs.next());
    assertEquals("ApplicationName", rs.getString("NAME"));
  }

  @Test
  void getColumnsForAutoIncrement_whenCatalogArgPercentSign_expectNoResults() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns("%", "%", "sercoltest", "%");
    assertFalse(rs.next());
  }

  @Test
  void getColumnsForAutoIncrement() throws Exception {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getColumns(null, "%", "sercoltest", "%");
    assertTrue(rs.next());
    assertEquals("a", rs.getString("COLUMN_NAME"));
    assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));

    assertTrue(rs.next());
    assertEquals("b", rs.getString("COLUMN_NAME"));
    assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));

    assertFalse(rs.next());
  }

  @Test
  void getSchemas_whenCatalogArgPercentSign_expectNoResults() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getSchemas("%", "publ%");

    assertFalse(rs.next());
  }

  @Test
  void getSchemas() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getSchemas(null, "publ%");

    assertTrue(rs.next());
    assertEquals("public", rs.getString("TABLE_SCHEM"));
    assertNotNull(rs.getString("TABLE_CATALOG"));
    assertFalse(rs.next());
  }

  @Test
  void getSchemas_whenNullCatalogAndSchemaPattern_expectRows() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getSchemas(null, null);

    assertTrue(rs.next());
  }

  @Test
  void getSchemas_whenEmptySchemaPattern_expectNoRows() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getSchemas(null, "");

    assertFalse(rs.next());
  }

  @Test
  void getSchemas_whenEmptyCatalog_expectNoRows() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getSchemas("", null);

    assertFalse(rs.next());
  }

  @Test
  void getSchemas_whenRandomCatalog_expectNoRows() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    ResultSet rs = dbmd.getSchemas(UUID.randomUUID().toString(), null);

    assertFalse(rs.next());
  }

  @Test
  void getFunctionsInSchemaForFunctions_whenCatalogArgEmpty_expectNoResults() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    try (ResultSet rs = dbmd.getFunctions("", "hasfunctions", null)) {
      assertFalse(rs.next());
    }

    try (ResultSet rs = dbmd.getFunctions("", "hasfunctions", "addfunction")) {
      assertFalse(rs.next());
    }

    try (ResultSet rs = dbmd.getFunctions("", "nofunctions", null)) {
      boolean hasFunctions = rs.next();
      assertFalse(hasFunctions, "There should be no functions in the nofunctions schema");
    }
  }

  @Test
  void getFunctionsInSchemaForFunctions() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    try (ResultSet rs = dbmd.getFunctions(null, "hasfunctions", null)) {
      List<CatalogObject> list = assertFunctionRSAndReturnList(rs);
      assertEquals(1, list.size(), "There should be one function in the hasfunctions schema");
      assertListContains("getFunctions('', 'hasfunctions', '') must contain addfunction", list, "hasfunctions", "addfunction");
    }

    try (ResultSet rs = dbmd.getFunctions(null, "hasfunctions", "addfunction")) {
      List<CatalogObject> list = assertFunctionRSAndReturnList(rs);
      assertEquals(1, list.size(), "There should be one function in the hasfunctions schema with name addfunction");
      assertListContains("getFunctions('', 'hasfunctions', 'addfunction') must contain addfunction", list, "hasfunctions", "addfunction");
    }

    try (ResultSet rs = dbmd.getFunctions(null, "nofunctions", null)) {
      boolean hasFunctions = rs.next();
      assertFalse(hasFunctions, "There should be no functions in the nofunctions schema");
    }
  }

  @Test
  void getFunctionsInSchemaForProcedures_whenCatalogArgEmpty_expectNoResults() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getFunctions should not return procedures for PostgreSQL versions 11+
    // On older installation we do not create the procedures so the below schemas should all be empty
    DatabaseMetaData dbmd = conn.getMetaData();

    // Search for functions in schema "hasprocedures"
    try (ResultSet rs = dbmd.getFunctions("", "hasprocedures", null)) {
      assertFalse(rs.next(), "The hasprocedures schema not return procedures from getFunctions");
    }
    // Search for functions in schema "noprocedures" (which should never expect records)
    try (ResultSet rs = dbmd.getFunctions("", "noprocedures", null)) {
      assertFalse(rs.next(), "The noprocedures schema should not have functions");
    }
    // Search for functions by procedure name "addprocedure"
    try (ResultSet rs = dbmd.getFunctions("", "hasprocedures", "addprocedure")) {
      assertFalse(rs.next(), "Should not return procedures from getFunctions by schema + name");
    }
  }

  @Test
  void getFunctionsInSchemaForProcedures() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getFunctions should not return procedures for PostgreSQL versions 11+
    // On older installation we do not create the procedures so the below schemas should all be empty
    DatabaseMetaData dbmd = conn.getMetaData();

    // Search for functions in schema "hasprocedures"
    try (ResultSet rs = dbmd.getFunctions(null, "hasprocedures", null)) {
      assertFalse(rs.next(), "The hasprocedures schema not return procedures from getFunctions");
    }
    // Search for functions in schema "noprocedures" (which should never expect records)
    try (ResultSet rs = dbmd.getFunctions(null, "noprocedures", null)) {
      assertFalse(rs.next(), "The noprocedures schema should not have functions");
    }
    // Search for functions by procedure name "addprocedure"
    try (ResultSet rs = dbmd.getFunctions(null, "hasprocedures", "addprocedure")) {
      assertFalse(rs.next(), "Should not return procedures from getFunctions by schema + name");
    }
  }

  @Test
  void getProceduresInSchemaForFunctions_whenCatalogArgEmpty_expectNoResults() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getProcedures should not return functions for PostgreSQL versions 11+
    DatabaseMetaData dbmd = conn.getMetaData();

    // Search for procedures in schema "hasfunctions" (which should expect a record only for PostgreSQL < 11)
    try (ResultSet rs = dbmd.getProcedures("", "hasfunctions", null)) {
      assertFalse(rs.next());
    }

    // Search for procedures in schema "nofunctions" (which should never expect records)
    try (ResultSet rs = dbmd.getProcedures("", "nofunctions", null)) {
      assertFalse(rs.next(), "getProcedures(...) should not return procedures for schema nofunctions");
    }

    // Search for procedures by function name "addfunction" within schema "hasfunctions" (which should expect a record for PostgreSQL < 11)
    try (ResultSet rs = dbmd.getProcedures("", "hasfunctions", "addfunction")) {
      assertFalse(rs.next());
    }

    // Search for procedures by function name "addfunction" within schema "nofunctions"  (which should never expect records)
    try (ResultSet rs = dbmd.getProcedures("", "nofunctions", "addfunction")) {
      assertFalse(rs.next(), "getProcedures(...) should not return procedures for schema nofunctions + addfunction");
    }
  }

  @Test
  void getProceduresInSchemaForFunctions() throws SQLException {
    // Due to the introduction of actual stored procedures in PostgreSQL 11, getProcedures should not return functions for PostgreSQL versions 11+
    DatabaseMetaData dbmd = conn.getMetaData();

    // Search for procedures in schema "hasfunctions" (which should expect a record only for PostgreSQL < 11)
    try (ResultSet rs = dbmd.getProcedures(null, "hasfunctions", null)) {
      if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
        assertFalse(rs.next(), "PostgreSQL11+ should not return functions from getProcedures");
      } else {
        // PostgreSQL prior to 11 should return functions from getProcedures
        assertProcedureRS(rs);
      }
    }

    // Search for procedures in schema "nofunctions" (which should never expect records)
    try (ResultSet rs = dbmd.getProcedures(null, "nofunctions", null)) {
      assertFalse(rs.next(), "getProcedures(...) should not return procedures for schema nofunctions");
    }

    // Search for procedures by function name "addfunction" within schema "hasfunctions" (which should expect a record for PostgreSQL < 11)
    try (ResultSet rs = dbmd.getProcedures(null, "hasfunctions", "addfunction")) {
      if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11)) {
        assertFalse(rs.next(), "PostgreSQL11+ should not return functions from getProcedures");
      } else {
        // PostgreSQL prior to 11 should return functions from getProcedures
        assertProcedureRS(rs);
      }
    }

    // Search for procedures by function name "addfunction" within schema "nofunctions"  (which should never expect records)
    try (ResultSet rs = dbmd.getProcedures(null, "nofunctions", "addfunction")) {
      assertFalse(rs.next(), "getProcedures(...) should not return procedures for schema nofunctions + addfunction");
    }
  }

  @Test
  void getProceduresInSchemaForProcedures_whenCatalogArgEmpty_expectNoResults() throws SQLException {
    // Only run this test for PostgreSQL version 11+; assertions for versions prior would be vacuously true as we don't create a procedure in the setup for older versions
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11));

    DatabaseMetaData dbmd = conn.getMetaData();

    try (ResultSet rs = dbmd.getProcedures("", "hasprocedures", null)) {
      assertFalse(rs.next());
    }

    try (ResultSet rs = dbmd.getProcedures("", "noprocedures", null)) {
      assertFalse(rs.next(), "getProcedures() should be empty for the noprocedures schema");
    }

    try (ResultSet rs = dbmd.getProcedures("", "hasfunctions", null)) {
      assertFalse(rs.next(), "getProcedures() should be empty for the hasfunctions schema");
    }

    try (ResultSet rs = dbmd.getProcedures("", "nofunctions", null)) {
      assertFalse(rs.next(), "getProcedures() should be empty for the nofunctions schema");
    }
  }

  @Test
  void getProceduresWithCorrectCatalogAndWithout() throws SQLException {
    // Only run this test for PostgreSQL version 11+; assertions for versions prior would be vacuously true as we don't create a procedure in the setup for older versions
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11));

    DatabaseMetaData dbmd = conn.getMetaData();
    try (ResultSet rs = dbmd.getProcedures(null, "hasprocedures", null)) {
      int count = assertProcedureRS(rs);
      assertEquals(1, count, "getProcedures() should be non-empty for the hasprocedures schema");
    }
    try (ResultSet rs = dbmd.getProcedures("nonsensecatalog", "hasprocedures", null)) {
      assertFalse(rs.next(),"This should not return results as the catalog is not the same as the database");
    }
    try (ResultSet rs = dbmd.getProcedureColumns(null, "hasprocedures", null, null)) {
      int count = assertProcedureColumnsRS(rs);
      assertEquals(1, count, "getProcedureColumnss() should be non-empty for the hasprocedures schema");
    }
    try (ResultSet rs = dbmd.getProcedureColumns("nonsensecatalog", "hasprocedures", null,null)) {
      assertFalse(rs.next(),"This should not return results as the catalog is not the same as the database");
    }
  }

  @Test
  void getProceduresInSchemaForProcedures() throws SQLException {
    // Only run this test for PostgreSQL version 11+; assertions for versions prior would be vacuously true as we don't create a procedure in the setup for older versions
    assumeTrue(TestUtil.haveMinimumServerVersion(conn, ServerVersion.v11));

    DatabaseMetaData dbmd = conn.getMetaData();

    try (ResultSet rs = dbmd.getProcedures(null, "hasprocedures", null)) {
      int count = assertProcedureRS(rs);
      assertEquals(1, count, "getProcedures() should be non-empty for the hasprocedures schema");
    }

    try (ResultSet rs = dbmd.getProcedures(null, "noprocedures", null)) {
      assertFalse(rs.next(), "getProcedures() should be empty for the noprocedures schema");
    }

    try (ResultSet rs = dbmd.getProcedures(null, "hasfunctions", null)) {
      assertFalse(rs.next(), "getProcedures() should be empty for the hasfunctions schema");
    }

    try (ResultSet rs = dbmd.getProcedures(null, "nofunctions", null)) {
      assertFalse(rs.next(), "getProcedures() should be empty for the nofunctions schema");
    }
  }

  @Test
  void getFunctionsWithEmptyPatterns() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();
    try (ResultSet rs = dbmd.getFunctions("", "", "")) {
      assertFalse(rs.next());
    }

    try (ResultSet rs = dbmd.getFunctions("", null, null)) {
      assertFalse(rs.next());
    }

    try (ResultSet rs = dbmd.getFunctions(null, "", null)) {
      assertFalse(rs.next());
    }

    try (ResultSet rs = dbmd.getFunctions(null, null, "")) {
      assertFalse(rs.next());
    }
  }

  @Test
  void getFunctionsWithBadCatalog() throws SQLException {
    DatabaseMetaData dbmd = conn.getMetaData();

    try (ResultSet rs = dbmd.getFunctions("nonsensecatalog", "", "")) {
      assertFalse(rs.next());
    }
  }

  @Test
  void getFunctionsWithBlankPatterns() throws SQLException {
    int minFuncCount = 1000;
    DatabaseMetaData dbmd = conn.getMetaData();

    final int totalCount;
    try (ResultSet rs = dbmd.getFunctions("", "", "")) {
      assertFalse(rs.next());
    }

    // Should not be same as blank pattern
    try (ResultSet rs = dbmd.getFunctions(null, null, null)) {
      List<CatalogObject> list = assertFunctionRSAndReturnList(rs);
      totalCount = list.size(); // Rest of this test will validate against this value
      assertThat(totalCount > minFuncCount, is(true));
      assertListContains("getFunctions('', '', '') must contain addfunction", list, "hasfunctions", "addfunction");
    }

    // Catalog parameter has effect on our getFunctions filtering
    try (ResultSet rs = dbmd.getFunctions("ANYTHING_WILL_WORK", null, null)) {
      assertFalse(rs.next());
    }

    // Filter by schema
    try (ResultSet rs = dbmd.getFunctions("", "pg_catalog", null)) {
      assertFalse(rs.next());
    }

    // Filter by schema and function name
    try (ResultSet rs = dbmd.getFunctions("", "pg_catalog", "abs")) {
      assertFalse(rs.next());
    }

    // Filter by function name only
    try (ResultSet rs = dbmd.getFunctions("", "", "abs")) {
      assertFalse(rs.next());
    }
  }

  @Test
  void getFunctionsWithNullPatterns() throws SQLException {
    int minFuncCount = 1000;
    DatabaseMetaData dbmd = conn.getMetaData();

    final int totalCount;

    try (ResultSet rs = dbmd.getFunctions(null, null, null)) {
      List<CatalogObject> list = assertFunctionRSAndReturnList(rs);
      totalCount = list.size(); // Rest of this test will validate against this value
      assertThat(totalCount > minFuncCount, is(true));
      assertListContains("getFunctions('', '', '') must contain addfunction", list, "hasfunctions", "addfunction");
    }

    // Catalog parameter has effect on our getFunctions filtering
    try (ResultSet rs = dbmd.getFunctions(UUID.randomUUID().toString(), null, null)) {
      assertFalse(rs.next());
    }

    // Filter by schema
    try (ResultSet rs = dbmd.getFunctions(null, "pg_catalog", null)) {
      int count = assertGetFunctionRS(rs);
      assertThat(count > minFuncCount, is(true));
    }

    // Filter by schema and function name
    try (ResultSet rs = dbmd.getFunctions(null, "pg_catalog", "abs")) {
      int count = assertGetFunctionRS(rs);
      assertThat(count >= 1, is(true));
    }

    // Filter by function name only
    try (ResultSet rs = dbmd.getFunctions(null, null, "abs")) {
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
      result = prime * result + (catalog == null ? 0 : catalog.hashCode());
      result = prime * result + (name == null ? 0 : name.hashCode());
      result = prime * result + (schema == null ? 0 : schema.hashCode());
      result = prime * result + (specificName == null ? 0 : specificName.hashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      } else if (obj == this) {
        return true;
      }
      return compareTo((CatalogObject) obj) == 0;
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
  private static int assertGetFunctionRS(ResultSet rs) throws SQLException {
    return assertFunctionRSAndReturnList(rs).size();
  }

  private static List<CatalogObject> assertFunctionRSAndReturnList(ResultSet rs) throws SQLException {
    // There should be at least one row
    assertThat(rs.next(), is(true));
    assertThat(rs.getString("FUNCTION_CAT"), is(TestUtil.getDatabase()));
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

  private static int assertProcedureRS(ResultSet rs) throws SQLException {
    return assertProcedureRSAndReturnList(rs).size();
  }

  private static List<CatalogObject> assertProcedureRSAndReturnList(ResultSet rs) throws SQLException {
    // There should be at least one row
    assertThat(rs.next(), is(true));
    assertThat(rs.getString("PROCEDURE_CAT"), is(TestUtil.getDatabase()));
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

  private static int assertProcedureColumnsRS(ResultSet rs) throws SQLException {
    return assertProcedureColumnsRSAndReturnList(rs).size();
  }

  private static List<CatalogObject> assertProcedureColumnsRSAndReturnList(ResultSet rs) throws SQLException {
    // There should be at least one row
    assertThat(rs.next(), is(true));
    assertThat(rs.getString("PROCEDURE_CAT"), is(TestUtil.getDatabase()));
    assertThat(rs.getString("PROCEDURE_SCHEM"), notNullValue());
    assertThat(rs.getString("PROCEDURE_NAME"), notNullValue());
    assertThat(rs.getString("COLUMN_NAME") , notNullValue());
    assertThat(rs.getString("SPECIFIC_NAME"), notNullValue());

    // Ensure there is enough column and column value retrieve by index should be same as column name (ordered)
    assertThat(rs.getMetaData().getColumnCount(), is(20));
    assertThat(rs.getString(1), is(rs.getString("PROCEDURE_CAT")));
    assertThat(rs.getString(2), is(rs.getString("PROCEDURE_SCHEM")));
    assertThat(rs.getString(3), is(rs.getString("PROCEDURE_NAME")));
    // Per JDBC spec, indexes 4, 5, and 6 are reserved for future use
    assertThat(rs.getString(13), is(rs.getString("REMARKS")));
    assertThat(rs.getString(4), is(rs.getString("COLUMN_NAME")));
    assertThat(rs.getString(20), is(rs.getString("SPECIFIC_NAME")));

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

  private static void assertListContains(String message, List<CatalogObject> list, String schema, String name) throws SQLException {
    boolean found = list.stream().anyMatch(item -> item.schema.equals(schema) && item.name.equals(name));
    assertTrue(found, message + "; schema=" + schema + " name=" + name);
  }

  @Test
  void getFunctionsWithSpecificTypes_whenCatalogAndSchemaArgsEmpty_expectNoResults() throws SQLException {
    // These function creation are borrow from jdbc2/DatabaseMetaDataTest
    // We modify to ensure new function created are returned by getFunctions()

    DatabaseMetaData dbmd = conn.getMetaData();
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_4)) {
      Statement stmt = conn.createStatement();
      stmt.execute(
          "CREATE OR REPLACE FUNCTION getfunc_f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
      ResultSet rs = dbmd.getFunctions("", "", "getfunc_f1");
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f1(int, varchar)");

      stmt.execute(
          "CREATE OR REPLACE FUNCTION getfunc_f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
      rs = dbmd.getFunctions("", "", "getfunc_f3");
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f3(int, varchar)");

      // RETURNS TABLE requires PostgreSQL 8.4+
      stmt.execute(
          "CREATE OR REPLACE FUNCTION getfunc_f5() RETURNS TABLE (i int) LANGUAGE sql AS 'SELECT 1'");

      rs = dbmd.getFunctions("", "", "getfunc_f5");
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f5()");
    } else {
      // For PG 8.3 or 8.2 it will resulted in unknown function type
      Statement stmt = conn.createStatement();
      stmt.execute(
          "CREATE OR REPLACE FUNCTION getfunc_f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
      ResultSet rs = dbmd.getFunctions("", "", "getfunc_f1");
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f1(int, varchar)");

      stmt.execute(
          "CREATE OR REPLACE FUNCTION getfunc_f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
      rs = dbmd.getFunctions("", "", "getfunc_f3");
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f3(int, varchar)");
    }
  }

  @Test
  void getFunctionsWithSpecificTypes() throws SQLException {
    // These function creation are borrow from jdbc2/DatabaseMetaDataTest
    // We modify to ensure new function created are returned by getFunctions()

    DatabaseMetaData dbmd = conn.getMetaData();
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v8_4)) {
      Statement stmt = conn.createStatement();
      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
      ResultSet rs = dbmd.getFunctions(null, null, "getfunc_f1");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f1"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short) DatabaseMetaData.functionNoTable));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f1(int, varchar)");

      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
      rs = dbmd.getFunctions(null, null, "getfunc_f3");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f3"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short) DatabaseMetaData.functionNoTable));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f3(int, varchar)");

      // RETURNS TABLE requires PostgreSQL 8.4+
      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f5() RETURNS TABLE (i int) LANGUAGE sql AS 'SELECT 1'");

      rs = dbmd.getFunctions(null, null, "getfunc_f5");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f5"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short) DatabaseMetaData.functionReturnsTable));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f5()");
    } else {
      // For PG 8.3 or 8.2 it will resulted in unknown function type
      Statement stmt = conn.createStatement();
      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f1(int, varchar) RETURNS int AS 'SELECT 1;' LANGUAGE SQL");
      ResultSet rs = dbmd.getFunctions(null, null, "getfunc_f1");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f1"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short) DatabaseMetaData.functionResultUnknown));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f1(int, varchar)");

      stmt.execute(
              "CREATE OR REPLACE FUNCTION getfunc_f3(IN a int, INOUT b varchar, OUT c timestamptz) AS $f$ BEGIN b := 'a'; c := now(); return; END; $f$ LANGUAGE plpgsql");
      rs = dbmd.getFunctions(null, null, "getfunc_f3");
      assertThat(rs.next(), is(true));
      assertThat(rs.getString("FUNCTION_NAME"), is("getfunc_f3"));
      assertThat(rs.getShort("FUNCTION_TYPE"), is((short) DatabaseMetaData.functionResultUnknown));
      assertThat(rs.next(), is(false));
      rs.close();
      stmt.execute("DROP FUNCTION getfunc_f3(int, varchar)");
    }
  }

  @Test
  void sortedDataTypes() throws SQLException {
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

  @Test
  void getSqlTypes() throws SQLException {
    if (TestUtil.haveMinimumServerVersion(conn, ServerVersion.v10)) {
      try (Connection privileged = TestUtil.openPrivilegedDB()) {
        try (Statement stmt = privileged.createStatement()) {
          // create a function called array_in
          stmt.execute("CREATE OR REPLACE FUNCTION public.array_in(anyarray, oid, integer)\n"
              + " RETURNS anyarray\n"
              + " LANGUAGE internal\n"
              + " STABLE PARALLEL SAFE STRICT\n"
              + "AS $function$array_in$function$");
        }
        DatabaseMetaData dbmd = privileged.getMetaData();
        ResultSet rs = dbmd.getTypeInfo();
        try (Statement stmt = privileged.createStatement()) {
          stmt.execute("drop function public.array_in(anyarray, oid, integer)");
        }
      }
    }
  }
}
