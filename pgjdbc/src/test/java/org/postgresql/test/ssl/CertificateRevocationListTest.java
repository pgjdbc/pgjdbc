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
    File certDirFile = TestUtil.getFile("certdir");
    String certdir = certDirFile.getAbsolutePath();
    Properties props = new Properties();
    props.put(TestUtil.SERVER_HOST_PORT_PROP, "localhost" + ":" + TestUtil.getPort());
    props.put(TestUtil.DATABASE_PROP, "hostssldb");
    PGProperty.SSL_MODE.set(props, SslMode.VERIFY_CA.value);
    PGProperty.SSL_CERT.set(props, certdir + "/" + "revoked.crt");
    PGProperty.SSL_KEY.set(props, certdir + "/" + "revoked.pk8");
    PGProperty.SSL_ROOT_CERT.set(props, certdir + "/" + "goodroot.crt");
    PGProperty.SSL_CRL_FILE.set(props, certdir + "/" + crlFileName);
    return props;
  }

  @Test
  public void revokedClientCert() throws Exception {
    Properties props = getCrlProps("root.crl");
    try (Connection con = TestUtil.openDB(props)) {
      Assert.fail("Should throw an exception");
    } catch (PSQLException ex) {
      String message = ex.getMessage();
      if (message == null || !message.matches("^SSL certificate with serial number .* revoked")) {
        Assert.fail("Expected SSL certificate revocation exception; actual message: " + message);
      }
    }
  }
}
