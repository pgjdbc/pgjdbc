/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.extensions;

import org.postgresql.test.TestUtil;

import junit.framework.JUnit4TestAdapter;
import junit.framework.TestSuite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/*
 * Executes all known tests for PostgreSQL extensions supported by JDBC driver
 */
public class ExtensionsTestSuite extends TestSuite {

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    TestSuite suite = new TestSuite();

    Connection connection = TestUtil.openDB();
    try {
      if (isHStoreEnabled(connection)) {
        suite.addTest(new JUnit4TestAdapter(HStoreTest.class));
      }
    } finally {
      connection.close();
    }

    return suite;
  }

  /**
   * Not all servers have hstore extensions installed.
   */
  private static boolean isHStoreEnabled(Connection conn) {
    try {
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT 'a=>1'::hstore::text");
      rs.close();
      stmt.close();
      return true;
    } catch (SQLException sqle) {
      return false;
    }
  }

}

