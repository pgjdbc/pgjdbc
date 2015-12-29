/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;

import junit.framework.TestSuite;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/*
 * Executes all known tests for JDBC4
 */
public class Jdbc4TestSuite extends TestSuite {

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    Class.forName("org.postgresql.Driver");
    TestSuite suite = new TestSuite();

    suite.addTestSuite(DatabaseMetaDataTest.class);
    suite.addTestSuite(ArrayTest.class);
    suite.addTestSuite(WrapperTest.class);
    suite.addTestSuite(BinaryTest.class);
    suite.addTestSuite(IsValidTest.class);
    suite.addTestSuite(ClientInfoTest.class);
    suite.addTestSuite(PGCopyInputStreamTest.class);
    suite.addTestSuite(BlobTest.class);
    suite.addTestSuite(BinaryStreamTest.class);

    Connection connection = TestUtil.openDB();
    try {
      if (TestUtil.haveMinimumServerVersion(connection, "8.3")) {
        suite.addTestSuite(UUIDTest.class);
        if (isXmlEnabled(connection)) {
          suite.addTestSuite(XmlTest.class);
        }
      }
    } finally {
      connection.close();
    }

    return suite;
  }

  /**
   * Not all servers will have been complied --with-libxml.
   */
  private static boolean isXmlEnabled(Connection conn) {
    try {
      Statement stmt = conn.createStatement();
      ResultSet rs = stmt.executeQuery("SELECT '<a>b</a>'::xml");
      rs.close();
      stmt.close();
      return true;
    } catch (SQLException sqle) {
      return false;
    }
  }

}

