/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.PGProperty;
import org.postgresql.ssl.PKCS12KeyManager;
import org.postgresql.test.TestUtil;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.sql.Connection;
import java.util.Properties;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.x500.X500Principal;

public class PKCS12KeyTest {
  @Test
  public void TestGoodClientP12() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    props.put(TestUtil.DATABASE_PROP, "hostssldb");
    PGProperty.SSL_MODE.set(props, "prefer");
    PGProperty.SSL_KEY.set(props, TestUtil.getSslTestCertPath("goodclient.p12"));

    try (Connection conn = TestUtil.openDB(props)) {
      boolean sslUsed = TestUtil.queryForBoolean(conn, "SELECT ssl_is_used()");
      Assert.assertTrue("SSL should be in use", sslUsed);
    }
  }

  @Test
  public void TestChooseClientAlias() throws Exception {
    PKCS12KeyManager pkcs12KeyManager = new PKCS12KeyManager(TestUtil.getSslTestCertPath("goodclient.p12"), new TestCallbackHandler("sslpwd"));
    X500Principal testPrincipal = new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US");
    X500Principal[] issuers = new X500Principal[]{testPrincipal};

    String validKeyType = pkcs12KeyManager.chooseClientAlias(new String[]{"RSA"}, issuers, null);
    Assert.assertNotNull(validKeyType);

    String ignoresCase = pkcs12KeyManager.chooseClientAlias(new String[]{"rsa"}, issuers, null);
    Assert.assertNotNull(ignoresCase);

    String invalidKeyType = pkcs12KeyManager.chooseClientAlias(new String[]{"EC"}, issuers, null);
    Assert.assertNull(invalidKeyType);

    String containsValidKeyType = pkcs12KeyManager.chooseClientAlias(new String[]{"EC","RSA"}, issuers, null);
    Assert.assertNotNull(containsValidKeyType);

    String ignoresBlank = pkcs12KeyManager.chooseClientAlias(new String[]{}, issuers, null);
    Assert.assertNotNull(ignoresBlank);
  }

  public static class TestCallbackHandler implements CallbackHandler {
    char [] password;

    public TestCallbackHandler(String password) {
      if (password != null) {
        this.password = password.toCharArray();
      }
    }

    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
      for (Callback callback : callbacks) {
        if (!(callback instanceof PasswordCallback)) {
          throw new UnsupportedCallbackException(callback);
        }
        PasswordCallback pwdCallback = (PasswordCallback) callback;
        if (password != null) {
          pwdCallback.setPassword(password);
          continue;
        }
      }
    }
  }
}
