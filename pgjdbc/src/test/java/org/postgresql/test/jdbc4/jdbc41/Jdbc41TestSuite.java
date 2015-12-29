/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc4.jdbc41;

import junit.framework.TestSuite;

/*
 * Executes all known tests for JDBC4.1
 */
public class Jdbc41TestSuite extends TestSuite {

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    Class.forName("org.postgresql.Driver");
    TestSuite suite = new TestSuite();

    suite.addTestSuite(SchemaTest.class);
    suite.addTestSuite(AbortTest.class);
    suite.addTestSuite(CloseOnCompletionTest.class);

    return suite;
  }

}
