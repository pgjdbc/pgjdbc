/*
 * Copyright (c) 2019, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509KeyManager;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.x500.X500Principal;

public class PKCS12KeyManager implements X509KeyManager {

  private final CallbackHandler cbh;
  private @Nullable PSQLException error;
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

  /**
   * getCertificateChain and getPrivateKey cannot throw exceptions, therefore any exception is stored
   * in {@link #error} and can be raised by this method.
   *
   * @throws PSQLException if any exception is stored in {@link #error} and can be raised
   */
  public void throwKeyManagerException() throws PSQLException {
    if (error != null) {
      throw error;
    }
  }

  @Override
  public String @Nullable [] getClientAliases(String keyType, Principal @Nullable [] principals) {
    String alias = chooseClientAlias(new String[]{keyType}, principals, (Socket) null);
    return alias == null ? null : new String[]{alias};
  }

  @Override
  public @Nullable String chooseClientAlias(String[] keyType, Principal @Nullable [] principals,
      @Nullable Socket socket) {
    if (principals == null || principals.length == 0) {
      // Postgres 8.4 and earlier do not send the list of accepted certificate authorities
      // to the client. See BUG #5468. We only hope, that our certificate will be accepted.
      return "user";
    } else {
      // Sending a wrong certificate makes the connection rejected, even, if clientcert=0 in
      // pg_hba.conf.
      // therefore we only send our certificate, if the issuer is listed in issuers
      X509Certificate[] certchain = getCertificateChain("user");
      if (certchain == null) {
        return null;
      } else {
        X509Certificate cert = certchain[certchain.length - 1];
        X500Principal ourissuer = cert.getIssuerX500Principal();
        String certKeyType = cert.getPublicKey().getAlgorithm();
        boolean keyTypeFound = false;
        boolean found = false;
        if (keyType != null && keyType.length > 0) {
          for (String kt : keyType) {
            if (kt.equalsIgnoreCase(certKeyType)) {
              keyTypeFound = true;
            }
          }
        } else {
          // If no key types were passed in, assume we don't care
          // about checking that the cert uses a particular key type.
          keyTypeFound = true;
        }
        if (keyTypeFound) {
          for (Principal issuer : principals) {
            if (ourissuer.equals(issuer)) {
              found = keyTypeFound;
            }
          }
        }
        return found ? "user" : null;
      }
    }
  }

  @Override
  public String @Nullable [] getServerAliases(String s, Principal @Nullable [] principals) {
    return new String[]{};
  }

  @Override
  public @Nullable String chooseServerAlias(String s, Principal @Nullable [] principals,
      @Nullable Socket socket) {
    // we are not a server
    return null;
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

      keyStore.load(new BufferedInputStream(new FileInputStream(keyfile)), pwdcb.getPassword());
      keystoreLoaded = true;
    }
  }

}
