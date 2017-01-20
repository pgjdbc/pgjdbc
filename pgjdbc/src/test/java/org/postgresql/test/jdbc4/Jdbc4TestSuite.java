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

    suite.addTest(new JUnit4TestAdapter(DatabaseMetaDataTest.class));
    suite.addTest(new JUnit4TestAdapter(ArrayTest.class));
    suite.addTest(new JUnit4TestAdapter(WrapperTest.class));
    suite.addTest(new JUnit4TestAdapter(BinaryTest.class));
    suite.addTest(new JUnit4TestAdapter(IsValidTest.class));
    suite.addTest(new JUnit4TestAdapter(ClientInfoTest.class));
    suite.addTest(new JUnit4TestAdapter(PGCopyInputStreamTest.class));
    suite.addTest(new JUnit4TestAdapter(BlobTest.class));
    suite.addTest(new JUnit4TestAdapter(BinaryStreamTest.class));
    suite.addTest(new JUnit4TestAdapter(CharacterStreamTest.class));

    Connection connection = TestUtil.openDB();
    try {
      if (TestUtil.haveMinimumServerVersion(connection, ServerVersion.v8_3)) {
        suite.addTest(new JUnit4TestAdapter(UUIDTest.class));
        if (isXmlEnabled(connection)) {
          suite.addTest(new JUnit4TestAdapter(XmlTest.class));
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

