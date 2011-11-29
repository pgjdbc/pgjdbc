package org.postgresql.test.ssl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import junit.framework.TestCase;
import junit.framework.TestSuite;

import org.postgresql.test.TestUtil;

public class SslTest extends TestCase {

  /**
   * Tries to connect to the database.
   * @param connstr Connection string for the database
   * @param expected Expected values. the first element is a String holding the expected message of PSQLException
   * or null, if no exception is expected, the second indicates weather ssl is to be used (Boolean)
   * @throws SQLException
   */
  protected void driver(String connstr, Object[] expected) throws SQLException
  {
    Connection conn = null;
    String exmsg = (String)expected[0];
    try
    {
      conn = DriverManager.getConnection(connstr, TestUtil.getUser(), TestUtil.getPassword());
      if (exmsg!=null)
      {
        fail("Exception did not occur: "+exmsg);
      }
      //
      ResultSet rs = conn.createStatement().executeQuery("select ssl_is_used()");
      assertTrue(rs.next());
      assertEquals("ssl_is_used: ",((Boolean)expected[1]).booleanValue(), rs.getBoolean(1));
      conn.close();
    }
    catch (SQLException ex) {
      if (conn!=null) { conn.close(); }
      if (exmsg==null)
      { //no exception is excepted
        fail("Exception thrown: "+ex.getMessage());
      } else {
        assertTrue("expected: "+exmsg+" actual: "+ex.getMessage(), ex.getMessage().matches(exmsg));
        return;
      }
    }
  }

  protected String certdir;
  protected String connstr;
  protected String sslmode;
  protected int protocol;
  protected boolean goodclient;
  protected boolean goodserver;
  protected String prefix;
  protected Object[] expected;
  
  private String makeConnStr(String sslmode, boolean goodclient, boolean goodserver, int protocol)
  {
    return connstr+"&protocolVersion="+protocol+"&sslmode="+sslmode+
        "&sslcert="+certdir+"/"+prefix+(goodclient?"goodclient.crt":"badclient.crt")+
        "&sslkey="+certdir+"/"+prefix+(goodclient?"goodclient.pk8":"badclient.pk8")+
        "&sslrootcert="+certdir+"/"+prefix+(goodserver?"goodroot.crt":"badroot.crt")+
        "&loglevel="+TestUtil.getLogLevel();
  }

  public SslTest(String name, String certdir, String connstr, String sslmode, int protocol,  boolean goodclient, boolean goodserver, String prefix, Object[] expected)
  {
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

  static TestSuite getSuite(Properties prop, String param)
  {
    String certdir = prop.getProperty("certdir");
    String sconnstr = prop.getProperty(param);
    String sprefix = prop.getProperty(param + "prefix");
    String[] csslmode = {"disable","allow","prefer","require","verify-ca","verify-full"};
    
    TestSuite suite = new TestSuite();
    Map expected = (Map)expectedmap.get(param);
    if (expected == null) {;expected = defaultexpected;}
    int j=0;
    for (int i=0; i<csslmode.length; i++)
    {
      suite.addTest(new SslTest(param + "-"+csslmode[i]+"GG2",certdir,sconnstr,csslmode[i],2,true, true,sprefix,(Object[])expected.get(csslmode[i]+"GG")));
      suite.addTest(new SslTest(param + "-"+csslmode[i]+"GG3",certdir,sconnstr,csslmode[i],3,true, true,sprefix,(Object[])expected.get(csslmode[i]+"GG")));
      suite.addTest(new SslTest(param + "-"+csslmode[i]+"GB2",certdir,sconnstr,csslmode[i],2,true,false,sprefix,(Object[])expected.get(csslmode[i]+"GB")));
      suite.addTest(new SslTest(param + "-"+csslmode[i]+"GB3",certdir,sconnstr,csslmode[i],3,true,false,sprefix,(Object[])expected.get(csslmode[i]+"GB")));
      suite.addTest(new SslTest(param + "-"+csslmode[i]+"BG2",certdir,sconnstr,csslmode[i],2,false,true,sprefix,(Object[])expected.get(csslmode[i]+"BG")));
      suite.addTest(new SslTest(param + "-"+csslmode[i]+"BG3",certdir,sconnstr,csslmode[i],3,false,true,sprefix,(Object[])expected.get(csslmode[i]+"BG")));
    }
    return suite;
  }
  
  protected void runTest() throws Throwable {
    driver(makeConnStr(sslmode, goodclient, goodserver, protocol), expected);
  }

  static Map expectedmap;
  static TreeMap defaultexpected;
  
  //For some strange reason, the v2 driver begins these error messages by "Connection rejected: " but the v3 does not.
  //Also, for v2 there are two spaces after FATAL:, and the message ends with "\n.".
  static String PG_HBA_ON = "(Connection rejected: )?FATAL:  ?no pg_hba.conf entry for host .*, user .*, database .*, SSL on(?s-d:.*)";
  static String PG_HBA_OFF = "(Connection rejected: )?FATAL:  ?no pg_hba.conf entry for host .*, user .*, database .*, SSL off(?s-d:.*)";
  static String FAILED = "The connection attempt failed.";
  static String BROKEN = "SSL error: (Broken pipe|Received fatal alert: unknown_ca|Connection reset)";
  static String SSLMODE = "Invalid sslmode value: (allow|prefer)";
  //static String UNKNOWN = "SSL error: Broken pipe";
  //static String UNKNOWN = "SSL error: Received fatal alert: unknown_ca";
  static String ANY = ".*";
  static String VALIDATOR = "SSL error: sun.security.validator.ValidatorException: PKIX path (building|validation) failed:.*";
  static String HOSTNAME = "The hostname .* could not be verified.";
  static
  {
    defaultexpected = new TreeMap();
    defaultexpected.put("disableGG", new Object[]{null,Boolean.FALSE});
    defaultexpected.put("disableGB", new Object[]{null,Boolean.FALSE});
    defaultexpected.put("disableBG", new Object[]{null,Boolean.FALSE});
    defaultexpected.put("allowGG", new Object[]{SSLMODE,Boolean.TRUE});
    defaultexpected.put("allowGB", new Object[]{SSLMODE,Boolean.TRUE});
    defaultexpected.put("allowBG", new Object[]{SSLMODE,Boolean.TRUE});
    defaultexpected.put("preferGG", new Object[]{SSLMODE,Boolean.TRUE});
    defaultexpected.put("preferGB", new Object[]{SSLMODE,Boolean.TRUE});
    defaultexpected.put("preferBG", new Object[]{SSLMODE,Boolean.TRUE});
    defaultexpected.put("requireGG", new Object[]{null,Boolean.TRUE});
    defaultexpected.put("requireGB", new Object[]{null,Boolean.TRUE});
    defaultexpected.put("requireBG", new Object[]{null,Boolean.TRUE});
    defaultexpected.put("verify-caGG", new Object[]{null,Boolean.TRUE});
    defaultexpected.put("verify-caGB", new Object[]{ANY,Boolean.TRUE});
    defaultexpected.put("verify-caBG", new Object[]{null,Boolean.TRUE});
    defaultexpected.put("verify-fullGG", new Object[]{null,Boolean.TRUE});
    defaultexpected.put("verify-fullGB", new Object[]{ANY,Boolean.TRUE});
    defaultexpected.put("verify-fullBG", new Object[]{null,Boolean.TRUE});
    
    expectedmap = new TreeMap();
    TreeMap work;
    
    work=(TreeMap)defaultexpected.clone();
    work.put("disableGG", new Object[]{null,Boolean.FALSE});
    work.put("disableGB", new Object[]{null,Boolean.FALSE});
    work.put("disableBG", new Object[]{null,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferGG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferGB", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("requireGG", new Object[]{ANY,Boolean.TRUE});
    work.put("requireGB", new Object[]{ANY,Boolean.TRUE});
    work.put("requireBG", new Object[]{ANY,Boolean.TRUE});
    work.put("verify-caGG", new Object[]{ANY,Boolean.TRUE});
    work.put("verify-caGB", new Object[]{ANY,Boolean.TRUE});
    work.put("verify-caBG", new Object[]{ANY,Boolean.TRUE});
    work.put("verify-fullGG", new Object[]{ANY,Boolean.TRUE});
    work.put("verify-fullGB", new Object[]{ANY,Boolean.TRUE});
    work.put("verify-fullBG", new Object[]{ANY,Boolean.TRUE});    
    expectedmap.put("ssloff8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("ssloff9",work);
    
    work=(TreeMap)defaultexpected.clone();
    work.put("disableGG", new Object[]{null,Boolean.FALSE});
    work.put("disableGB", new Object[]{null,Boolean.FALSE});
    work.put("disableBG", new Object[]{null,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferGG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferGB", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("requireGG", new Object[]{PG_HBA_ON,Boolean.TRUE});
    work.put("requireGB", new Object[]{PG_HBA_ON,Boolean.TRUE});
    work.put("requireBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-caGG", new Object[]{PG_HBA_ON,Boolean.TRUE});
    work.put("verify-caGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-caBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-fullGG", new Object[]{PG_HBA_ON,Boolean.TRUE});
    work.put("verify-fullGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-fullBG", new Object[]{BROKEN,Boolean.TRUE});    
    expectedmap.put("sslhostnossl8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslhostnossl9",work);
    
    work=(TreeMap)defaultexpected.clone();
    work.put("disableGG", new Object[]{null,Boolean.FALSE});
    work.put("disableGB", new Object[]{null,Boolean.FALSE});
    work.put("disableBG", new Object[]{null,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("requireGG", new Object[]{null,Boolean.TRUE});
    work.put("requireGB", new Object[]{null,Boolean.TRUE});
    work.put("requireBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-caGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-caGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-caBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-fullGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-fullGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-fullBG", new Object[]{BROKEN,Boolean.TRUE});    
    expectedmap.put("sslhostgh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslhostgh9",work);
    
    work=(TreeMap)work.clone();
    work.put("disableGG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableGB", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableBG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.FALSE});
    expectedmap.put("sslhostsslgh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslhostsslgh9",work);
    
    work=(TreeMap)defaultexpected.clone();
    work.put("disableGG", new Object[]{null,Boolean.FALSE});
    work.put("disableGB", new Object[]{null,Boolean.FALSE});
    work.put("disableBG", new Object[]{null,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("preferGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.FALSE});
    work.put("requireGG", new Object[]{null,Boolean.TRUE});
    work.put("requireGB", new Object[]{null,Boolean.TRUE});
    work.put("requireBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-caGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-caGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-caBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-fullGG", new Object[]{HOSTNAME,Boolean.TRUE});
    work.put("verify-fullGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-fullBG", new Object[]{BROKEN,Boolean.TRUE});    
    expectedmap.put("sslhostbh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslhostbh9",work);
    
    work=(TreeMap)work.clone();
    work.put("disableGG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableGB", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableBG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.TRUE});    
    work.put("preferBG", new Object[]{SSLMODE,Boolean.FALSE});
    expectedmap.put("sslhostsslbh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslhostsslbh9",work);
    
    work=(TreeMap)defaultexpected.clone();
    work.put("disableGG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableGB", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableBG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("requireGG", new Object[]{null,Boolean.TRUE});
    work.put("requireGB", new Object[]{null,Boolean.TRUE});
    work.put("requireBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-caGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-caGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-caBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-fullGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-fullGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-fullBG", new Object[]{BROKEN,Boolean.TRUE});    
    expectedmap.put("sslhostsslcertgh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslhostsslcertgh9",work);
    
    work=(TreeMap)defaultexpected.clone();
    work.put("disableGG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableGB", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableBG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("requireGG", new Object[]{null,Boolean.TRUE});
    work.put("requireGB", new Object[]{null,Boolean.TRUE});
    work.put("requireBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-caGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-caGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-caBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-fullGG", new Object[]{HOSTNAME,Boolean.TRUE});
    work.put("verify-fullGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-fullBG", new Object[]{BROKEN,Boolean.TRUE});    
    expectedmap.put("sslhostsslcertbh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslhostsslcertbh9",work);
    
    work=(TreeMap)defaultexpected.clone();
    work.put("disableGG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableGB", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("disableBG", new Object[]{PG_HBA_OFF,Boolean.FALSE});
    work.put("allowGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("allowBG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferGB", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("preferBG", new Object[]{SSLMODE,Boolean.TRUE});
    work.put("requireGG", new Object[]{null,Boolean.TRUE});
    work.put("requireGB", new Object[]{null,Boolean.TRUE});
    work.put("requireBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-caGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-caGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-caBG", new Object[]{BROKEN,Boolean.TRUE});
    work.put("verify-fullGG", new Object[]{null,Boolean.TRUE});
    work.put("verify-fullGB", new Object[]{VALIDATOR,Boolean.TRUE});
    work.put("verify-fullBG", new Object[]{BROKEN,Boolean.TRUE});    
    expectedmap.put("sslcertgh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslcertgh9",work);
    
    work=(TreeMap)work.clone();
    work.put("verify-fullGG", new Object[]{HOSTNAME,Boolean.TRUE});
    expectedmap.put("sslcertbh8",work);
    work=(TreeMap)work.clone();
    expectedmap.put("sslcertbh9",work);

  }



}
