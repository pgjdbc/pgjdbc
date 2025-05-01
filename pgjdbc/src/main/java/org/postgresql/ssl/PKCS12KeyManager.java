/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.internal.FileUtils;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

public class PKCS12KeyManager extends BaseX509KeyManager {

  private final CallbackHandler cbh;
  private final String keyfile;
  private final KeyStore keyStore;
  boolean keystoreLoaded;
  private final ResourceLock lock = new ResourceLock();

  public PKCS12KeyManager(String pkcsFile, CallbackHandler cbh) throws PSQLException {
    try {
      keyStore = KeyStore.getInstance("pkcs12");
      keyfile = pkcsFile;
      this.cbh = cbh;
    } catch ( KeyStoreException kse ) {
      throw new PSQLException(GT.tr(
        "Unable to find pkcs12 keystore."),
        PSQLState.CONNECTION_FAILURE, kse);
    }
  }

  @Override
  public X509Certificate @Nullable [] getCertificateChain(String alias) {
    try {
      loadKeyStore();
      Certificate[] certs = keyStore.getCertificateChain(alias);
      if (certs == null) {
        return null;
      }
      X509Certificate[] x509Certificates = new X509Certificate[certs.length];
      int i = 0;
      for (Certificate cert : certs) {
        x509Certificates[i++] = (X509Certificate) cert;
      }
      return x509Certificates;
    } catch (Exception kse) {
      error = new PSQLException(GT.tr(
        "Could not find a java cryptographic algorithm: X.509 CertificateFactory not available."),
        PSQLState.CONNECTION_FAILURE, kse);
    }
    return null;
  }

  @Override
  public @Nullable PrivateKey getPrivateKey(String s) {
    try {
      loadKeyStore();
      PasswordCallback pwdcb = new PasswordCallback(GT.tr("Enter SSL password: "), false);
      cbh.handle(new Callback[]{pwdcb});

      KeyStore.ProtectionParameter protParam = new KeyStore.PasswordProtection(pwdcb.getPassword());
      KeyStore.PrivateKeyEntry pkEntry =
          (KeyStore.PrivateKeyEntry) keyStore.getEntry("user", protParam);
      if (pkEntry == null) {
        return null;
      }
      return pkEntry.getPrivateKey();
    } catch (Exception ioex ) {
      error = new PSQLException(GT.tr("Could not read SSL key file {0}.", keyfile),
        PSQLState.CONNECTION_FAILURE, ioex);
    }
    return null;
  }

  private void loadKeyStore() throws Exception {
    try (ResourceLock ignore = lock.obtain()) {
      if (keystoreLoaded) {
        return;
      }
      // We call back for the password
      PasswordCallback pwdcb = new PasswordCallback(GT.tr("Enter SSL password: "), false);
      try {
        cbh.handle(new Callback[]{pwdcb});
      } catch (UnsupportedCallbackException ucex) {
        if ((cbh instanceof LibPQFactory.ConsoleCallbackHandler)
            && ("Console is not available".equals(ucex.getMessage()))) {
          error = new PSQLException(GT
              .tr("Could not read password for SSL key file, console is not available."),
              PSQLState.CONNECTION_FAILURE, ucex);
        } else {
          error =
              new PSQLException(
                  GT.tr("Could not read password for SSL key file by callbackhandler {0}.",
                      cbh.getClass().getName()),
                  PSQLState.CONNECTION_FAILURE, ucex);
        }

      }

      keyStore.load(FileUtils.newBufferedInputStream(keyfile), pwdcb.getPassword());
      keystoreLoaded = true;
    }
  }

}
