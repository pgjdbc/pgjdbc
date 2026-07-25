/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509KeyManager;

/**
 * A key manager that selects PEM when its header is found within the first 64 KiB
 * of the key file, and PK8/DER otherwise. This preserves libpq's preference for PEM
 * before DER, while using a bounded marker scan rather than parsing the key during
 * format detection.
 *
 * <p>The delegate is resolved lazily on the first call to any {@link X509KeyManager}
 * method, so this works regardless of which method the TLS engine calls first.</p>
 */
public class Pk8OrPemKeyManager implements X509KeyManager {

  // PKCS#8 PEM marker (ASCII per RFC 7468) used to tell PEM from binary DER. A key file
  // is tiny, so the scan is bounded to a small prefix and uses O(1) match state, leaving
  // a misconfigured sslkey that points at a huge or endless file unable to exhaust memory.
  private static final byte[] PEM_HEADER = "BEGIN PRIVATE KEY".getBytes(StandardCharsets.US_ASCII);
  private static final int MAX_PEM_SCAN_BYTES = 64 * 1024;
  private static final int PEM_SCAN_CHUNK_BYTES = 8 * 1024;

  private final String keyFilePath;
  private final PEMKeyManager pem;
  private final LazyKeyManager pk8;
  private @Nullable X509KeyManager delegate;
  private @Nullable PSQLException permissionError;

  public Pk8OrPemKeyManager(String keyFilePath, PEMKeyManager pem, LazyKeyManager pk8) {
    this.keyFilePath = keyFilePath;
    this.pem = pem;
    this.pk8 = pk8;
  }

  /**
   * Resolves which key manager to use by probing the key file format, or {@code null}
   * if the key file has insecure permissions.
   */
  private @Nullable X509KeyManager delegate() {
    X509KeyManager d = delegate;
    if (d != null) {
      return d;
    }
    if (permissionError != null) {
      return null;
    }
    // Validate permissions before probing the format, otherwise the PK8/DER fallback
    // (which does not check permissions itself) would let an insecure key file load
    // even though the PEM path would have rejected it.
    try {
      if (!keyFilePath.isEmpty()) {
        Path keyPath = Paths.get(keyFilePath);
        if (Files.exists(keyPath)) {
          BaseX509KeyManager.validateKeyFilePermissions(keyPath);
        }
      }
    } catch (PSQLException e) {
      permissionError = e;
      return null;
    }
    // Preserve libpq's PEM-before-DER preference, but detect the format with a bounded
    // scan for the PEM private-key header rather than calling PEMKeyManager.getPrivateKey:
    // a failed probe there builds a translated PSQLException, and loading that message
    // bundle in the driver's class loader pins the loader and prevents it from being
    // unloaded.
    d = looksLikePem() ? pem : pk8;
    delegate = d;
    return d;
  }

  /**
   * Returns {@code true} if the key file is a PKCS#8 PEM file, detected by the
   * {@code BEGIN PRIVATE KEY} marker within the first {@link #MAX_PEM_SCAN_BYTES} bytes.
   * Binary (DER) or unreadable content is not PEM.
   *
   * <p>The marker is matched with a streaming scan, so it is found even when it straddles
   * a read boundary, while memory stays bounded to one chunk. The marker's first byte
   * ({@code 'B'}) does not recur in it, so a mismatch can only start a fresh match and no
   * KMP-style backtracking is needed.</p>
   */
  private boolean looksLikePem() {
    if (keyFilePath.isEmpty()) {
      return false;
    }
    Path keyPath = Paths.get(keyFilePath);
    if (!Files.exists(keyPath)) {
      return false;
    }
    byte[] chunk = new byte[PEM_SCAN_CHUNK_BYTES];
    int matched = 0;
    int scanned = 0;
    try (InputStream in = Files.newInputStream(keyPath)) {
      int read;
      while (scanned < MAX_PEM_SCAN_BYTES && (read = in.read(chunk)) != -1) {
        for (int i = 0; i < read; i++) {
          byte b = chunk[i];
          if (b == PEM_HEADER[matched]) {
            if (++matched == PEM_HEADER.length) {
              return true;
            }
          } else {
            matched = b == PEM_HEADER[0] ? 1 : 0;
          }
        }
        scanned += read;
      }
    } catch (IOException e) {
      // Unreadable file: treat as non-PEM.
    }
    return false;
  }

  @Override
  public String @Nullable [] getClientAliases(String keyType,
      Principal @Nullable [] issuers) {
    X509KeyManager d = delegate();
    return d == null ? null : d.getClientAliases(keyType, issuers);
  }

  @Override
  public @Nullable String chooseClientAlias(String[] keyType,
      Principal @Nullable [] issuers, @Nullable Socket socket) {
    X509KeyManager d = delegate();
    return d == null ? null : d.chooseClientAlias(keyType, issuers, socket);
  }

  @Override
  public String @Nullable [] getServerAliases(String keyType,
      Principal @Nullable [] issuers) {
    X509KeyManager d = delegate();
    return d == null ? null : d.getServerAliases(keyType, issuers);
  }

  @Override
  public @Nullable String chooseServerAlias(String keyType,
      Principal @Nullable [] issuers, @Nullable Socket socket) {
    X509KeyManager d = delegate();
    return d == null ? null : d.chooseServerAlias(keyType, issuers, socket);
  }

  @Override
  public X509Certificate @Nullable [] getCertificateChain(String alias) {
    X509KeyManager d = delegate();
    return d == null ? null : d.getCertificateChain(alias);
  }

  @Override
  public @Nullable PrivateKey getPrivateKey(String alias) {
    X509KeyManager d = delegate();
    return d == null ? null : d.getPrivateKey(alias);
  }

  /**
   * Propagates any exception from the resolved delegate key manager, including an
   * insecure-permission error detected while probing the key file.
   *
   * @throws PSQLException if the delegate key manager has a stored exception
   */
  public void throwKeyManagerException() throws PSQLException {
    X509KeyManager d = delegate();
    if (permissionError != null) {
      throw permissionError;
    }
    if (d instanceof LazyKeyManager) {
      ((LazyKeyManager) d).throwKeyManagerException();
    } else if (d instanceof BaseX509KeyManager) {
      ((BaseX509KeyManager) d).throwKeyManagerException();
    }
  }
}
