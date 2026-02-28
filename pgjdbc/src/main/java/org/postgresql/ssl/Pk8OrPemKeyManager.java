/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509KeyManager;

/**
 * A key manager that tries PEM format first, then falls back to PK8/DER format.
 * This mimics libpq's behavior of attempting PEM loading first and falling back
 * to DER on failure, rather than relying on file extensions to determine format.
 *
 * <p>The delegate is resolved lazily on the first call to any {@link X509KeyManager}
 * method, so this works regardless of which method the TLS engine calls first.</p>
 */
public class Pk8OrPemKeyManager implements X509KeyManager {

  private final PEMKeyManager pem;
  private final LazyKeyManager pk8;
  private @Nullable X509KeyManager delegate;

  public Pk8OrPemKeyManager(PEMKeyManager pem, LazyKeyManager pk8) {
    this.pem = pem;
    this.pk8 = pk8;
  }

  /**
   * Resolves which key manager to use by probing the key file format.
   * Like libpq, tries PEM first then falls back to PK8/DER.
   */
  private X509KeyManager delegate() {
    X509KeyManager d = delegate;
    if (d != null) {
      return d;
    }
    // Like libpq, try PEM first then fall back to PK8/DER.
    // We can't rely on file extensions (.key is ambiguous) or content sniffing
    // (PEM allows leading non-matching lines before -----BEGIN).
    if (pem.getPrivateKey("user") != null) {
      d = pem;
    } else {
      d = pk8;
    }
    delegate = d;
    return d;
  }

  @Override
  public String @Nullable [] getClientAliases(String keyType,
      Principal @Nullable [] issuers) {
    return delegate().getClientAliases(keyType, issuers);
  }

  @Override
  public @Nullable String chooseClientAlias(String[] keyType,
      Principal @Nullable [] issuers, @Nullable Socket socket) {
    return delegate().chooseClientAlias(keyType, issuers, socket);
  }

  @Override
  public String @Nullable [] getServerAliases(String keyType,
      Principal @Nullable [] issuers) {
    return delegate().getServerAliases(keyType, issuers);
  }

  @Override
  public @Nullable String chooseServerAlias(String keyType,
      Principal @Nullable [] issuers, @Nullable Socket socket) {
    return delegate().chooseServerAlias(keyType, issuers, socket);
  }

  @Override
  public X509Certificate @Nullable [] getCertificateChain(String alias) {
    return delegate().getCertificateChain(alias);
  }

  @Override
  public @Nullable PrivateKey getPrivateKey(String alias) {
    return delegate().getPrivateKey(alias);
  }

  /**
   * Propagates any exception from the resolved delegate key manager.
   *
   * @throws PSQLException if the delegate key manager has a stored exception
   */
  public void throwKeyManagerException() throws PSQLException {
    X509KeyManager d = delegate();
    if (d instanceof LazyKeyManager) {
      ((LazyKeyManager) d).throwKeyManagerException();
    } else if (d instanceof BaseX509KeyManager) {
      ((BaseX509KeyManager) d).throwKeyManagerException();
    }
  }
}
