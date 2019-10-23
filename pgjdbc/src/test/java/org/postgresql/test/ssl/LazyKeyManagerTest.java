/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.ssl.LazyKeyManager;

import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

public class LazyKeyManagerTest {

  @Test
  public void testLoadKey() throws Exception {
    String certdir = "../certdir/";
    String path = new File("./").getAbsolutePath();
    LazyKeyManager lazyKeyManager = new LazyKeyManager(certdir + "goodclient.crt",
        certdir + "goodclient.pk8", new TestCallbackHandler("sslpwd"), true);
    PrivateKey pk = lazyKeyManager.getPrivateKey("user");
    Assert.assertNotNull(pk);
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
        // It is used instead of cons.readPassword(prompt), because the prompt may contain '%'
        // characters
        //pwdCallback.setPassword(cons.readPassword("%s", pwdCallback.getPrompt()));
      }
    }
  }
}
