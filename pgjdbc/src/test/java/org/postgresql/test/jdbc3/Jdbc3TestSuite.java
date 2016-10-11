/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import org.postgresql.test.TestUtil;

import junit.framework.JUnit4TestAdapter;
import junit.framework.TestSuite;

/*
 * Executes all known tests for JDBC3
 */
public class Jdbc3TestSuite extends TestSuite {

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    Class.forName("org.postgresql.Driver");
    TestSuite suite = new TestSuite();
    try {
      java.sql.Connection con = TestUtil.openDB();

      if (TestUtil.haveMinimumServerVersion(con, "8.1") && TestUtil.isProtocolVersion(con, 3)) {
        suite.addTest(new JUnit4TestAdapter(Jdbc3CallableStatementTest.class));
      }
      if (TestUtil.haveMinimumServerVersion(con, "8.2")) {
        suite.addTest(new JUnit4TestAdapter(GeneratedKeysTest.class));
      }
      con.close();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    suite.addTestSuite(CompositeQueryParseTest.class);
    suite.addTestSuite(Jdbc3SavepointTest.class);
    suite.addTest(new JUnit4TestAdapter(TypesTest.class));
    suite.addTestSuite(ResultSetTest.class);
    suite.addTest(new JUnit4TestAdapter(ParameterMetaDataTest.class));
    suite.addTestSuite(Jdbc3BlobTest.class);
    suite.addTestSuite(DatabaseMetaDataTest.class);
    suite.addTestSuite(SendRecvBufferSizeTest.class);
    suite.addTestSuite(StringTypeParameterTest.class);
    return suite;
  }
}
