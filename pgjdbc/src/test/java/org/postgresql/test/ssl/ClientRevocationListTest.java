/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.SslMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.sql.Connection;

import java.util.Properties;

public class ClientRevocationListTest {


  @Test
  public void testRevokedClientCert() throws Exception {

    File certDirFile = TestUtil.getFile("certdir");
    String certdir = certDirFile.getAbsolutePath();

    Connection con = null;
    Properties props = new Properties();
    props.put(TestUtil.SERVER_HOST_PORT_PROP, "localhost" + ":" + TestUtil.getPort());
    props.put(TestUtil.DATABASE_PROP, "hostssldb");

    PGProperty.SSL_MODE.set(props, SslMode.VERIFY_CA.value);
    PGProperty.SSL_CERT.set(props,
        certdir + "/" + "revoked.crt");
    PGProperty.SSL_KEY.set(props,
        certdir + "/" + "revoked.pk8");
    PGProperty.SSL_ROOT_CERT.set(props,
        certdir + "/" + "goodroot.crt");
    PGProperty.SSL_CRL_FILE.set(props, certdir + "/" + "root.crl");

    try {
      con = TestUtil.openDB(props);
      Assert.fail("Should throw an exception");
    } catch (PSQLException ex ) {
      Assert.assertNotEquals("SSL certificate " + certdir + "/revoked.crt revoked", ex.getMessage());
    }
    TestUtil.closeQuietly(con);
  }

  /**
   * Helper method to create a connection using the additional properties specified in the "info"
   * paramater.
   *
   * @param info The additional properties to use when creating a connection
   */
  protected Connection getConnection(Properties info) throws Exception {

    return TestUtil.openDB(info);
  }

  protected String getUsername() {
    return System.getProperty("username");
  }

  protected String getPassword() {
    return System.getProperty("password");
  }



}
