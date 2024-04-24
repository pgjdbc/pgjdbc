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
public class SSLFactory extends WrappedFactory {

  protected SSLFactory(Properties info, final String protocol, final String algorithm, final String keyStoreProvider, final String keyStoreType, final String trustStoreProvider, final String trustStoreType) throws PSQLException {

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

    } catch (KeyStoreException ex) {
      throw new PSQLException(GT.tr("SSL keystore {0} not available.",
                keyStoreType), PSQLState.CONNECTION_FAILURE, ex);
    } catch (NoSuchProviderException ex) {
      throw new PSQLException(GT.tr("SSL keystore {0} not available.",
                keyStoreType), PSQLState.CONNECTION_FAILURE, ex);
    }

    try {

      keyStore.load(null, null);

    } catch (CertificateException ex) {
      throw new PSQLException(GT.tr("SSL keystore {0} could not be loaded.",
                keyStoreType), PSQLState.CONNECTION_FAILURE, ex);
    } catch (IOException ex) {
      throw new PSQLException(GT.tr("SSL keystore {0} could not be loaded.",
                keyStoreType), PSQLState.CONNECTION_FAILURE, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new PSQLException(GT.tr("Could not find a Java cryptographic algorithm: {0}.",
                ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
    }

    try {

      keyManagerFactory = KeyManagerFactory
              .getInstance(algorithm);

    } catch (NoSuchAlgorithmException ex) {
      throw new PSQLException(GT.tr("Could not find a Java cryptographic algorithm: {0}.",
                ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
    }

    try {

      keyManagerFactory.init(keyStore, keyPassphrase);

    } catch (NoSuchAlgorithmException ex) {
      throw new PSQLException(GT.tr("Could not find a Java cryptographic algorithm: {0}.",
                ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
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
      // On the Mac, the truststore is new and won't be available
      // on old versions of the JDK. In these cases fall back to
      // the default truststore in cacerts.
      if (ex.getCause() instanceof NoSuchAlgorithmException) {
        trustStore = null;
      } else {
        throw new PSQLException(GT.tr("SSL truststore {0} not available.",
                    trustStoreType), PSQLState.CONNECTION_FAILURE, ex);
      }
    } catch (NoSuchProviderException ex) {
      throw new PSQLException(GT.tr("SSL truststore {0} not available.",
                trustStoreType), PSQLState.CONNECTION_FAILURE, ex);
    }

    try {

      if (trustStore != null) {
        trustStore.load(null, null);
      }

    } catch (CertificateException ex) {
      throw new PSQLException(GT.tr("SSL truststore {0} could not be loaded.",
                trustStoreType), PSQLState.CONNECTION_FAILURE, ex);
    } catch (IOException ex) {
      throw new PSQLException(GT.tr("SSL truststore {0} could not be loaded.",
                trustStoreType), PSQLState.CONNECTION_FAILURE, ex);
    } catch (NoSuchAlgorithmException ex) {
      throw new PSQLException(GT.tr("Could not find a Java cryptographic algorithm: {0}.",
                ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
    }

    try {

      trustManagerFactory = TrustManagerFactory
              .getInstance(algorithm);

    } catch (NoSuchAlgorithmException ex) {
      throw new PSQLException(GT.tr("Could not find a Java cryptographic algorithm: {0}.",
                ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
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
      throw new PSQLException(GT.tr("Could not find a Java cryptographic algorithm: {0}.",
                ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
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

    try {

      KeyManager km = keyManagerFactory.getKeyManagers()[0];

      if (subject != null) {
        km = new SubjectKeyManager(X509KeyManager.class.cast(km), subject);
      }

      ctx.init(new KeyManager[] { km }, null, null);

    } catch (KeyManagementException ex) {
      throw new PSQLException(GT.tr("Could not initialize SSL keystore/truststore."),
                PSQLState.CONNECTION_FAILURE, ex);
    }

    factory = ctx.getSocketFactory();

  }
}
