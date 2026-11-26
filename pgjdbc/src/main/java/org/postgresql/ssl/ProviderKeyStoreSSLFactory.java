/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * Socket factory that uses typical "zero configuration" key and trust
 * stores, like the native Windows "SunMSCAPI" or native Apple "Apple"
 * providers.
 */
public abstract class ProviderKeyStoreSSLFactory extends WrappedFactory {

  private static final Logger LOGGER = Logger.getLogger(ProviderKeyStoreSSLFactory.class.getName());

  protected ProviderKeyStoreSSLFactory(Properties info, final String protocol, final String algorithm,
      final String keyStoreProvider, final String keyStoreType,
      final String trustStoreProvider, final String trustStoreType) throws PSQLException {

    LOGGER.log(Level.FINE,
        "Initializing SSL context from key store {0} (provider {1}) and trust store {2} (provider {3})",
        new Object[]{keyStoreType, keyStoreProvider, trustStoreType, trustStoreProvider});

    SSLContext ctx;
    final KeyStore keyStore;
    KeyStore trustStore;
    final char[] keyPassphrase = new char[0];
    final String sslsubject = PGProperty.SSL_SUBJECT.getOrDefault(info);
    final @Nullable X500Principal subject;
    final KeyManagerFactory keyManagerFactory;
    final TrustManagerFactory trustManagerFactory;

    try {
      keyStore = KeyStore.getInstance(keyStoreType, keyStoreProvider);
    } catch (KeyStoreException | NoSuchProviderException ex) {
      throw new PSQLException(GT.tr("SSL keystore {0} not available.", keyStoreType),
          PSQLState.CONNECTION_FAILURE, ex);
    }

    try {
      keyStore.load(null, null);
    } catch (CertificateException | IOException ex) {
      throw new PSQLException(GT.tr("SSL keystore {0} could not be loaded.", keyStoreType),
          PSQLState.CONNECTION_FAILURE, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw noSuchAlgorithm(ex);
    }

    try {
      keyManagerFactory = KeyManagerFactory.getInstance(algorithm);
    } catch (NoSuchAlgorithmException ex) {
      throw noSuchAlgorithm(ex);
    }

    try {
      keyManagerFactory.init(keyStore, keyPassphrase);
    } catch (NoSuchAlgorithmException ex) {
      throw noSuchAlgorithm(ex);
    } catch (KeyStoreException ex) {
      throw new PSQLException(GT.tr("Could not initialize SSL keystore."),
          PSQLState.CONNECTION_FAILURE, ex);
    } catch (UnrecoverableKeyException ex) {
      throw new PSQLException(GT.tr("Could not read SSL key."),
          PSQLState.CONNECTION_FAILURE, ex);
    }

    try {
      trustStore = KeyStore.getInstance(trustStoreType, trustStoreProvider);
    } catch (KeyStoreException ex) {
      // On older JDKs the native trust store may not be available
      // (notably the macOS KeychainStore-ROOT). Fall back to the default
      // cacerts trust store in that case.
      if (ex.getCause() instanceof NoSuchAlgorithmException) {
        LOGGER.log(Level.WARNING,
            "SSL trust store {0} (provider {1}) is not available on this JVM; falling back to the "
            + "default cacerts trust store. Server certificates will be validated against cacerts "
            + "rather than the operating system trust store.",
            new Object[]{trustStoreType, trustStoreProvider});
        trustStore = null;
      } else {
        throw new PSQLException(GT.tr("SSL truststore {0} not available.", trustStoreType),
            PSQLState.CONNECTION_FAILURE, ex);
      }
    } catch (NoSuchProviderException ex) {
      throw new PSQLException(GT.tr("SSL truststore {0} not available.", trustStoreType),
          PSQLState.CONNECTION_FAILURE, ex);
    }

    try {
      if (trustStore != null) {
        trustStore.load(null, null);
      }
    } catch (CertificateException | IOException ex) {
      throw new PSQLException(GT.tr("SSL truststore {0} could not be loaded.", trustStoreType),
          PSQLState.CONNECTION_FAILURE, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw noSuchAlgorithm(ex);
    }

    try {
      trustManagerFactory = TrustManagerFactory.getInstance(algorithm);
    } catch (NoSuchAlgorithmException ex) {
      throw noSuchAlgorithm(ex);
    }

    try {
      trustManagerFactory.init(trustStore);
    } catch (KeyStoreException ex) {
      throw new PSQLException(GT.tr("Could not initialize SSL truststore."),
          PSQLState.CONNECTION_FAILURE, ex);
    }

    try {
      ctx = SSLContext.getInstance(protocol);
    } catch (NoSuchAlgorithmException ex) {
      throw noSuchAlgorithm(ex);
    }

    try {
      if (sslsubject != null && sslsubject.length() != 0) {
        subject = new X500Principal(sslsubject);
      } else {
        subject = null;
      }
    } catch (IllegalArgumentException ex) {
      throw new PSQLException(GT.tr("Could not parse sslsubject {0}: {1}.",
          sslsubject, ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
    }

    KeyManager[] keyManagers = keyManagerFactory.getKeyManagers();
    if (keyManagers.length == 0) {
      throw new PSQLException(GT.tr("SSL keystore {0} produced no key managers.", keyStoreType),
          PSQLState.CONNECTION_FAILURE);
    }
    KeyManager km = keyManagers[0];

    if (subject != null) {
      if (!(km instanceof X509KeyManager)) {
        throw new PSQLException(
            GT.tr("sslsubject is set, but SSL keystore {0} did not provide an X509KeyManager.",
                keyStoreType), PSQLState.CONNECTION_FAILURE);
      }
      km = new SubjectKeyManager((X509KeyManager) km, subject);
    }

    try {
      ctx.init(new KeyManager[]{ km }, null, null);
    } catch (KeyManagementException ex) {
      throw new PSQLException(GT.tr("Could not initialize SSL keystore/truststore."),
          PSQLState.CONNECTION_FAILURE, ex);
    }

    factory = ctx.getSocketFactory();
  }

  private static PSQLException noSuchAlgorithm(NoSuchAlgorithmException ex) {
    return new PSQLException(GT.tr("Could not find a Java cryptographic algorithm: {0}.",
        ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
  }
}
