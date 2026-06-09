/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.ServerVersion;
import org.postgresql.ssl.LazyKeyManager;
import org.postgresql.ssl.PEMKeyManager;
import org.postgresql.ssl.Pk8OrPemKeyManager;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

/**
 * Tests for {@link Pk8OrPemKeyManager}, which auto-detects PEM vs PK8/DER format
 * with a bounded scan while preserving libpq's preference for PEM before DER.
 */
class Pk8OrPemKeyManagerTest {

  private static final X500Principal TEST_ISSUER =
      new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US");

  @TempDir
  Path tempDir;

  /**
   * Copies a cert-dir key file into the per-test temp dir and restricts it to
   * owner-only access, so it satisfies the key-file permission check. The owner-only
   * permission cannot be expressed on a non-POSIX filesystem, so the test is skipped there.
   */
  private Path secureKeyCopy(String keyFileName) throws IOException {
    Path keyFile = tempDir.resolve(keyFileName);
    Files.copy(Paths.get(TestUtil.getSslTestCertPath(keyFileName)), keyFile);
    assumeTrue(Files.getFileAttributeView(keyFile, PosixFileAttributeView.class) != null,
        "Test requires a POSIX filesystem to set owner-only key permissions");
    Set<PosixFilePermission> perms = new HashSet<>();
    perms.add(PosixFilePermission.OWNER_READ);
    Files.setPosixFilePermissions(keyFile, perms);
    return keyFile;
  }

  private Pk8OrPemKeyManager createManager(String keyFileName) throws IOException {
    Path keyFile = secureKeyCopy(keyFileName);
    String sslCertFile = TestUtil.getSslTestCertPath("goodclient.crt");

    PEMKeyManager pem = new PEMKeyManager(keyFile.toString(), sslCertFile, "RSA");
    LazyKeyManager pk8 = new LazyKeyManager(
        sslCertFile, keyFile.toString(),
        new PKCS12KeyManagerTest.TestCallbackHandler(null), false);
    return new Pk8OrPemKeyManager(keyFile.toString(), pem, pk8);
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
  void derKeyDelegatesPk8ViaChooseClientAlias() throws Exception {
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
  void pemKeyDelegatesPemViaChooseClientAlias() throws Exception {
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
  void derKeyDelegatesPk8ViaGetPrivateKey() throws Exception {
    Pk8OrPemKeyManager km = createManager("goodclient.pk8");

    PrivateKey key = km.getPrivateKey("user");
    assertNotNull(key, "DER key should be loadable when getPrivateKey is called first");
  }

  /**
   * Invalid key type should return null alias even after successful delegation.
   */
  @Test
  void invalidKeyTypeReturnsNull() throws Exception {
    Pk8OrPemKeyManager km = createManager("goodclient.key");

    X500Principal[] issuers = new X500Principal[]{TEST_ISSUER};
    String alias = km.chooseClientAlias(new String[]{"EC"}, issuers, null);
    assertNull(alias, "EC key type should not match RSA certificate");
  }

  /**
   * A key file with insecure permissions must be rejected rather than loaded via the
   * PK8/DER fallback, which would otherwise bypass the PEM path's permission check.
   */
  @Test
  void insecurePermissionsRejectKey() throws Exception {
    Path keyFile = tempDir.resolve("goodclient.key");
    Files.copy(Paths.get(TestUtil.getSslTestCertPath("goodclient.pk8")), keyFile);
    assumeTrue(Files.getFileAttributeView(keyFile, PosixFileAttributeView.class) != null,
        "Test requires a POSIX filesystem to express insecure permissions");
    // 0604: world-readable, insecure regardless of whether the owner is root
    Set<PosixFilePermission> perms = new HashSet<>();
    perms.add(PosixFilePermission.OWNER_READ);
    perms.add(PosixFilePermission.OWNER_WRITE);
    perms.add(PosixFilePermission.OTHERS_READ);
    Files.setPosixFilePermissions(keyFile, perms);

    String sslCertFile = TestUtil.getSslTestCertPath("goodclient.crt");
    PEMKeyManager pem = new PEMKeyManager(keyFile.toString(), sslCertFile, "RSA");
    LazyKeyManager pk8 = new LazyKeyManager(
        sslCertFile, keyFile.toString(),
        new PKCS12KeyManagerTest.TestCallbackHandler(null), false);
    Pk8OrPemKeyManager km = new Pk8OrPemKeyManager(keyFile.toString(), pem, pk8);

    assertNull(km.getPrivateKey("user"),
        "Key with insecure permissions must not load via PK8 fallback");
    assertThrows(PSQLException.class, km::throwKeyManagerException,
        "Insecure permissions must surface as a key manager exception");
  }

  /**
   * The PEM header is detected even when it straddles the streaming scan's read
   * boundary, i.e. the file spans more than one internal read.
   */
  @Test
  void pemHeaderAcrossReadBoundaryDetected() throws Exception {
    String pem = new String(
        Files.readAllBytes(Paths.get(TestUtil.getSslTestCertPath("goodclient.key"))),
        StandardCharsets.UTF_8);
    // Blank lines are ignored by the PEM parser, so the key still loads; the padding
    // pushes "BEGIN PRIVATE KEY" past the 8 KiB scan chunk so it spans two reads.
    StringBuilder padded = new StringBuilder();
    for (int i = 0; i < 8180; i++) {
      padded.append('\n');
    }
    padded.append(pem);
    Path keyFile = tempDir.resolve("padded.key");
    Files.write(keyFile, padded.toString().getBytes(StandardCharsets.UTF_8));
    assumeTrue(Files.getFileAttributeView(keyFile, PosixFileAttributeView.class) != null,
        "Test requires a POSIX filesystem to set owner-only key permissions");
    Set<PosixFilePermission> perms = new HashSet<>();
    perms.add(PosixFilePermission.OWNER_READ);
    Files.setPosixFilePermissions(keyFile, perms);

    String sslCertFile = TestUtil.getSslTestCertPath("goodclient.crt");
    PEMKeyManager pem2 = new PEMKeyManager(keyFile.toString(), sslCertFile, "RSA");
    LazyKeyManager pk8 = new LazyKeyManager(
        sslCertFile, keyFile.toString(),
        new PKCS12KeyManagerTest.TestCallbackHandler(null), false);
    Pk8OrPemKeyManager km = new Pk8OrPemKeyManager(keyFile.toString(), pem2, pk8);

    assertNotNull(km.getPrivateKey("user"),
        "PEM key must be detected when its header straddles the scan read boundary");
  }

  /**
   * Integration test: a DER key file with .key extension should work through
   * the full LibPQFactory path. This is the exact regression from issue #3942.
   */
  @Test
  void derKeyWithDotKeyExtensionConnects() throws Exception {
    TestUtil.assumeHaveMinimumServerVersion(ServerVersion.v9_5);
    TestUtil.assumeSslTestsEnabled();

    // Copy the DER key to a .key file to simulate the regression scenario
    Path derKeyFile = tempDir.resolve("goodclient.key");
    Files.copy(Paths.get(TestUtil.getSslTestCertPath("goodclient.pk8")), derKeyFile);
    // The key-file permission check requires owner-only POSIX permissions, which
    // cannot be expressed on non-POSIX filesystems, so skip the test there.
    assumeTrue(Files.getFileAttributeView(derKeyFile, PosixFileAttributeView.class) != null,
        "Test requires a POSIX filesystem to set owner-only key permissions");
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
