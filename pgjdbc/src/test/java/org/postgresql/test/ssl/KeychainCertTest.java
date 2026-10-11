/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.SocketFactoryFactory;
import org.postgresql.ssl.SubjectKeyManager;

import org.junit.jupiter.api.Test;

import java.security.KeyStore;
import java.util.Locale;
import java.util.Properties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * Verifies KeychainSSLFactory against a client certificate that has been
 * imported into the macOS keychain. The certificate is not present by default,
 * so the test only runs when the PGJDBC_KEYCHAIN_IMPORTED environment variable
 * is set; the CI job imports {@code certdir/goodclient.p12} before running it.
 *
 * <p>To run locally on macOS without touching the login keychain, use the
 * helper script, which creates a throwaway keychain and restores the search
 * list afterwards:</p>
 *
 * <pre>
 *   ./certdir/macos-keychain.sh setup
 *   PGJDBC_KEYCHAIN_IMPORTED=true ./gradlew :postgresql:test --tests '*KeychainCertTest'
 *   ./certdir/macos-keychain.sh teardown
 * </pre>
 */
class KeychainCertTest {

  private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

  // Subject of the leaf certificate inside certdir/goodclient.p12.
  private static final X500Principal CERT_SUBJECT =
      new X500Principal("CN=test, O=PgJdbc tests, ST=CA, C=US");

  private static void assumeKeychainImported() {
    assumeTrue(OS.contains("mac"), "KeychainStore is only available on macOS");
    assumeTrue("true".equalsIgnoreCase(System.getenv("PGJDBC_KEYCHAIN_IMPORTED")),
        "set PGJDBC_KEYCHAIN_IMPORTED=true after importing goodclient.p12 into a keychain");
  }

  @Test
  void keychainExposesImportedCertificate() throws Exception {
    assumeKeychainImported();

    KeyStore keyStore = KeyStore.getInstance("KeychainStore", "Apple");
    keyStore.load(null, null);

    KeyManagerFactory kmf = KeyManagerFactory.getInstance("PKIX");
    kmf.init(keyStore, new char[0]);
    X509KeyManager km = (X509KeyManager) kmf.getKeyManagers()[0];

    // The imported certificate must be selected when its subject is requested,
    // and no certificate must be returned for a subject that does not match.
    String matched = new SubjectKeyManager(km, CERT_SUBJECT)
        .chooseClientAlias(new String[]{"RSA"}, null, null);
    assertNotNull(matched, "Imported keychain certificate should match its own subject");

    X500Principal otherSubject = new X500Principal("CN=absent, O=PgJdbc tests, ST=CA, C=US");
    String unmatched = new SubjectKeyManager(km, otherSubject)
        .chooseClientAlias(new String[]{"RSA"}, null, null);
    assertNull(unmatched, "No certificate should match a subject that is not in the keychain");
  }

  @Test
  void keychainFactoryBuildsWithSslSubject() throws Exception {
    assumeKeychainImported();

    Properties props = new Properties();
    PGProperty.SSL_FACTORY.set(props, "org.postgresql.ssl.KeychainSSLFactory");
    PGProperty.SSL_SUBJECT.set(props, CERT_SUBJECT.getName());

    assertNotNull(SocketFactoryFactory.getSslSocketFactory(props));
  }
}
