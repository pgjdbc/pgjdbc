/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.test.TestUtil;

import junit.framework.TestSuite;

import java.util.Properties;

public class SslTestSuite extends TestSuite {
  private static Properties prop;

  private static void add(TestSuite suite, String param) {
    if (prop.getProperty(param, "").equals("")) {
      System.out.println("Skipping " + param + ".");
    } else {
      suite.addTest(SslTest.getSuite(prop, param));
    }
  }

  /*
   * The main entry point for JUnit
   */
  public static TestSuite suite() throws Exception {
    TestSuite suite = new TestSuite();
    prop = TestUtil.loadPropertyFiles("ssltest.properties");
    add(suite, "ssloff9");
    add(suite, "sslhostnossl9");

    String[] hostModes = {"sslhost", "sslhostssl", "sslhostsslcert", "sslcert"};
    String[] certModes = {"gh", "bh"};

    for (String hostMode : hostModes) {
      for (String certMode : certModes) {
        add(suite, hostMode + certMode + "9");
      }
    }

    TestUtil.initDriver();

    return suite;
  }
}
