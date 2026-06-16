/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.postgresql.PGProperty;
import org.postgresql.ssl.LibPQFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.Key;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.KeyStoreSpi;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Properties;

/**
 * Regression test for https://github.com/pgjdbc/pgjdbc/issues/4191: under FIPS-mode JVMs, the
 * legacy {@code "jks"} KeyStore type is unavailable, and the driver must use
 * {@link KeyStore#getDefaultType()} instead.
 *
 * <p>We can't actually run on a FIPS JVM in CI, so we simulate the failure by installing a
 * {@link Provider} that exposes a {@code "jks"} KeyStoreSpi which throws on every operation.
 * With that provider at position 1, any caller that asks for {@code KeyStore.getInstance("jks")}
 * will get a broken store. The driver instead asks for the default type, which still resolves to
 * a working SUN/PKCS12 implementation, so {@link LibPQFactory} constructs successfully.
 */
class LibPQFactoryKeyStoreTypeTest {

  private static final String FAKE_PROVIDER_NAME = "PgJdbcFipsSimulator";

  /**
   * KeyStoreSpi that throws on every operation. Stands in for what a FIPS provider would do if a
   * non-approved {@code jks} type were requested.
   */
  public static class FailingKeyStoreSpi extends KeyStoreSpi {
    @Override
    public Key engineGetKey(String alias, char[] password) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public Certificate[] engineGetCertificateChain(String alias) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public Certificate engineGetCertificate(String alias) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public Date engineGetCreationDate(String alias) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public void engineSetKeyEntry(String alias, Key key, char[] password, Certificate[] chain) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public void engineSetKeyEntry(String alias, byte[] key, Certificate[] chain) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public void engineSetCertificateEntry(String alias, Certificate cert) throws KeyStoreException {
      throw new KeyStoreException("FIPS: jks not allowed");
    }

    @Override
    public void engineDeleteEntry(String alias) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public Enumeration<String> engineAliases() {
      return Collections.emptyEnumeration();
    }

    @Override
    public boolean engineContainsAlias(String alias) {
      return false;
    }

    @Override
    public int engineSize() {
      return 0;
    }

    @Override
    public boolean engineIsKeyEntry(String alias) {
      return false;
    }

    @Override
    public boolean engineIsCertificateEntry(String alias) {
      return false;
    }

    @Override
    public String engineGetCertificateAlias(Certificate cert) {
      return null;
    }

    @Override
    public void engineStore(OutputStream stream, char[] password) {
      throw new UnsupportedOperationException("FIPS: jks not allowed");
    }

    @Override
    public void engineLoad(InputStream stream, char[] password)
        throws IOException, NoSuchAlgorithmException, CertificateException {
      throw new IOException("FIPS: jks not allowed");
    }
  }

  /**
   * Test-only Provider that registers the failing {@code "jks"} KeyStoreSpi. Installed at
   * position 1 so it shadows the real SUN provider's {@code "jks"} entry while preserving every
   * other algorithm and keystore type.
   */
  public static class FipsSimulatorProvider extends Provider {
    // Use the JDK 8-compatible deprecated constructor;
    // Provider(String, String, String) requires JDK 9+.
    @SuppressWarnings("deprecation")
    public FipsSimulatorProvider() {
      super(FAKE_PROVIDER_NAME, 1.0, "Simulates FIPS-mode rejection of jks for tests");
      put("KeyStore.jks", FailingKeyStoreSpi.class.getName());
    }
  }

  private String originalKeyStoreType;

  @BeforeEach
  void installFipsSimulator() {
    // Security.* state is JVM-global. If install partially succeeds, clean up before
    // propagating so a sibling test in the same JVM cannot be shadowed.
    originalKeyStoreType = Security.getProperty("keystore.type");
    boolean clean = false;
    try {
      // Force the JVM default to a working type even on JDK 8 where the default is "jks".
      Security.setProperty("keystore.type", "PKCS12");
      Security.insertProviderAt(new FipsSimulatorProvider(), 1);
      clean = true;
    } finally {
      if (!clean) {
        removeFipsSimulator();
      }
    }
  }

  @AfterEach
  void removeFipsSimulator() {
    Security.removeProvider(FAKE_PROVIDER_NAME);
    if (originalKeyStoreType != null) {
      Security.setProperty("keystore.type", originalKeyStoreType);
    }
  }

  @Test
  void simulatorMakesJksUnusable() throws Exception {
    // Sanity check: with our provider at position 1, asking for "jks" returns the failing SPI.
    KeyStore ks = KeyStore.getInstance("jks");
    assertEquals(FAKE_PROVIDER_NAME, ks.getProvider().getName(),
        "test fixture must shadow the real jks provider");
  }

  @Test
  void defaultKeyStoreTypeBypassesFailingJks() {
    // With keystore.type=PKCS12 the default-type lookup must NOT return our failing jks SPI.
    KeyStore ks = assertDoesNotThrow(() -> KeyStore.getInstance(KeyStore.getDefaultType()));
    assertNotEquals(FAKE_PROVIDER_NAME, ks.getProvider().getName(),
        "default keystore type must resolve to a working provider, not the simulated FIPS one");
  }

  @Test
  void libpqFactoryConstructsUnderSimulatedFips() {
    // The verifyCertificate path inside LibPQFactory creates the in-memory truststore that the
    // bug reports under #4191. With the fix, it asks for the default KeyStore type and skips
    // our failing "jks" SPI; without the fix, this constructor would throw
    // NoSuchAlgorithmException("jks KeyStore not available").
    Properties info = new Properties();
    // sslmode=verify-ca is what makes LibPQFactory hit the truststore branch (#4191's path).
    PGProperty.SSL_MODE.set(info, "verify-ca");
    PGProperty.SSL_ROOT_CERT.set(info, "../certdir/goodroot.crt");
    // Point sslkey/sslcert at existing files so the constructor reaches the keystore branch
    // even if LazyKeyManager/PEMKeyManager become eager about file I/O in the future
    // (today they defer reads until the SSL handshake, so the bug site at LibPQFactory.java:159
    // is reachable without these — but pinning real paths keeps this test from rotting silently).
    PGProperty.SSL_KEY.set(info, "../certdir/goodclient.key");
    PGProperty.SSL_CERT.set(info, "../certdir/goodclient.crt");
    assertDoesNotThrow(() -> new LibPQFactory(info));
  }
}
