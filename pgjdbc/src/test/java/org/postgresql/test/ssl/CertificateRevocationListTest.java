/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import java.io.File;
import java.sql.Connection;
import java.util.Properties;

import org.junit.Assert;
import org.junit.Test;
import org.postgresql.PGProperty;
import org.postgresql.jdbc.SslMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

public class CertificateRevocationListTest {
  private static Properties getCrlProps(String crlFileName) {
    File certDir = TestUtil.getFile("certdir/ssl");
    Properties props = new Properties();
    props.put(TestUtil.SERVER_HOST_PORT_PROP, "localhost" + ":" + TestUtil.getPort());
    props.put(TestUtil.DATABASE_PROP, "hostssldb");
    PGProperty.SSL_MODE.set(props, SslMode.VERIFY_CA.value);
    PGProperty.SSL_CERT.set(props, new File(certDir, "client-revoked.crt").getAbsolutePath());
    PGProperty.SSL_KEY.set(props, new File(certDir, "client_ca.pk8").getAbsolutePath());
    PGProperty.SSL_ROOT_CERT.set(props, new File(certDir, "client_ca.crt").getAbsolutePath());
    PGProperty.SSL_CRL_FILE.set(props, new File(certDir, crlFileName).getAbsolutePath());
    return props;
  }

  @Test
  public void revokedClientCert() throws Exception {
    Properties props = getCrlProps("client.crl");
    try (Connection con = TestUtil.openDB(props)) {
      Assert.fail("Should throw an exception");
    } catch (PSQLException ex) {
      String message = ex.getMessage();
      if (message == null || !message.matches("^SSL certificate with serial number .* revoked")) {
        Assert.fail("Expected SSL certificate revocation exception; actual message: " + message);
      }
    }
  }

  @Test
  public void missingCrlFile() throws Exception {
    // Purposefully use a CRL file that does not exist
    Properties props = getCrlProps("bad-root.crl");
    try (Connection con = TestUtil.openDB(props)) {
      Assert.fail("Should throw an exception");
    } catch (PSQLException ex) {
      String message = ex.getMessage();
      if (message == null || !message.matches("^SSL certificate revocation list file .* could not be read")) {
        Assert.fail("Expected SSL CRL file missing exception; actual message: " + message);
      }
    }
  }
}
