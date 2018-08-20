/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Tests that database objects for which the current user has no privileges are filtered out from
 * the DatabaseMetaData depending on whether the connection parameter hideUnprivilegedObjects is
 * set to true.
 */
public class DatabaseMetaDataHideUnprivilegedObjectsTest {
  public static final String COLUMNS = "digit int4, name text";
  private static Connection hidingCon;
  private static Connection nonhidingCon;
  private static Connection privilegedCon;
  private static DatabaseMetaData hidingDatabaseMetaData;
  private static DatabaseMetaData nonhidingDatabaseMetaData;

  @BeforeClass
  public static void setUp() throws Exception {
    Properties props = new Properties();
    privilegedCon = TestUtil.openPrivilegedDB();
    Statement stmt = privilegedCon.createStatement();

    createTestDataObjectsWithRangeOfPrivilegesInSchema("high_privileges_schema");
    // Grant Test User ALL privileges on schema.
    stmt.executeUpdate("GRANT USAGE ON SCHEMA high_privileges_schema TO " + TestUtil.getUser());
    stmt.executeUpdate("GRANT CREATE ON SCHEMA high_privileges_schema TO " + TestUtil.getUser());
    stmt.executeUpdate("REVOKE USAGE ON SCHEMA high_privileges_schema FROM public");
    stmt.executeUpdate("REVOKE CREATE ON SCHEMA high_privileges_schema FROM public");

    createTestDataObjectsWithRangeOfPrivilegesInSchema("low_privileges_schema");
    // Grant Test User USAGE privileges on schema.
    stmt.executeUpdate("GRANT USAGE ON SCHEMA low_privileges_schema TO " + TestUtil.getUser());
    stmt.executeUpdate("REVOKE USAGE ON SCHEMA low_privileges_schema FROM public");
    stmt.executeUpdate("REVOKE CREATE ON SCHEMA low_privileges_schema FROM public");

    createTestDataObjectsWithRangeOfPrivilegesInSchema("no_privileges_schema");
    // Revoke ALL privileges from Test User USAGE on schema.
    stmt.executeUpdate("REVOKE USAGE ON SCHEMA no_privileges_schema FROM " + TestUtil.getUser());
    stmt.executeUpdate("REVOKE CREATE ON SCHEMA no_privileges_schema FROM " + TestUtil.getUser());
    stmt.executeUpdate("REVOKE USAGE ON SCHEMA no_privileges_schema FROM public");
    stmt.executeUpdate("REVOKE CREATE ON SCHEMA no_privileges_schema FROM public");

    stmt.close();

    nonhidingDatabaseMetaData = getNonHidingDatabaseMetaData(props);
    hidingDatabaseMetaData = getHidingDatabaseMetaData(props);
  }

  private static DatabaseMetaData getHidingDatabaseMetaData(Properties props) throws Exception {
    PGProperty.HIDE_UNPRIVILEGED_OBJECTS.set(props, true);
    hidingCon = TestUtil.openDB(props);
    if (isSuperUser(hidingCon)) {
      fail("Test for hiding database objects will not work while:" + TestUtil.getUser()
          + " has a SUPERUSER role.");
    }
    return hidingCon.getMetaData();
  }

  private static DatabaseMetaData getNonHidingDatabaseMetaData(Properties props) throws Exception {
    nonhidingCon = TestUtil.openDB(props);
    return nonhidingCon.getMetaData();
  }

  private static void createTestDataObjectsWithRangeOfPrivilegesInSchema(String schema)
      throws SQLException {
    TestUtil.createSchema(privilegedCon, schema);

    String[] tables = new String[]{"owned_table", "all_grants_table", "insert_granted_table",
        "select_granted_table", "no_grants_table"};
    createSimpleTablesInSchema(schema,tables);

    Statement stmt = privilegedCon.createStatement();
    stmt.executeUpdate(
        "CREATE FUNCTION " + schema + "."
            + "execute_granted_add_function(integer, integer) RETURNS integer AS 'select $1 + $2;' LANGUAGE SQL IMMUTABLE  RETURNS NULL ON NULL INPUT");
    stmt.executeUpdate(
        "CREATE FUNCTION " + schema + "."
            + "no_grants_add_function(integer, integer) RETURNS integer AS 'select $1 + $2;' LANGUAGE SQL IMMUTABLE  RETURNS NULL ON NULL INPUT");
    stmt.executeUpdate(
        "CREATE OR REPLACE VIEW " + schema + "." + "select_granted_view AS SELECT name FROM "
            + schema + "." + "select_granted_table");
    stmt.executeUpdate(
        "CREATE OR REPLACE VIEW " + schema + "." + "no_grants_view AS SELECT name FROM " + schema
            + "." + "owned_table");
    stmt.executeUpdate(
        "CREATE TYPE " + schema + "." + "usage_granted_composite_type AS (f1 int, f2 text)");
    stmt.executeUpdate(
        "CREATE TYPE " + schema + "." + "no_grants_composite_type AS (f1 int, f2 text)");
    stmt.executeUpdate(
        "CREATE DOMAIN " + schema + "." + "usage_granted_us_postal_code_domain CHAR(5) NOT NULL");
    stmt.executeUpdate(
        "CREATE DOMAIN " + schema + "." + "no_grants_us_postal_code_domain AS CHAR(5) NOT NULL");

    if (TestUtil.haveMinimumServerVersion(privilegedCon, ServerVersion.v9_2)) {
      stmt.executeUpdate(
          "REVOKE ALL ON TYPE " + schema + "." + "usage_granted_composite_type FROM public RESTRICT");
      stmt.executeUpdate(
          "REVOKE ALL ON TYPE " + schema + "." + "no_grants_composite_type FROM public RESTRICT");
      stmt.executeUpdate("GRANT USAGE on TYPE " + schema + "." + "usage_granted_composite_type TO "
          + TestUtil.getUser());
      stmt.executeUpdate(
          "REVOKE ALL ON TYPE " + schema + "."
              + "usage_granted_us_postal_code_domain FROM public RESTRICT");
      stmt.executeUpdate(
          "REVOKE ALL ON TYPE " + schema + "."
              + "no_grants_us_postal_code_domain FROM public RESTRICT");
      stmt.executeUpdate(
          "GRANT USAGE on TYPE " + schema + "." + "usage_granted_us_postal_code_domain TO "
              + TestUtil.getUser());
    }

    stmt.executeUpdate(
        "REVOKE ALL ON FUNCTION " + schema + ".execute_granted_add_function(integer, integer) FROM public RESTRICT");
    stmt.executeUpdate(
        "REVOKE ALL ON FUNCTION " + schema + ".no_grants_add_function(integer, integer) FROM public RESTRICT");
    stmt.executeUpdate(
        "REVOKE ALL ON FUNCTION " + schema + ".execute_granted_add_function(integer, integer) FROM " + TestUtil.getUser()
            + " RESTRICT");
    stmt.executeUpdate(
        "REVOKE ALL ON FUNCTION " + schema + ".no_grants_add_function(integer, integer) FROM " + TestUtil.getUser()
            + " RESTRICT");

    for (String table : tables) {
      for (String privilege : getTablePrivileges()) {
        stmt.executeUpdate(
            "REVOKE " + privilege + " ON TABLE " + schema + "." + table + " FROM public RESTRICT");
        stmt.executeUpdate(
            "REVOKE " + privilege + " ON TABLE " + schema + "." + table + " FROM " + TestUtil.getUser()
                + " RESTRICT");
      }
    }

    stmt.executeUpdate(
        "GRANT ALL ON FUNCTION " + schema + "."
            + "execute_granted_add_function(integer, integer) TO "
            + TestUtil.getUser());
    stmt.executeUpdate(
        "ALTER TABLE " + schema + "." + "owned_table OWNER TO " + TestUtil.getUser());

    for (String privilege : getTablePrivileges()) {
      stmt.executeUpdate(
          "GRANT " + privilege + " ON TABLE " + schema + "." + "all_grants_table TO " + TestUtil.getUser());
    }
    stmt.executeUpdate("GRANT INSERT ON TABLE " + schema + "." + "insert_granted_table TO "
        + TestUtil.getUser());
    stmt.executeUpdate("GRANT SELECT ON TABLE " + schema + "." + "select_granted_table TO "
        + TestUtil.getUser());
    stmt.executeUpdate("GRANT SELECT ON TABLE " + schema + "." + "select_granted_view TO "
        + TestUtil.getUser());
    stmt.close();
  }

  private static String[] getTablePrivileges() {
    return new String[] { "SELECT", "INSERT", "UPDATE", "DELETE", "RULE", "REFERENCES", "TRIGGER" };
  }

  private static void createSimpleTablesInSchema(String schema, String[] tables
  ) throws SQLException {
    for (String tableName : tables) {
      TestUtil.createTable(privilegedCon, schema + "." + tableName,
          COLUMNS);
    }
  }

  @AfterClass
  public static void tearDown() throws SQLException {
    TestUtil.closeDB(hidingCon);
    TestUtil.closeDB(nonhidingCon);
    TestUtil.dropSchema(privilegedCon, "high_privileges_schema");
    TestUtil.dropSchema(privilegedCon, "low_privileges_schema");
    TestUtil.dropSchema(privilegedCon, "no_privileges_schema");
    TestUtil.closeDB(privilegedCon);
  }

  private static boolean isSuperUser(Connection connection) throws SQLException {
    // Check if we're operating as a superuser.
    Statement st = connection.createStatement();
    st.executeQuery("SHOW is_superuser;");
    ResultSet rs = st.getResultSet();
    rs.next(); // One row is guaranteed
    boolean connIsSuper = rs.getString(1).equalsIgnoreCase("on");
    st.close();
    return connIsSuper;
  }

  @Test
  public void testGetSchemas() throws SQLException {
    List<String> schemasWithHiding = getSchemaNames(hidingDatabaseMetaData);
    assertThat(schemasWithHiding,
        hasItems("pg_catalog", "information_schema",
            "high_privileges_schema", "low_privileges_schema"));
    assertThat(schemasWithHiding,
        not(hasItem("no_privileges_schema")));

    List<String> schemasWithNoHiding = getSchemaNames(nonhidingDatabaseMetaData);
    assertThat(schemasWithNoHiding,
        hasItems("pg_catalog", "information_schema",
            "high_privileges_schema", "low_privileges_schema", "no_privileges_schema"));
  }

  List<String> getSchemaNames(DatabaseMetaData databaseMetaData) throws SQLException {
    List<String> schemaNames = new ArrayList<String>();
    ResultSet rs = databaseMetaData.getSchemas();
    while (rs.next()) {
      schemaNames.add(rs.getString("TABLE_SCHEM"));
    }
    return schemaNames;
  }

  @Test
  public void testGetTables() throws SQLException {
    List<String> tablesWithHiding = getTableNames(hidingDatabaseMetaData, "high_privileges_schema");

    assertThat(tablesWithHiding,
        hasItems(
            "owned_table",
            "all_grants_table",
            "insert_granted_table",
            "select_granted_table"));
    assertThat(tablesWithHiding,
        not(hasItem("no_grants_table")));

    List<String> tablesWithNoHiding =
        getTableNames(nonhidingDatabaseMetaData, "high_privileges_schema");
    assertThat(tablesWithNoHiding,
        hasItems(
            "owned_table",
            "all_grants_table",
            "insert_granted_table",
            "select_granted_table",
            "no_grants_table"));

    tablesWithHiding = getTableNames(hidingDatabaseMetaData, "low_privileges_schema");

    assertThat(tablesWithHiding,
        hasItems(
            "owned_table",
            "all_grants_table",
            "insert_granted_table",
            "select_granted_table"));
    assertThat(tablesWithHiding,
        not(hasItem("no_grants_table")));

    tablesWithNoHiding =
        getTableNames(nonhidingDatabaseMetaData, "low_privileges_schema");
    assertThat(tablesWithNoHiding,
        hasItems(
            "owned_table",
            "all_grants_table",
            "insert_granted_table",
            "select_granted_table",
            "no_grants_table"));

    // Or should the the tables names not be returned because the schema is not visible?
    tablesWithHiding = getTableNames(hidingDatabaseMetaData, "no_privileges_schema");

    assertThat(tablesWithHiding,
        hasItems(
            "owned_table",
            "all_grants_table",
            "insert_granted_table",
            "select_granted_table"));
    assertThat(tablesWithHiding,
        not(hasItem("no_grants_table")));

    tablesWithNoHiding =
        getTableNames(nonhidingDatabaseMetaData, "no_privileges_schema");
    assertThat(tablesWithNoHiding,
        hasItems(
            "owned_table",
            "all_grants_table",
            "insert_granted_table",
            "select_granted_table",
            "no_grants_table"));

  }

  List<String> getTableNames(DatabaseMetaData databaseMetaData, String schemaPattern)
      throws SQLException {
    List<String> tableNames = new ArrayList<String>();
    ResultSet rs = databaseMetaData.getTables(null, schemaPattern, null, new String[]{"TABLE"});
    while (rs.next()) {
      tableNames.add(rs.getString("TABLE_NAME"));
    }
    return tableNames;
  }

  @Test
  public void testGetViews() throws SQLException {
    List<String> viewsWithHiding = getViewNames(hidingDatabaseMetaData, "high_privileges_schema");

    assertThat(viewsWithHiding,
        hasItems(
            "select_granted_view"));
    assertThat(viewsWithHiding,
        not(hasItem("no_grants_view")));

    List<String> viewsWithNoHiding =
        getViewNames(nonhidingDatabaseMetaData, "high_privileges_schema");
    assertThat(viewsWithNoHiding,
        hasItems(
            "select_granted_view",
            "no_grants_view"));

    viewsWithHiding = getViewNames(hidingDatabaseMetaData, "low_privileges_schema");

    assertThat(viewsWithHiding,
        hasItems(
            "select_granted_view"));
    assertThat(viewsWithHiding,
        not(hasItem("no_grants_view")));

    viewsWithNoHiding =
        getViewNames(nonhidingDatabaseMetaData, "low_privileges_schema");
    assertThat(viewsWithNoHiding,
        hasItems(
            "select_granted_view",
            "no_grants_view"));

    // Or should the the view names not be returned because the schema is not visible?
    viewsWithHiding = getViewNames(hidingDatabaseMetaData, "no_privileges_schema");

    assertThat(viewsWithHiding,
        hasItems(
            "select_granted_view"));
    assertThat(viewsWithHiding,
        not(hasItem("no_grants_view")));

    viewsWithNoHiding =
        getViewNames(nonhidingDatabaseMetaData, "no_privileges_schema");
    assertThat(viewsWithNoHiding,
        hasItems(
            "select_granted_view",
            "no_grants_view"));

  }

  List<String> getViewNames(DatabaseMetaData databaseMetaData, String schemaPattern)
      throws SQLException {
    List<String> viewNames = new ArrayList<String>();
    ResultSet rs = databaseMetaData.getTables(null, schemaPattern, null, new String[]{"VIEW"});
    while (rs.next()) {
      viewNames.add(rs.getString("TABLE_NAME"));
    }
    return viewNames;
  }

  @Test
  public void testGetFunctions() throws SQLException {
    List<String> functionsWithHiding =
        getFunctionNames(hidingDatabaseMetaData, "high_privileges_schema");
    assertThat(functionsWithHiding,
        hasItem("execute_granted_add_function"));
    assertThat(functionsWithHiding,
        not(hasItem("no_grants_add_function")));

    List<String> functionsWithNoHiding =
        getFunctionNames(nonhidingDatabaseMetaData, "high_privileges_schema");
    assertThat(functionsWithNoHiding,
        hasItems("execute_granted_add_function", "no_grants_add_function"));

    functionsWithHiding =
        getFunctionNames(hidingDatabaseMetaData, "low_privileges_schema");
    assertThat(functionsWithHiding,
        hasItem("execute_granted_add_function"));
    assertThat(functionsWithHiding,
        not(hasItem("no_grants_add_function")));

    functionsWithNoHiding =
        getFunctionNames(nonhidingDatabaseMetaData, "low_privileges_schema");
    assertThat(functionsWithNoHiding,
        hasItems("execute_granted_add_function", "no_grants_add_function"));

    functionsWithHiding =
        getFunctionNames(hidingDatabaseMetaData, "no_privileges_schema");
    assertThat(functionsWithHiding,
        not(hasItem("execute_granted_add_function")));
    assertThat(functionsWithHiding,
        not(hasItem("no_grants_add_function")));

    // PgDatabaseMetaData.getFunctions() has a WHERE clause that uses pg_function_is_visible(oid)
    // which will return false if current role does not have usage on the schema. Therefore, even though
    // this test is using the nonhidingDatabaseMetaData, we do not expect that getFunctions() will return
    // anything for the no_privileges_schema (because both the test and public roles have been revoked
    // from all privileges).
    functionsWithNoHiding =
        getFunctionNames(nonhidingDatabaseMetaData, "no_privileges_schema");
    assertThat(functionsWithNoHiding,
        not(hasItems("execute_granted_add_function", "no_grants_add_function")));
  }

  List<String> getFunctionNames(DatabaseMetaData databaseMetaData, String schemaPattern)
      throws SQLException {
    List<String> functionNames = new ArrayList<String>();

    // Set the search_path to schemaPattern because PgDatabaseMetaData.getFunctions()
    // has a WHERE clause that uses pg_function_is_visible(oid). This function returns
    // a boolean that describes whether or not the function is visible in your
    // session's search_path (as well as whether or not the role has usage on the schema).
    // N.B. do not use Connection.setSchema() because pgjdbc needs to work with openjdk6.
    Statement stmt = databaseMetaData.getConnection().createStatement();
    stmt.executeUpdate("SET SESSION search_path TO '" + schemaPattern + "',public");
    stmt.close();

    ResultSet rs = databaseMetaData.getFunctions(null, schemaPattern, null);
    while (rs.next()) {
      functionNames.add(rs.getString("FUNCTION_NAME"));
    }
    return functionNames;
  }

  @Test
  /*
   *  According to the JDBC JavaDoc, the applicable UDTs are: JAVA_OBJECT, STRUCT, or DISTINCT.
   */
  public void testGetUDTs() throws SQLException {
    if (TestUtil.haveMinimumServerVersion(privilegedCon, ServerVersion.v9_2)) {
      List<String> typesWithHiding = getTypeNames(hidingDatabaseMetaData, "high_privileges_schema");
      assertThat(typesWithHiding,
          hasItems("usage_granted_composite_type", "usage_granted_us_postal_code_domain"));
      assertThat(typesWithHiding,
          not(hasItems("no_grants_composite_type", "no_grants_us_postal_code_domain")));
      List<String> typesWithNoHiding =
          getTypeNames(nonhidingDatabaseMetaData, "high_privileges_schema");
      assertThat(typesWithNoHiding,
          hasItems("usage_granted_composite_type", "no_grants_composite_type",
              "usage_granted_us_postal_code_domain", "no_grants_us_postal_code_domain"));

      typesWithHiding = getTypeNames(hidingDatabaseMetaData, "low_privileges_schema");
      assertThat(typesWithHiding,
          hasItems("usage_granted_composite_type", "usage_granted_us_postal_code_domain"));
      assertThat(typesWithHiding,
          not(hasItems("no_grants_composite_type", "no_grants_us_postal_code_domain")));
      typesWithNoHiding =
          getTypeNames(nonhidingDatabaseMetaData, "low_privileges_schema");
      assertThat(typesWithNoHiding,
          hasItems("usage_granted_composite_type", "no_grants_composite_type",
              "usage_granted_us_postal_code_domain", "no_grants_us_postal_code_domain"));

      // Or should the the types names not be returned because the schema is not visible?
      typesWithHiding = getTypeNames(hidingDatabaseMetaData, "no_privileges_schema");
      assertThat(typesWithHiding,
          hasItems("usage_granted_composite_type", "usage_granted_us_postal_code_domain"));
      assertThat(typesWithHiding,
          not(hasItems("no_grants_composite_type", "no_grants_us_postal_code_domain")));
      typesWithNoHiding =
          getTypeNames(nonhidingDatabaseMetaData, "no_privileges_schema");
      assertThat(typesWithNoHiding,
          hasItems("usage_granted_composite_type", "no_grants_composite_type",
              "usage_granted_us_postal_code_domain", "no_grants_us_postal_code_domain"));
    }
  }

  /*
  From the Postgres JDBC driver source code, we are mapping the types:
      java.sql.Types.DISTINCT to the Postgres type:  TYPTYPE_COMPOSITE  'c'   # composite (e.g., table's rowtype)
      java.sql.Types.STRUCT   to the Postgres type:  TYPTYPE_DOMAIN     'd'   # domain over another type
   */
  List<String> getTypeNames(DatabaseMetaData databaseMetaData, String schemaPattern)
      throws SQLException {
    List<String> typeNames = new ArrayList<String>();
    ResultSet rs = databaseMetaData.getUDTs(null, schemaPattern, null, null);
    while (rs.next()) {
      typeNames.add(rs.getString("TYPE_NAME"));
    }
    return typeNames;
  }
}
