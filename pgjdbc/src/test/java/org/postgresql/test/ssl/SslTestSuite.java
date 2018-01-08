/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.test.TestUtil;

import junit.framework.TestSuite;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SslTestSuite extends TestSuite {
  private static final Logger LOGGER = Logger.getLogger(SslTestSuite.class.getName());
  private static Properties prop;

  private static void add(TestSuite suite, String param) {
    if (prop.getProperty(param, "").equals("")) {
      LOGGER.log(Level.INFO, "Skipping {0}.", param);
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
    add(suite, "ssloff8");
    add(suite, "sslhostnossl8");
    add(suite, "ssloff9");
    add(suite, "sslhostnossl9");

    String[] hostModes = {"sslhost", "sslhostssl", "sslhostsslcert", "sslcert"};
    String[] certModes = {"gh", "bh"};

    for (String hostMode : hostModes) {
      for (String certMode : certModes) {
        add(suite, hostMode + certMode + "8");
        add(suite, hostMode + certMode + "9");
      }
    }

    TestUtil.initDriver();

    return suite;
  }
}
