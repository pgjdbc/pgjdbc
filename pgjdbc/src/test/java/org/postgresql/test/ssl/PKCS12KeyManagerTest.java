/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.postgresql.ssl.PKCS12KeyManager;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.x500.X500Principal;

class PKCS12KeyManagerTest {

  @Test
  void chooseClientAlias() throws Exception {
    PKCS12KeyManager pkcs12KeyManager = new PKCS12KeyManager(
        TestUtil.getSslTestCertPath("goodclient.p12"),
        new TestCallbackHandler("sslpwd"));
    X500Principal testPrincipal = new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US");
    X500Principal[] issuers = new X500Principal[]{testPrincipal};

    String validKeyType = pkcs12KeyManager.chooseClientAlias(new String[]{"RSA"}, issuers, null);
    assertNotNull(validKeyType);

    String ignoresCase = pkcs12KeyManager.chooseClientAlias(new String[]{"rsa"}, issuers, null);
    assertNotNull(ignoresCase);

    String invalidKeyType = pkcs12KeyManager.chooseClientAlias(new String[]{"EC"}, issuers, null);
    assertNull(invalidKeyType);

    String containsValidKeyType = pkcs12KeyManager.chooseClientAlias(new String[]{"EC", "RSA"}, issuers, null);
    assertNotNull(containsValidKeyType);

    String ignoresBlank = pkcs12KeyManager.chooseClientAlias(new String[]{}, issuers, null);
    assertNotNull(ignoresBlank);

    PrivateKey pk = pkcs12KeyManager.getPrivateKey("user");
    assertNotNull(pk);

    X509Certificate[] chain = pkcs12KeyManager.getCertificateChain("user");
    assertNotNull(chain);

  }

  public static class TestCallbackHandler implements CallbackHandler {
    char [] password;

    TestCallbackHandler(String password) {
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
        // It is used instead of cons.readPassword(prompt), because the prompt may contain '%'
        // characters
        //pwdCallback.setPassword(cons.readPassword("%s", pwdCallback.getPrompt()));
      }
    }
  }
}
