/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.ssl.LazyKeyManager;
import org.postgresql.ssl.PEMKeyManager;
import org.postgresql.ssl.Pk8OrPemKeyManager;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import javax.security.auth.x500.X500Principal;

/**
 * Tests for {@link Pk8OrPemKeyManager} which auto-detects PEM vs PK8/DER format,
 * mimicking libpq's try-then-fallback behavior.
 */
class Pk8OrPemKeyManagerTest {

  private static final Logger LOGGER = Logger.getLogger(Pk8OrPemKeyManagerTest.class.getName());

  private static final X500Principal TEST_ISSUER =
      new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US");

  private static Set<PosixFilePermission> originalPermissions;
  private static Path keyFilePath;

  @BeforeAll
  static void setKeyFilePermissions() {
    try {
      File keyFile = new File(TestUtil.getSslTestCertPath("goodclient.key"));
      if (keyFile.exists()) {
        keyFilePath = keyFile.toPath();
        originalPermissions = Files.getPosixFilePermissions(keyFilePath);
        Set<PosixFilePermission> perms = new HashSet<>();
        perms.add(PosixFilePermission.OWNER_READ);
        Files.setPosixFilePermissions(keyFilePath, perms);
      }
    } catch (Exception e) {
      LOGGER.warning("Failed to set file permissions: " + e.getMessage());
    }
  }

  @AfterAll
  static void restoreKeyFilePermissions() {
    try {
      if (keyFilePath != null && originalPermissions != null && Files.exists(keyFilePath)) {
        Files.setPosixFilePermissions(keyFilePath, originalPermissions);
      }
    } catch (Exception e) {
      LOGGER.warning("Failed to restore file permissions: " + e.getMessage());
    }
  }

  private Pk8OrPemKeyManager createManager(String keyFile) {
    String sslKeyFile = TestUtil.getSslTestCertPath(keyFile);
    String sslCertFile = TestUtil.getSslTestCertPath("goodclient.crt");

    PEMKeyManager pem = new PEMKeyManager(sslKeyFile, sslCertFile, "RSA");
    LazyKeyManager pk8 = new LazyKeyManager(
        sslCertFile, sslKeyFile,
        new PKCS12KeyManagerTest.TestCallbackHandler(null), false);
    return new Pk8OrPemKeyManager(pem, pk8);
  }

  /**
   * A DER-encoded key should fall back to PK8 after PEM detection fails.
   * This is the regression case from issue #3942: .key was previously mapped
   * to PEM only, causing MalformedInputException for DER keys.
   *
   * <p>Verifies that chooseClientAlias (typically the first method called by
   * the TLS engine) triggers format detection and delegates to PK8.</p>
   */
  @Test
  void derKeyDelegatesPk8ViaChooseClientAlias() {
    Pk8OrPemKeyManager km = createManager("goodclient.pk8");

    // chooseClientAlias is typically the first method called during TLS handshake
    X500Principal[] issuers = new X500Principal[]{TEST_ISSUER};
    String alias = km.chooseClientAlias(new String[]{"RSA"}, issuers, null);
    assertNotNull(alias, "DER key should be loadable via PK8 fallback");

    // Verify the full chain works after delegation is resolved
    X509Certificate[] chain = km.getCertificateChain("user");
    assertNotNull(chain, "Certificate chain should be available after delegation");

    PrivateKey key = km.getPrivateKey("user");
    assertNotNull(key, "Private key should be available after delegation");
  }

  /**
   * A PEM-encoded key should be detected as PEM regardless of extension.
   */
  @Test
  void pemKeyDelegatesPemViaChooseClientAlias() {
    Pk8OrPemKeyManager km = createManager("goodclient.key");

    X500Principal[] issuers = new X500Principal[]{TEST_ISSUER};
    String alias = km.chooseClientAlias(new String[]{"RSA"}, issuers, null);
    assertNotNull(alias, "PEM key should be loadable via PEM detection");

    PrivateKey key = km.getPrivateKey("user");
    assertNotNull(key, "Private key should be available after PEM delegation");
  }

  /**
   * When getPrivateKey is called first (instead of chooseClientAlias),
   * delegation should still work correctly for DER keys.
   */
  @Test
  void derKeyDelegatesPk8ViaGetPrivateKey() {
    Pk8OrPemKeyManager km = createManager("goodclient.pk8");

    PrivateKey key = km.getPrivateKey("user");
    assertNotNull(key, "DER key should be loadable when getPrivateKey is called first");
  }

  /**
   * Invalid key type should return null alias even after successful delegation.
   */
  @Test
  void invalidKeyTypeReturnsNull() {
    Pk8OrPemKeyManager km = createManager("goodclient.key");

    X500Principal[] issuers = new X500Principal[]{TEST_ISSUER};
    String alias = km.chooseClientAlias(new String[]{"EC"}, issuers, null);
    assertNull(alias, "EC key type should not match RSA certificate");
  }

  /**
   * Integration test: a DER key file with .key extension should work through
   * the full LibPQFactory path. This is the exact regression from issue #3942.
   */
  @Test
  void derKeyWithDotKeyExtensionConnects(@TempDir Path tempDir) throws Exception {
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v9_5);
    TestUtil.assumeSslTestsEnabled();

    // Copy the DER key to a .key file to simulate the regression scenario
    Path derSource = new File(TestUtil.getSslTestCertPath("goodclient.pk8")).toPath();
    Path derKeyFile = tempDir.resolve("goodclient.key");
    Files.copy(derSource, derKeyFile);
    // Set owner-only permissions as required by key file validation
    Set<PosixFilePermission> perms = new HashSet<>();
    perms.add(PosixFilePermission.OWNER_READ);
    Files.setPosixFilePermissions(derKeyFile, perms);

    Properties props = new Properties();
    PGProperty.SSL_MODE.set(props, "prefer");
    PGProperty.SSL_KEY.set(props, derKeyFile.toString());
    PGProperty.SSL_CERT.set(props, TestUtil.getSslTestCertPath("goodclient.crt"));
    PGProperty.SSL_ROOT_CERT.set(props, TestUtil.getSslTestCertPath("goodroot.crt"));

    try (Connection conn = TestUtil.openDB(props)) {
      boolean sslUsed = TestUtil.queryForBoolean(conn,
          "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()");
      assertTrue(sslUsed, "SSL should be in use with DER .key file");
    }
  }
}
