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
    TestSuite suite = new TestSuite();
    try {
      java.sql.Connection con = TestUtil.openDB();

      suite.addTest(new JUnit4TestAdapter(Jdbc3CallableStatementTest.class));
      suite.addTest(new JUnit4TestAdapter(GeneratedKeysTest.class));
      con.close();
    } catch (Exception ex) {
      ex.printStackTrace();
    }
    suite.addTest(new JUnit4TestAdapter(CompositeQueryParseTest.class));
    suite.addTest(new JUnit4TestAdapter(Jdbc3SavepointTest.class));
    suite.addTest(new JUnit4TestAdapter(TypesTest.class));
    suite.addTest(new JUnit4TestAdapter(ResultSetTest.class));
    suite.addTest(new JUnit4TestAdapter(ParameterMetaDataTest.class));
    suite.addTest(new JUnit4TestAdapter(Jdbc3BlobTest.class));
    suite.addTest(new JUnit4TestAdapter(DatabaseMetaDataTest.class));
    suite.addTest(new JUnit4TestAdapter(SendRecvBufferSizeTest.class));
    suite.addTestSuite(StringTypeParameterTest.class);
    return suite;
  }
}
