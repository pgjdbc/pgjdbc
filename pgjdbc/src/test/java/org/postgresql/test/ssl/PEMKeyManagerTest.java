/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.ssl.PEMKeyManager;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import javax.security.auth.x500.X500Principal;

public class PEMKeyManagerTest {

  private static final Logger LOGGER = Logger.getLogger(PEMKeyManagerTest.class.getName());

  private Set<PosixFilePermission> originalPosixPermissions;
  private List<AclEntry> originalAclPermissions;
  private Path keyFilePath;

  /**
   * Let's attempt to make the goodclient.key file to have correct permissions (owner read-only permissions).
   * If it's not possible, lets not fail, and go ahead with the tests.
   */
  @BeforeEach
  void setKeyFilePermissions() {
    try {
      String keyFilePathStr = TestUtil.getSslTestCertPath("goodclient.key");
      File keyFile = new File(keyFilePathStr);

      if (keyFile.exists()) {
        keyFilePath = keyFile.toPath();

        // Check if the file system supports POSIX permissions
        if (Files.getFileAttributeView(keyFilePath, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
          // Save original POSIX permissions
          originalPosixPermissions = Files.getPosixFilePermissions(keyFilePath);
          LOGGER.info("Saved original POSIX permissions for " + keyFilePathStr);

          // Set POSIX permissions to 400 (read-only for owner)
          Set<PosixFilePermission> perms = new HashSet<>();
          perms.add(PosixFilePermission.OWNER_READ);
          Files.setPosixFilePermissions(keyFilePath, perms);
          LOGGER.info("Set POSIX permissions to 400 for " + keyFilePathStr);
        } else {
          // Windows: Save original ACL and set owner-only read permissions
          AclFileAttributeView aclView = Files.getFileAttributeView(keyFilePath, AclFileAttributeView.class);
          if (aclView != null) {
            originalAclPermissions = aclView.getAcl();
            LOGGER.info("Saved original ACL permissions for " + keyFilePathStr);
          }
          setWindowsOwnerOnlyPermissions(keyFilePath);
          LOGGER.info("Set Windows ACL permissions to owner read-only for " + keyFilePathStr);
        }
      }
    } catch (Exception e) {
      // Log and ignore permission setting errors - tests may still pass
      LOGGER.warning("Failed to set file permissions for goodclient.key: " + e.getMessage());
    }
  }

  /**
   * Restores the original file permissions that were saved in setKeyFilePermissions.
   */
  @AfterEach
  void restoreKeyFilePermissions() {
    try {
      if (keyFilePath != null && Files.exists(keyFilePath)) {
        // Restore POSIX permissions if they were saved
        if (originalPosixPermissions != null) {
          Files.setPosixFilePermissions(keyFilePath, originalPosixPermissions);
          LOGGER.info("Restored original POSIX permissions for " + keyFilePath);
          originalPosixPermissions = null;
        }

        // Restore ACL permissions if they were saved
        if (originalAclPermissions != null) {
          AclFileAttributeView aclView = Files.getFileAttributeView(keyFilePath, AclFileAttributeView.class);
          if (aclView != null) {
            aclView.setAcl(originalAclPermissions);
            LOGGER.info("Restored original ACL permissions for " + keyFilePath);
          }
          originalAclPermissions = null;
        }

        keyFilePath = null;
      }
    } catch (Exception e) {
      // Log and ignore permission restoration errors
      LOGGER.warning("Failed to restore file permissions for goodclient.key: " + e.getMessage());
    }
  }

  /**
   * Sets Windows ACL permissions to allow only the owner to read the file.
   * This is equivalent to chmod 400 on Unix systems.
   */
  private void setWindowsOwnerOnlyPermissions(Path path) throws Exception {
    AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class);
    if (aclView == null) {
      return; // ACL not supported
    }

    UserPrincipal owner = Files.getOwner(path);

    // Create ACL entry that grants only read permissions to the owner
    AclEntry entry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(owner)
        .setPermissions(
            EnumSet.of(
                AclEntryPermission.READ_DATA,
                AclEntryPermission.READ_ATTRIBUTES,
                AclEntryPermission.READ_NAMED_ATTRS,
                AclEntryPermission.SYNCHRONIZE
            )
        )
        .build();

    // Set the ACL with only the owner's read permissions
    List<AclEntry> acl = new ArrayList<>();
    acl.add(entry);
    aclView.setAcl(acl);
  }

  @Test
  void TestChooseClientAlias() {
    String sslKeyFile = TestUtil.getSslTestCertPath("goodclient.key");
    String sslCertFile = TestUtil.getSslTestCertPath("goodclient.crt");
    PEMKeyManager pemKeyManager = new PEMKeyManager(sslKeyFile, sslCertFile, "RSA");

    X500Principal testPrincipal = new X500Principal("CN=root certificate, O=PgJdbc test, ST=CA, C=US");
    X500Principal[] issuers = new X500Principal[]{testPrincipal};

    String validKeyType = pemKeyManager.chooseClientAlias(new String[]{"RSA"}, issuers, null);
    assertNotNull(validKeyType);

    String ignoresCase = pemKeyManager.chooseClientAlias(new String[]{"rsa"}, issuers, null);
    assertNotNull(ignoresCase);

    String invalidKeyType = pemKeyManager.chooseClientAlias(new String[]{"EC"}, issuers, null);
    assertNull(invalidKeyType);

    String containsValidKeyType = pemKeyManager.chooseClientAlias(new String[]{"EC", "RSA"}, issuers, null);
    assertNotNull(containsValidKeyType);

    String ignoresBlank = pemKeyManager.chooseClientAlias(new String[]{}, issuers, null);
    assertNotNull(ignoresBlank);
  }

  @Test
  void TestGoodClientPEM() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.SSL_MODE.set(props, "prefer");
    PGProperty.SSL_KEY.set(props, TestUtil.getSslTestCertPath("goodclient.key"));
    PGProperty.SSL_CERT.set(props, TestUtil.getSslTestCertPath("goodclient.crt"));
    PGProperty.PEM_KEY_ALGORITHM.set(props, "RSA");

    try (Connection conn = TestUtil.openDB(props)) {
      boolean sslUsed = TestUtil.queryForBoolean(conn, "SELECT ssl_is_used()");
      assertTrue(sslUsed, "SSL should be in use");
    }
  }
}
