/*-------------------------------------------------------------------------
*
* Copyright (c) 2007-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.hostchooser;

import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

/*
 * Executes multi host tests (aka master/slave connectivity selection).
 */
public class MultiHostTestSuite extends TestSuite {

  public static java.sql.Connection openSlaveDB() throws Exception {
    TestUtil.initDriver();

    Properties props = new Properties();

    props.setProperty("user", TestUtil.getUser());
    props.setProperty("password", TestUtil.getPassword());

    return DriverManager.getConnection(TestUtil.getURL(getSlaveServer(), getSlavePort()), props);
  }

  /*
   * Returns the Test server
   */
  public static String getSlaveServer() {
    return System.getProperty("slaveServer", TestUtil.getServer());
  }

  /*
   * Returns the Test port
   */
  public static int getSlavePort() {
    return Integer.parseInt(
        System.getProperty("slavePort", String.valueOf(TestUtil.getPort() + 1)));
  }

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    Class.forName("org.postgresql.Driver");
    TestSuite suite = new TestSuite();

    try {
      Connection connection = openSlaveDB();
      TestUtil.closeDB(connection);
    } catch (PSQLException ex) {
      // replication instance is not available, but suite must have at lest one test case
      suite.addTestSuite(DummyTest.class);
      return suite;
    }

    suite.addTestSuite(MultiHostsConnectionTest.class);
    return suite;
  }

  public static class DummyTest extends TestCase {
    public DummyTest(String name) {
      super(name);
    }

    public void testDummy() {
    }
  }
}

