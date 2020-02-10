/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import java.io.InputStream;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public abstract class DbKeyStoreSocketFactory extends org.postgresql.ssl.WrappedFactory {
  /*
   * Populate the WrappedFactory member factory with an SSL Socket Factory that uses the JKS
   * keystore provided by getKeyStorePassword() and getKeyStoreStream(). A subclass only needs to
   * implement these two methods. The key store will be used both for selecting a private key
   * certificate to send to the server, as well as checking the server's certificate against a set
   * of trusted CAs.
   */
  public DbKeyStoreSocketFactory() throws DbKeyStoreSocketException {
    KeyStore keys;
    char[] password;
    try {
      keys = KeyStore.getInstance("JKS");
      password = getKeyStorePassword();
      keys.load(getKeyStoreStream(), password);
    } catch (java.security.GeneralSecurityException gse) {
      throw new DbKeyStoreSocketException("Failed to load keystore: " + gse.getMessage());
    } catch (java.io.FileNotFoundException fnfe) {
      throw new DbKeyStoreSocketException("Failed to find keystore file." + fnfe.getMessage());
    } catch (java.io.IOException ioe) {
      throw new DbKeyStoreSocketException("Failed to read keystore file: " + ioe.getMessage());
    }
    try {
      KeyManagerFactory keyfact =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      keyfact.init(keys, password);

      TrustManagerFactory trustfact =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trustfact.init(keys);

      SSLContext ctx = SSLContext.getInstance("SSL");
      ctx.init(keyfact.getKeyManagers(), trustfact.getTrustManagers(), null);
      factory = ctx.getSocketFactory();
    } catch (java.security.GeneralSecurityException gse) {
      throw new DbKeyStoreSocketException(
          "Failed to set up database socket factory: " + gse.getMessage());
    }
  }

  public abstract char[] getKeyStorePassword();

  public abstract InputStream getKeyStoreStream();

  public static class DbKeyStoreSocketException extends Exception {
    public DbKeyStoreSocketException(String message) {
      super(message);
    }
  }
}
