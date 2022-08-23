/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.sql.*;

class InputStreamTest {

  private static Connection con;
  private static final String TABLE_NAME = "input_stream";
  private static final String INSERT1 = "INSERT INTO " + TABLE_NAME
      + " (id, data1) VALUES (?, ?)";
  private static final String SELECT1 = "SELECT data1 FROM " + TABLE_NAME
      + " WHERE id = ?";

  @BeforeAll
  public static void setUp() throws Exception {
    con = TestUtil.openDB();
    assumeTrue(TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_6));
    try (Statement stmt = con.createStatement()) {
      stmt.execute("CREATE TABLE " + TABLE_NAME
          + " (id int PRIMARY KEY, data1 bytea)");
    }
  }

  @AfterAll
  public static void tearDown() throws Exception {
    try (Statement stmt = con.createStatement()) {
      stmt.execute("DROP TABLE IF EXISTS " + TABLE_NAME);
    }
    TestUtil.closeDB(con);
  }


  @Test
  void testWithSetObject() throws SQLException {
    try (Connection c = assertDoesNotThrow(() -> TestUtil.openDB());
         PreparedStatement stmt1 = c.prepareStatement(INSERT1);
         PreparedStatement stmt2 = c.prepareStatement(SELECT1)) {
      String str = "<bpmn:definitions xmlns:bpmn=\"http://www.omg.org/spec/BPMN/20100524/MODEL\" " +
          "xmlns:bpmndi=\"http://www.omg.org/spec/BPMN/20100524/DI\" xmlns:dc=\"http://www.omg" +
          ".org/spec/DD/20100524/DC\" xmlns:di=\"http://www.omg.org/spec/DD/20100524/DI\" " +
          "xmlns:modeler=\"http://camunda.org/schema/modeler/1.0\" exporter=\"Camunda Modeler\" " +
          "exporterVersion=\"5.2.0\" expressionLanguage=\"http://www.w3.org/1999/XPath\" " +
          "id=\"Definitions_1g43prm\" modeler:executionPlatform=\"Camunda Platform\" " +
          "modeler:executionPlatformVersion=\"7.17.0\" targetNamespace=\"http://bpmn" +
          ".io/schema/bpmn\" typeLanguage=\"http://www.w3.org/2001/XMLSchema\">";
      ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(str.getBytes());
      stmt1.setInt(1, 101);
      stmt1.setObject(2, byteArrayInputStream);
      stmt1.execute();

      stmt2.setInt(1, 101);
      stmt2.execute();
      try (ResultSet rs = stmt2.getResultSet()) {
        assertTrue(rs.next());
        assertArrayEquals(rs.getBytes(1), str.getBytes());
      }
    }
  }
}
