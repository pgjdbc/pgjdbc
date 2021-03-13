/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.test.TestUtil;
import org.postgresql.util.DriverInfo;

import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;

/**
 * Test cases to print debug information of both client and server.
 */
public class PrintDebugInfoTest {
  @Test
  public void testShowDriverInfo() {
    System.err.println("========================================================================");
    System.err.println("                      PostgreSQL JDBC Driver Info                       ");
    System.err.println("                      ---------------------------                       ");
    System.err.println("                                                                        ");
    System.err.println("JAVA_VERSION = " + System.getProperty("java.version"));
    System.err.println("JAVA_VERSION = " + System.getProperty("os.name"));
    System.err.println("CLIENT_NOW = " + OffsetDateTime.now().toString());
    System.err.println("DRIVER_NAME = " + DriverInfo.DRIVER_NAME);
    System.err.println("DRIVER_SHORT_NAME = " + DriverInfo.DRIVER_SHORT_NAME);
    System.err.println("DRIVER_VERSION = " + DriverInfo.DRIVER_VERSION);
    System.err.println("DRIVER_MAJOR_VERSION = " + DriverInfo.MAJOR_VERSION);
    System.err.println("MINOR_VERSION = " + DriverInfo.MINOR_VERSION);
    System.err.println("PATCH_VERSION = " + DriverInfo.PATCH_VERSION);
    System.err.println("JDBC_VERSION = " + DriverInfo.JDBC_VERSION);
    System.err.println("========================================================================");
  }

  @Test
  public void testShowServerInfo() throws SQLException {
    System.err.println("========================================================================");
    System.err.println("                      PostgreSQL Test Server Info                       ");
    System.err.println("                      ---------------------------                       ");
    System.err.println("                                                                        ");
    try (Connection conn = TestUtil.openDB()) {
      System.err.println("SELECT version()");
      System.err.println("----------------");
      System.err.println(TestUtil.queryForString(conn, "SELECT version()"));
      System.err.println("");

      System.err.println("SELECT now()");
      System.err.println("------------");
      System.err.println(TestUtil.queryForString(conn, "SELECT now()"));
      System.err.println("");

      System.err.println("SELECT USER");
      System.err.println("----------------");
      System.err.println(TestUtil.queryForString(conn, "SELECT USER"));
      System.err.println("");

      System.err.println("SELECT current_database()");
      System.err.println("-------------------------");
      System.err.println(TestUtil.queryForString(conn, "SELECT current_database()"));
      System.err.println("");

      System.err.println("SHOW ALL");
      System.err.println("--------");
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SHOW ALL");
      while (rs.next()) {
        String name = rs.getString(1);
        String value = rs.getString(2);
        System.err.println(name + " = " + value);
      }
      rs.close();
      stmt.close();
    }
    System.err.println("========================================================================");
  }
}
