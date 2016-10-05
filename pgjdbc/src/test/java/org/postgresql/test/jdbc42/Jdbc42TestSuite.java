/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc42;

import junit.framework.TestSuite;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;

@RunWith(Suite.class)
@SuiteClasses({
    SimpleJdbc42Test.class,
    CustomizeDefaultFetchSizeTest.class,
    GetObject310Test.class,
    PreparedStatementTest.class,
    GetObject310BinaryTest.class,
    SetObject310Test.class})
public class Jdbc42TestSuite {

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    Class.forName("org.postgresql.Driver");
    TestSuite suite = new TestSuite();

    suite.addTestSuite(PreparedStatementTest.class);

    return suite;
  }

}
