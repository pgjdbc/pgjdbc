/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.TypeInfo;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PGInterval;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * The tests put operators and functions on the search_path that raise an error when they are
 * called. pg_catalog has none with exactly their argument types, so an unqualified operator or
 * function in a catalog query of the driver would resolve to them.
 */
class SearchPathOperatorTest {
  private static final String SCHEMA = "search_path_operator_test";

  private static final String[][] OPERATORS = {
      {"=", "oid", "int4"},
      {"=", "oid", "int8"},
      {"=", "oid", "regclass"},
      {"=", "regproc", "regproc"},
      {"=", "name", "varchar"},
      {"~~", "name", "varchar"},
      {"~~", "name", "name"},
      {"~", "name", "name"},
      {"!~", "name", "name"},
      {"||", "name", "name"},
      {"||", "text", "oid"},
  };

  private static final String[] FUNCTIONS = {
      "array_upper(name[], int4)",
      "format_type(oid, text)",
      "has_schema_privilege(name, text)",
      "has_table_privilege(oid, varchar)",
      "replace(name, text, text)",
  };

  private Connection con;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    TestUtil.createSchema(con, SCHEMA);
    // Before 9.2 the server refuses a search_path with a schema that does not exist.
    setSearchPath(con);
    for (String[] op : OPERATORS) {
      String signature = "fail(" + op[1] + ", " + op[2] + ")";
      createFailingFunction(signature);
      TestUtil.execute(con, "CREATE OPERATOR " + SCHEMA + "." + op[0] + " (LEFTARG = " + op[1]
          + ", RIGHTARG = " + op[2] + ", PROCEDURE = " + SCHEMA + ".fail)");
    }
    for (String function : FUNCTIONS) {
      createFailingFunction(function);
    }
    TestUtil.createTable(con, SCHEMA + ".parent", "id serial primary key, val text not null");
    TestUtil.createTable(con, SCHEMA + ".child",
        "id int4 primary key, parent_id int4 references " + SCHEMA + ".parent");
    // SQL functions can refer to a parameter by name only from 9.2.
    TestUtil.execute(con, "CREATE FUNCTION " + SCHEMA + ".func(a int4) RETURNS int4"
        + " LANGUAGE sql AS 'SELECT $1'");
    TestUtil.execute(con, "INSERT INTO " + SCHEMA + ".parent (val) VALUES ('a')");
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.dropSchema(con, SCHEMA);
    TestUtil.closeDB(con);
  }

  private static Connection openDB(Properties props) throws SQLException {
    Connection con = TestUtil.openDB(props);
    setSearchPath(con);
    return con;
  }

  private static void setSearchPath(Connection con) throws SQLException {
    TestUtil.execute(con, "SET search_path TO " + SCHEMA + ", public");
  }

  private void createFailingFunction(String signature) throws SQLException {
    TestUtil.execute(con, "CREATE OR REPLACE FUNCTION " + SCHEMA + "." + signature
        + " RETURNS boolean LANGUAGE plpgsql AS"
        + " 'BEGIN RAISE EXCEPTION ''" + signature + " was called''; END'");
  }

  private static int count(ResultSet rs) throws SQLException {
    int rows = 0;
    while (rs.next()) {
      rows++;
    }
    rs.close();
    return rows;
  }

  @Test
  void resultSetMetaData() throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT p.id, p.val, c.parent_id FROM " + SCHEMA
             + ".parent p LEFT JOIN " + SCHEMA + ".child c ON (p.id = c.parent_id)")) {
      ResultSetMetaData rsmd = rs.getMetaData();
      assertEquals("parent", rsmd.getTableName(1));
      assertEquals(ResultSetMetaData.columnNoNulls, rsmd.isNullable(2));
      assertEquals("child", rsmd.getTableName(3));
    }
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT id FROM " + SCHEMA + ".child")) {
      assertEquals("child", rs.getMetaData().getTableName(1));
    }
  }

  @Test
  void updatableResultSet() throws SQLException {
    try (Statement stmt =
             con.createStatement(ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);
         ResultSet rs = stmt.executeQuery("SELECT id, val FROM " + SCHEMA + ".parent")) {
      assertTrue(rs.next());
      rs.updateString(2, "b");
      rs.updateRow();
      assertEquals("b", rs.getString(2));
    }
  }

  @Test
  void typeInfo() throws SQLException {
    try (PreparedStatement ps = con.prepareStatement("SELECT ?")) {
      ps.setObject(1, new PGInterval("1 day"));
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next());
      }
    }
    TypeInfo typeInfo = con.unwrap(BaseConnection.class).getTypeInfo();
    int oid = typeInfo.getPGType("parent");
    assertNotEquals(0, oid);
    int arrayOid = typeInfo.getPGType("parent[]");
    assertEquals(oid, typeInfo.getPGType(SCHEMA + ".parent"));
    assertEquals(oid, typeInfo.getPGArrayElement(arrayOid));
    assertEquals(',', typeInfo.getArrayDelimiter(arrayOid));
    assertEquals("parent", typeInfo.getPGType(oid));
  }

  @Test
  void databaseMetaData() throws SQLException {
    databaseMetaData(con);
  }

  @Test
  void databaseMetaDataHideUnprivilegedObjects() throws SQLException {
    Properties props = new Properties();
    PGProperty.HIDE_UNPRIVILEGED_OBJECTS.set(props, true);
    try (Connection hideUnprivileged = openDB(props)) {
      databaseMetaData(hideUnprivileged);
    }
  }

  private static void databaseMetaData(Connection con) throws SQLException {
    DatabaseMetaData dbmd = con.getMetaData();
    assertEquals(1, count(dbmd.getSchemas(null, SCHEMA)));
    assertEquals(2, count(dbmd.getTables(null, SCHEMA, "%", new String[]{"TABLE"})));
    assertEquals(2, count(dbmd.getColumns(null, SCHEMA, "parent", "%")));
    assertNotEquals(0, count(dbmd.getColumnPrivileges(null, SCHEMA, "parent", "%")));
    assertNotEquals(0, count(dbmd.getTablePrivileges(null, SCHEMA, "parent")));
    assertEquals(1, count(dbmd.getBestRowIdentifier(null, SCHEMA, "parent",
        DatabaseMetaData.bestRowSession, false)));
    assertEquals(1, count(dbmd.getPrimaryKeys(null, SCHEMA, "parent")));
    assertEquals(1, count(dbmd.getImportedKeys(null, SCHEMA, "child")));
    assertEquals(1, count(dbmd.getExportedKeys(null, SCHEMA, "parent")));
    assertEquals(1, count(dbmd.getCrossReference(null, SCHEMA, "parent", null, SCHEMA, "child")));
    assertEquals(1, count(dbmd.getIndexInfo(null, SCHEMA, "parent", true, false)));
    assertNotEquals(0, count(dbmd.getTypeInfo()));
    assertEquals(1, count(dbmd.getFunctions(null, SCHEMA, "func")));
    assertEquals(2, count(dbmd.getFunctionColumns(null, SCHEMA, "func", "%")));
    count(dbmd.getProcedures(null, SCHEMA, "%"));
    assertEquals(2, count(dbmd.getProcedureColumns(null, SCHEMA, "func", "%")));
  }
}
