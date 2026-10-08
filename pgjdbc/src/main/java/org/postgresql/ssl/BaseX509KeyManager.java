/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

public abstract class BaseX509KeyManager implements X509KeyManager {

  private static final int ROOT_UID = 0;

  protected @Nullable PSQLException error;

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
        // The server lists the CAs it accepts, so the issuer comes from the
        // last certificate in the chain. The key type must come from the
        // first certificate: it holds the public key for our private key.
        X500Principal ourissuer = certchain[certchain.length - 1]
            .getIssuerX500Principal();
        String certKeyType = certchain[0].getPublicKey().getAlgorithm();
        boolean keyTypeFound = false;
        boolean found = false;
        if (keyType != null && keyType.length > 0) {
          for (String kt : keyType) {
            if (keyTypeMatches(kt, certKeyType)) {
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

  /**
   * Tells whether a key type that the TLS provider asks for names the
   * algorithm of our certificate's public key.
   *
   * <p>A key type can carry a suffix after a slash. BouncyCastle's JSSE
   * provider asks for TLS 1.3 EC keys as {@code EC/secp384r1}, while
   * {@code getAlgorithm()} on the public key returns {@code EC}. Only the part
   * before the slash is compared. The provider checks the curve itself when it
   * validates the alias it gets back, and tries the next key type if the curve
   * does not match.</p>
   *
   * @param requested the key type the TLS provider asks for
   * @param certKeyType the algorithm of the certificate's public key
   * @return true if the algorithm part of {@code requested} equals
   *     {@code certKeyType}, ignoring case
   */
  static boolean keyTypeMatches(String requested, String certKeyType) {
    int slash = requested.indexOf('/');
    String algorithm = slash < 0 ? requested : requested.substring(0, slash);
    return algorithm.equalsIgnoreCase(certKeyType);
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

  /**
   * Validates that the private key file has secure permissions, matching libpq behavior.
   * On POSIX systems, root-owned files are allowed group-read access (up to 0640), since
   * it's common for root to own certs and grant read access via group membership. Files
   * owned by anyone else must be 0600 or stricter.
   * On Windows, ACLs are checked to ensure only the owner and trusted system accounts have access.
   *
   * @param keyPath the path to the private key file
   * @throws PSQLException if the file has insecure permissions
   */
  public static void validateKeyFilePermissions(Path keyPath) throws PSQLException {
    // Try POSIX permissions first (Linux, macOS, Unix)
    if (validatePosixPermissions(keyPath)) {
      return;
    }

    // If POSIX is not supported, try Windows ACL permissions
    if (validateWindowsAclPermissions(keyPath)) {
      return;
    }
    throw new PSQLException(
            GT.tr("Unable to retrieve the permissions of the private key file \"{0}\"",
                  keyPath.toString()),
            PSQLState.CONNECTION_FAILURE);
  }

  /**
   * Validates POSIX file permissions of key, matching libpq behavior.
   * Root-owned files (uid 0) allow GROUP_READ (up to 0640).
   * Non-root-owned files require 0600 or less (no group or other permissions).
   *
   * @param keyPath the path to the private key file
   * @return true if validation succeeded (permissions are secure), false if POSIX is not supported
   * @throws PSQLException if the file has insecure permissions
   */
  private static boolean validatePosixPermissions(Path keyPath) throws PSQLException {
    try {
      Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(keyPath);
      boolean isOwnedByRoot = isFileOwnedByRoot(keyPath);

      if (hasInsecurePosixPermissions(permissions, isOwnedByRoot)) {
        throw new PSQLException(
                GT.tr("private key file \"{0}\" has group or world access; "
                      + "file must have permissions u=rw (0600) or less if owned by the current user, "
                      + "or permissions u=rw,g=r (0640) or less if owned by root. "
                      + "Current permissions: {1}",
                      keyPath.toString(),
                      PosixFilePermissions.toString(permissions)),
                PSQLState.CONNECTION_FAILURE);
      }
      return true;
    } catch (UnsupportedOperationException e) {
      return false;
    } catch (IOException e) {
      throw new PSQLException(
              GT.tr("Could not read permissions for private key file \"{0}\"", keyPath.toString()),
              PSQLState.CONNECTION_FAILURE, e);
    }
  }

  /**
   * Checks whether the file is owned by root (uid 0).
   * Falls back to false if the unix:uid attribute is not available.
   */
  private static boolean isFileOwnedByRoot(Path keyPath) throws IOException {
    try {
      Object uid = Files.getAttribute(keyPath, "unix:uid");
      return Integer.valueOf(ROOT_UID).equals(uid);
    } catch (UnsupportedOperationException | IllegalArgumentException e) {
      // unix:uid not available
      return false;
    }
  }

  /**
   * Determines whether the given POSIX permissions are insecure for a private key file.
   * Matches libpq behavior: root-owned files allow GROUP_READ (0640),
   * while non-root-owned files reject all group and other permissions (0600).
   */
  private static boolean hasInsecurePosixPermissions(
      Set<PosixFilePermission> permissions, boolean isOwnedByRoot) {
    boolean hasOtherPerms = permissions.contains(PosixFilePermission.OTHERS_READ)
                            || permissions.contains(PosixFilePermission.OTHERS_WRITE)
                            || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);

    if (isOwnedByRoot) {
      boolean hasGroupWriteOrExecute = permissions.contains(PosixFilePermission.GROUP_WRITE)
                                       || permissions.contains(PosixFilePermission.GROUP_EXECUTE);
      return hasGroupWriteOrExecute || hasOtherPerms;
    }

    boolean hasGroupPerms = permissions.contains(PosixFilePermission.GROUP_READ)
                            || permissions.contains(PosixFilePermission.GROUP_WRITE)
                            || permissions.contains(PosixFilePermission.GROUP_EXECUTE);
    return hasGroupPerms || hasOtherPerms;
  }

  /**
   * Validates Windows ACL permissions of the key file.
   *
   * @param keyPath the path to the private key file
   * @return true if validation succeeded (permissions are secure), false if ACL is not supported
   * @throws PSQLException if the file has insecure permissions
   */
  private static boolean validateWindowsAclPermissions(Path keyPath) throws PSQLException {
    try {
      AclFileAttributeView aclView = Files.getFileAttributeView(keyPath, AclFileAttributeView.class);
      if (aclView == null) {
        return false;
      }

      UserPrincipal owner = aclView.getOwner();
      List<AclEntry> aclEntries = aclView.getAcl();

      for (AclEntry entry : aclEntries) {
        UserPrincipal principal = entry.principal();
        String principalName = principal.getName();

        // Allow owner, SYSTEM, and Administrators (these are trusted on Windows)
        boolean isTrustedPrincipal = principal.equals(owner)
                                     || principalName.equals("NT AUTHORITY\\SYSTEM")
                                     || principalName.equals("BUILTIN\\Administrators")
                                     || principalName.endsWith("\\Administrators");

        if (!isTrustedPrincipal) {
          // Check if this non-owner principal has READ permission
          Set<AclEntryPermission> permissions = entry.permissions();
          if (permissions.contains(AclEntryPermission.READ_DATA)
              || permissions.contains(AclEntryPermission.READ_ATTRIBUTES)
              || permissions.contains(AclEntryPermission.READ_NAMED_ATTRS)) {
            throw new PSQLException(
                    GT.tr("Private key file \"{0}\" has insecure permissions. "
                          + "Non-owner principal \"{1}\" has read access.",
                          keyPath.toString(),
                          principalName),
                    PSQLState.CONNECTION_FAILURE);
          }
        }
      }
      return true;
    } catch (UnsupportedOperationException e) {
      return false;
    } catch (IOException e) {
      throw new PSQLException(
              GT.tr("Could not read ACL permissions for private key file \"{0}\"", keyPath.toString()),
              PSQLState.CONNECTION_FAILURE, e);
    }
  }
}
