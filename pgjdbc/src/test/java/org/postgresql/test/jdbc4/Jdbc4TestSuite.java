/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;

import junit.framework.JUnit4TestAdapter;
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
    TestSuite suite = new TestSuite();

    suite.addTestSuite(DatabaseMetaDataTest.class);
    suite.addTest(new JUnit4TestAdapter(ArrayTest.class));
    suite.addTestSuite(WrapperTest.class);
    suite.addTest(new JUnit4TestAdapter(BinaryTest.class));
    suite.addTestSuite(IsValidTest.class);
    suite.addTestSuite(ClientInfoTest.class);
    suite.addTestSuite(PGCopyInputStreamTest.class);
    suite.addTestSuite(BlobTest.class);
    suite.addTest(new JUnit4TestAdapter(BinaryStreamTest.class));

    Connection connection = TestUtil.openDB();
    try {
      if (TestUtil.haveMinimumServerVersion(connection, ServerVersion.v8_3)) {
        suite.addTest(new JUnit4TestAdapter(UUIDTest.class));
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

