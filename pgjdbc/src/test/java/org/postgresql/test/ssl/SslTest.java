/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;
import junit.framework.TestSuite;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

public class SslTest extends TestCase {

  /**
   * Tries to connect to the database.
   *
   */
  protected void driver() throws SQLException {
    Connection conn = null;
    String exMsgRegex = expected.exceptionMessageRegex;
    try {
      conn = DriverManager.getConnection(makeConnStr(), TestUtil.getUser(), TestUtil.getPassword());
      if (exMsgRegex != null) {
        fail("Exception did not occur: " + exMsgRegex);
      }
      //
      ResultSet rs = conn.createStatement().executeQuery("select ssl_is_used()");
      assertTrue(rs.next());
      assertEquals("ssl_is_used: ", expected.useSsl, rs.getBoolean(1));
      conn.close();
    } catch (SQLException ex) {
      if (conn != null) {
        conn.close();
      }
      if (exMsgRegex == null) { // no exception is excepted
        fail("Exception thrown: " + ex.getMessage());
      } else {
        assertTrue("expected: " + exMsgRegex + " actual: " + ex.getMessage(),
            ex.getMessage().matches(exMsgRegex));
      }
    }
  }

  private final String certdir;
  private final String connstr;
  private final String sslmode;
  private final int protocol;
  private final boolean goodclient;
  private final boolean goodserver;
  private final String prefix;
  private final Expected expected;

  private String makeConnStr() {
    return connstr + "&protocolVersion=" + protocol
        + "&sslmode=" + sslmode
        + "&sslcert=" + certdir + "/" + prefix + (goodclient ? "goodclient.crt" : "badclient.crt")
        + "&sslkey=" + certdir + "/" + prefix + (goodclient ? "goodclient.pk8" : "badclient.pk8")
        + "&sslrootcert=" + certdir + "/" + prefix + (goodserver ? "goodroot.crt" : "badroot.crt")
        + "&loglevel=" + TestUtil.getLogLevel();
  }

  public SslTest(String name, String certdir, String connstr, String sslmode, int protocol,
      boolean goodclient, boolean goodserver, String prefix, Expected expected) {
    super(name);
    this.certdir = certdir;
    this.connstr = connstr;
    this.sslmode = sslmode;
    this.protocol = protocol;
    this.goodclient = goodclient;
    this.goodserver = goodserver;
    this.prefix = prefix;
    this.expected = expected;
  }

  static TestSuite getSuite(Properties prop, String param) {
    File certDirFile = TestUtil.getFile(prop.getProperty("certdir"));
    String certdir = certDirFile.getAbsolutePath();
    String sconnstr = prop.getProperty(param);
    String sprefix = prop.getProperty(param + "prefix");
    String[] sslModes = {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"};

    TestSuite suite = new TestSuite();
    Map<String, Expected> expected = expectedMap.get(param);
    if (expected == null) {
      expected = defaultExpected;
    }
    for (String sslMode : sslModes) {
      suite.addTest(new SslTest(param + "-" + sslMode + "GG3", certdir, sconnstr, sslMode,
          3, true, true, sprefix, expected.get(sslMode + "GG")));
      suite.addTest(new SslTest(param + "-" + sslMode + "GB3", certdir, sconnstr, sslMode,
          3, true, false, sprefix, expected.get(sslMode + "GB")));
      suite.addTest(new SslTest(param + "-" + sslMode + "BG3", certdir, sconnstr, sslMode,
          3, false, true, sprefix, expected.get(sslMode + "BG")));
    }
    return suite;
  }

  @Override
  protected void runTest() throws Throwable {
    driver();
  }

  private static Map<String, Map<String, Expected>> expectedMap;
  private static TreeMap<String, Expected> defaultExpected;

  // For some strange reason, the v2 driver begins these error messages by "Connection rejected: "
  // but the v3 does not.
  private static String PG_HBA_ON =
      "(Connection rejected: )?FATAL:  ?no pg_hba.conf entry for host .*, user .*, database .*, SSL on(?s-d:.*)";
  private static String PG_HBA_OFF =
      "(Connection rejected: )?FATAL:  ?no pg_hba.conf entry for host .*, user .*, database .*, SSL off(?s-d:.*)";
  static String FAILED = "The connection attempt failed.";
  private static String BROKEN =
      "SSL error: (Broken pipe|Received fatal alert: unknown_ca|Connection reset)";
  private static String SSLMODE = "Invalid sslmode value: (allow|prefer)";
  private static String ANY = ".*";
  private static String VALIDATOR =
      "SSL error: sun.security.validator.ValidatorException: PKIX path (building|validation) failed:.*";
  private static String HOSTNAME = "The hostname .* could not be verified.";

  static {
    defaultExpected = new TreeMap<String, Expected>();
    defaultExpected.put("disableGG", new Expected(null, false));
    defaultExpected.put("disableGB", new Expected(null, false));
    defaultExpected.put("disableBG", new Expected(null, false));
    defaultExpected.put("allowGG", new Expected(SSLMODE, true));
    defaultExpected.put("allowGB", new Expected(SSLMODE, true));
    defaultExpected.put("allowBG", new Expected(SSLMODE, true));
    defaultExpected.put("preferGG", new Expected(SSLMODE, true));
    defaultExpected.put("preferGB", new Expected(SSLMODE, true));
    defaultExpected.put("preferBG", new Expected(SSLMODE, true));
    defaultExpected.put("requireGG", new Expected(null, true));
    defaultExpected.put("requireGB", new Expected(null, true));
    defaultExpected.put("requireBG", new Expected(null, true));
    defaultExpected.put("verify-caGG", new Expected(null, true));
    defaultExpected.put("verify-caGB", new Expected(ANY, true));
    defaultExpected.put("verify-caBG", new Expected(null, true));
    defaultExpected.put("verify-fullGG", new Expected(null, true));
    defaultExpected.put("verify-fullGB", new Expected(ANY, true));
    defaultExpected.put("verify-fullBG", new Expected(null, true));

    TreeMap<String, Expected> work = (TreeMap) defaultExpected.clone();
    work.put("disableGG", new Expected(null, false));
    work.put("disableGB", new Expected(null, false));
    work.put("disableBG", new Expected(null, false));
    work.put("allowGG", new Expected(SSLMODE, false));
    work.put("allowGB", new Expected(SSLMODE, false));
    work.put("allowBG", new Expected(SSLMODE, false));
    work.put("preferGG", new Expected(SSLMODE, false));
    work.put("preferGB", new Expected(SSLMODE, false));
    work.put("preferBG", new Expected(SSLMODE, false));
    work.put("requireGG", new Expected(ANY, true));
    work.put("requireGB", new Expected(ANY, true));
    work.put("requireBG", new Expected(ANY, true));
    work.put("verify-caGG", new Expected(ANY, true));
    work.put("verify-caGB", new Expected(ANY, true));
    work.put("verify-caBG", new Expected(ANY, true));
    work.put("verify-fullGG", new Expected(ANY, true));
    work.put("verify-fullGB", new Expected(ANY, true));
    work.put("verify-fullBG", new Expected(ANY, true));

    expectedMap = new TreeMap<String, Map<String, Expected>>();
    expectedMap.put("ssloff8", work);

    work = (TreeMap) work.clone();
    expectedMap.put("ssloff9", work);

    work = (TreeMap) defaultExpected.clone();
    work.put("disableGG", new Expected(null, false));
    work.put("disableGB", new Expected(null, false));
    work.put("disableBG", new Expected(null, false));
    work.put("allowGG", new Expected(SSLMODE, false));
    work.put("allowGB", new Expected(SSLMODE, false));
    work.put("allowBG", new Expected(SSLMODE, false));
    work.put("preferGG", new Expected(SSLMODE, false));
    work.put("preferGB", new Expected(SSLMODE, false));
    work.put("preferBG", new Expected(SSLMODE, false));
    work.put("requireGG", new Expected(PG_HBA_ON, true));
    work.put("requireGB", new Expected(PG_HBA_ON, true));
    work.put("requireBG", new Expected(BROKEN, true));
    work.put("verify-caGG", new Expected(PG_HBA_ON, true));
    work.put("verify-caGB", new Expected(VALIDATOR, true));
    work.put("verify-caBG", new Expected(BROKEN, true));
    work.put("verify-fullGG", new Expected(PG_HBA_ON, true));
    work.put("verify-fullGB", new Expected(VALIDATOR, true));
    work.put("verify-fullBG", new Expected(BROKEN, true));
    expectedMap.put("sslhostnossl8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslhostnossl9", work);

    work = (TreeMap) defaultExpected.clone();
    work.put("disableGG", new Expected(null, false));
    work.put("disableGB", new Expected(null, false));
    work.put("disableBG", new Expected(null, false));
    work.put("allowGG", new Expected(SSLMODE, false));
    work.put("allowGB", new Expected(SSLMODE, false));
    work.put("allowBG", new Expected(SSLMODE, false));
    work.put("preferGG", new Expected(SSLMODE, true));
    work.put("preferGB", new Expected(SSLMODE, true));
    work.put("preferBG", new Expected(SSLMODE, false));
    work.put("requireGG", new Expected(null, true));
    work.put("requireGB", new Expected(null, true));
    work.put("requireBG", new Expected(BROKEN, true));
    work.put("verify-caGG", new Expected(null, true));
    work.put("verify-caGB", new Expected(VALIDATOR, true));
    work.put("verify-caBG", new Expected(BROKEN, true));
    work.put("verify-fullGG", new Expected(null, true));
    work.put("verify-fullGB", new Expected(VALIDATOR, true));
    work.put("verify-fullBG", new Expected(BROKEN, true));
    expectedMap.put("sslhostgh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslhostgh9", work);

    work = (TreeMap) work.clone();
    work.put("disableGG", new Expected(PG_HBA_OFF, false));
    work.put("disableGB", new Expected(PG_HBA_OFF, false));
    work.put("disableBG", new Expected(PG_HBA_OFF, false));
    work.put("allowGG", new Expected(SSLMODE, true));
    work.put("allowGB", new Expected(SSLMODE, true));
    work.put("allowBG", new Expected(SSLMODE, true));
    work.put("preferBG", new Expected(SSLMODE, false));
    expectedMap.put("sslhostsslgh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslhostsslgh9", work);

    work = (TreeMap) defaultExpected.clone();
    work.put("disableGG", new Expected(null, false));
    work.put("disableGB", new Expected(null, false));
    work.put("disableBG", new Expected(null, false));
    work.put("allowGG", new Expected(SSLMODE, false));
    work.put("allowGB", new Expected(SSLMODE, false));
    work.put("allowBG", new Expected(SSLMODE, false));
    work.put("preferGG", new Expected(SSLMODE, true));
    work.put("preferGB", new Expected(SSLMODE, true));
    work.put("preferBG", new Expected(SSLMODE, false));
    work.put("requireGG", new Expected(null, true));
    work.put("requireGB", new Expected(null, true));
    work.put("requireBG", new Expected(BROKEN, true));
    work.put("verify-caGG", new Expected(null, true));
    work.put("verify-caGB", new Expected(VALIDATOR, true));
    work.put("verify-caBG", new Expected(BROKEN, true));
    work.put("verify-fullGG", new Expected(HOSTNAME, true));
    work.put("verify-fullGB", new Expected(VALIDATOR, true));
    work.put("verify-fullBG", new Expected(BROKEN, true));
    expectedMap.put("sslhostbh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslhostbh9", work);

    work = (TreeMap) work.clone();
    work.put("disableGG", new Expected(PG_HBA_OFF, false));
    work.put("disableGB", new Expected(PG_HBA_OFF, false));
    work.put("disableBG", new Expected(PG_HBA_OFF, false));
    work.put("allowGG", new Expected(SSLMODE, true));
    work.put("allowGB", new Expected(SSLMODE, true));
    work.put("allowBG", new Expected(SSLMODE, true));
    work.put("preferBG", new Expected(SSLMODE, false));
    expectedMap.put("sslhostsslbh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslhostsslbh9", work);

    work = (TreeMap) defaultExpected.clone();
    work.put("disableGG", new Expected(PG_HBA_OFF, false));
    work.put("disableGB", new Expected(PG_HBA_OFF, false));
    work.put("disableBG", new Expected(PG_HBA_OFF, false));
    work.put("allowGG", new Expected(SSLMODE, true));
    work.put("allowGB", new Expected(SSLMODE, true));
    work.put("allowBG", new Expected(SSLMODE, true));
    work.put("preferGG", new Expected(SSLMODE, true));
    work.put("preferGB", new Expected(SSLMODE, true));
    work.put("preferBG", new Expected(SSLMODE, true));
    work.put("requireGG", new Expected(null, true));
    work.put("requireGB", new Expected(null, true));
    work.put("requireBG", new Expected(BROKEN, true));
    work.put("verify-caGG", new Expected(null, true));
    work.put("verify-caGB", new Expected(VALIDATOR, true));
    work.put("verify-caBG", new Expected(BROKEN, true));
    work.put("verify-fullGG", new Expected(null, true));
    work.put("verify-fullGB", new Expected(VALIDATOR, true));
    work.put("verify-fullBG", new Expected(BROKEN, true));
    expectedMap.put("sslhostsslcertgh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslhostsslcertgh9", work);

    work = (TreeMap) defaultExpected.clone();
    work.put("disableGG", new Expected(PG_HBA_OFF, false));
    work.put("disableGB", new Expected(PG_HBA_OFF, false));
    work.put("disableBG", new Expected(PG_HBA_OFF, false));
    work.put("allowGG", new Expected(SSLMODE, true));
    work.put("allowGB", new Expected(SSLMODE, true));
    work.put("allowBG", new Expected(SSLMODE, true));
    work.put("preferGG", new Expected(SSLMODE, true));
    work.put("preferGB", new Expected(SSLMODE, true));
    work.put("preferBG", new Expected(SSLMODE, true));
    work.put("requireGG", new Expected(null, true));
    work.put("requireGB", new Expected(null, true));
    work.put("requireBG", new Expected(BROKEN, true));
    work.put("verify-caGG", new Expected(null, true));
    work.put("verify-caGB", new Expected(VALIDATOR, true));
    work.put("verify-caBG", new Expected(BROKEN, true));
    work.put("verify-fullGG", new Expected(HOSTNAME, true));
    work.put("verify-fullGB", new Expected(VALIDATOR, true));
    work.put("verify-fullBG", new Expected(BROKEN, true));
    expectedMap.put("sslhostsslcertbh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslhostsslcertbh9", work);

    work = (TreeMap) defaultExpected.clone();
    work.put("disableGG", new Expected(PG_HBA_OFF, false));
    work.put("disableGB", new Expected(PG_HBA_OFF, false));
    work.put("disableBG", new Expected(PG_HBA_OFF, false));
    work.put("allowGG", new Expected(SSLMODE, true));
    work.put("allowGB", new Expected(SSLMODE, true));
    work.put("allowBG", new Expected(SSLMODE, true));
    work.put("preferGG", new Expected(SSLMODE, true));
    work.put("preferGB", new Expected(SSLMODE, true));
    work.put("preferBG", new Expected(SSLMODE, true));
    work.put("requireGG", new Expected(null, true));
    work.put("requireGB", new Expected(null, true));
    work.put("requireBG", new Expected(BROKEN, true));
    work.put("verify-caGG", new Expected(null, true));
    work.put("verify-caGB", new Expected(VALIDATOR, true));
    work.put("verify-caBG", new Expected(BROKEN, true));
    work.put("verify-fullGG", new Expected(null, true));
    work.put("verify-fullGB", new Expected(VALIDATOR, true));
    work.put("verify-fullBG", new Expected(BROKEN, true));
    expectedMap.put("sslcertgh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslcertgh9", work);

    work = (TreeMap) work.clone();
    work.put("verify-fullGG", new Expected(HOSTNAME, true));
    expectedMap.put("sslcertbh8", work);
    work = (TreeMap) work.clone();
    expectedMap.put("sslcertbh9", work);
  }

  private static class Expected {
    private final String exceptionMessageRegex;
    private final boolean useSsl;

    /**
     *
     * @param exceptionMessageRegex the expected message regex of PSQLException or null,
     *                         if no exception is expected
     * @param useSsl weather ssl is to be used
     */
    Expected(String exceptionMessageRegex, boolean useSsl) {
      this.exceptionMessageRegex = exceptionMessageRegex;
      this.useSsl = useSsl;
    }
  }

}
