/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import org.postgresql.PGProperty;
import org.postgresql.ssl.LibPQFactory;
import org.postgresql.ssl.SingleCertValidatingFactory.SingleCertTrustManager;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Properties;

/**
 * Regression test for <a href="https://github.com/pgjdbc/pgjdbc/issues/4191">#4191</a>: a FIPS-mode
 * JVM (Semeru FIPS 140-3) exposes no general-purpose KeyStore type -- {@code "jks"} and the default
 * {@code "pkcs12"} both throw -- so the SSL factories must build their PKIX trust anchors without
 * any {@link KeyStore} at all.
 *
 * <p>Each test makes <em>every</em> {@code KeyStore.getInstance(...)} call fail through a
 * thread-scoped static mock, which is stricter than real FIPS, and asserts the factory still works.
 */
class LibPQFactoryFipsTest {

  @Test
  void libpqFactoryBuildsTruststoreWithoutKeyStore() {
    Properties info = new Properties();
    PGProperty.SSL_MODE.set(info, "verify-ca");
    PGProperty.SSL_ROOT_CERT.set(info, "../certdir/goodroot.crt");

    try (MockedStatic<KeyStore> ks = mockStatic(KeyStore.class, CALLS_REAL_METHODS)) {
      ks.when(() -> KeyStore.getInstance(anyString()))
          .thenThrow(new KeyStoreException("FIPS: no general-purpose KeyStore"));

      assertDoesNotThrow(() -> new LibPQFactory(info));
    }
  }

  @Test
  void singleCertTrustManagerBuildsWithoutKeyStore() {
    try (MockedStatic<KeyStore> ks = mockStatic(KeyStore.class, CALLS_REAL_METHODS)) {
      ks.when(() -> KeyStore.getInstance(anyString()))
          .thenThrow(new KeyStoreException("FIPS: no general-purpose KeyStore"));

      assertDoesNotThrow(() -> {
        try (InputStream in = Files.newInputStream(Paths.get("../certdir/goodroot.crt"))) {
          new SingleCertTrustManager(in);
        }
      });
    }
  }
}
