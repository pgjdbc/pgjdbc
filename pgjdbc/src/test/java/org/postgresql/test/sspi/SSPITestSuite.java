package org.postgresql.test.sspi;

import junit.framework.JUnit4TestAdapter;
import junit.framework.TestSuite;

/*
 * Executes all known tests for SSPI.
 */
public class SSPITestSuite extends TestSuite {

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    TestSuite suite = new TestSuite();

    // Add one line per class in our test cases.
    suite.addTestSuite(SSPITest.class);

    // That's all folks.
    return suite;
  }

}
