/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.sql.ResultSet;
import java.util.Properties;

public class PKCS12KeyTest extends BaseTest4 {
  @Override
  @Before
  public void setUp() throws Exception {

    super.setUp();
  }

  @Override
  protected void updateProperties(Properties props) {
    Properties prop = TestUtil.loadPropertyFiles("ssltest.properties");
    props.put(TestUtil.DATABASE_PROP, "hostssldb");
    PGProperty.SSL_MODE.set(props, "prefer");
    String enableSslTests = prop.getProperty("enable_ssl_tests");
    Assume.assumeTrue(
        "Skipping the test as enable_ssl_tests is not set",
        Boolean.parseBoolean(enableSslTests)
    );

    File certDirFile = TestUtil.getFile(prop.getProperty("certdir"));
    String certdir = certDirFile.getAbsolutePath();

    PGProperty.SSL_KEY.set(props, certdir + "/" + "goodclient" + ".p12");

  }

  @Test
  public void TestGoodClientP12() throws Exception {

    ResultSet rs = con.createStatement().executeQuery("select ssl_is_used()");
    Assert.assertTrue("select ssl_is_used() should return a row", rs.next());
    boolean sslUsed = rs.getBoolean(1);
  }
}
