/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.ssl.PEMKeyManager;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.Properties;

import javax.security.auth.x500.X500Principal;

public class PEMKeyManagerTest {

  @Test
  void TestChooseClientAlias() {
    String sslKeyFile = TestUtil.getSslTestCertPath("goodclient.key");
    String sslCertFile = TestUtil.getSslTestCertPath("goodclient.crt");
    PEMKeyManager pemKeyManager = new PEMKeyManager(sslKeyFile, sslCertFile, "RSA");

    X500Principal testPrincipal = new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US");
    X500Principal[] issuers = new X500Principal[]{testPrincipal};

    String validKeyType = pemKeyManager.chooseClientAlias(new String[]{"RSA"}, issuers, null);
    assertNotNull(validKeyType);

    String ignoresCase = pemKeyManager.chooseClientAlias(new String[]{"rsa"}, issuers, null);
    assertNotNull(ignoresCase);

    String invalidKeyType = pemKeyManager.chooseClientAlias(new String[]{"EC"}, issuers, null);
    assertNull(invalidKeyType);

    String containsValidKeyType = pemKeyManager.chooseClientAlias(new String[]{"EC", "RSA"}, issuers, null);
    assertNotNull(containsValidKeyType);

    String ignoresBlank = pemKeyManager.chooseClientAlias(new String[]{}, issuers, null);
    assertNotNull(ignoresBlank);
  }

  @Test
  void TestGoodClientPEM() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.SSL_MODE.set(props, "prefer");
    PGProperty.SSL_KEY.set(props, TestUtil.getSslTestCertPath("goodclient.key"));
    PGProperty.SSL_CERT.set(props, TestUtil.getSslTestCertPath("goodclient.crt"));
    PGProperty.PEM_KEY_ALGORITHM.set(props, "RSA");

    try (Connection conn = TestUtil.openDB(props)) {
      boolean sslUsed = TestUtil.queryForBoolean(conn, "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()");
      assertTrue(sslUsed, "SSL should be in use");
    }
  }
}
