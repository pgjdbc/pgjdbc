package org.postgresql.test.ssl;

import org.postgresql.test.TestUtil;

import junit.framework.TestSuite;

import java.util.Properties;

public class SslTestSuite extends TestSuite {

  private static void add(TestSuite suite, String param) {
    if (prop.getProperty(param, "").equals("")) {
      System.out.println("Skipping " + param + ".");
    } else {
      suite.addTest(SslTest.getSuite(prop, param));
    }
  }

  static Properties prop;

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

    String[] hostmode = {"sslhost", "sslhostssl", "sslhostsslcert", "sslcert"};
    String[] certmode = {"gh", "bh"};

    for (int i = 0; i < hostmode.length; i++) {
      for (int j = 0; j < certmode.length; j++) {
        add(suite, hostmode[i] + certmode[j] + "8");
        add(suite, hostmode[i] + certmode[j] + "9");
      }
    }

    TestUtil.initDriver();

    return suite;
  }
}
