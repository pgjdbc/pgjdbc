/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * The SubjectKeyManager limits candidate certificates to
 * the certificate subject specified in "sslsubject".
 */
public class SubjectKeyManager implements X509KeyManager {

  private final X509KeyManager km;
  private final @Nullable X500Principal subject;

  /**
   * <p>
   * Wrap the provided key manager with this one, limiting
   * certificates to those matching the given subject.
   * </p>
   *
   * <p>
   * If subject is null, this key manager gives way to the
   * wrapped key manager and does nothing.
   * </p>
   * @param km Wrapped key manager
   * @param subject Subject distinguished name of the chosen
   *     certificate
   */
  public SubjectKeyManager(X509KeyManager km, @Nullable X500Principal subject) {

    this.km = km;
    this.subject = subject;

  }

  @Override
  public @Nullable String chooseClientAlias(String[] keyTypes, Principal @Nullable [] issuers,
      @Nullable Socket socket) {

    if (subject == null) {
      return this.km.chooseClientAlias(keyTypes, issuers, socket);
    }

    for (String keyType: keyTypes) {
      String[] aliases = getClientAliases(keyType, issuers);

      if (aliases == null) {
        continue;
      }

      for (String alias: aliases) {

        X509Certificate[] certchain = getCertificateChain(alias);
        if (certchain == null || certchain.length == 0) {
          continue;
        }

        X509Certificate leaf = certchain[0];
        X500Principal oursubject = leaf.getSubjectX500Principal();
        if (!oursubject.equals(subject)) {
          continue;
        }

        return alias;
      }
    }

    return null;
  }

  @Override
  public String @Nullable [] getClientAliases(String keyType, Principal @Nullable [] issuers) {
    return this.km.getClientAliases(keyType, issuers);
  }

  @Override
  public String @Nullable [] getServerAliases(String keyType, Principal@Nullable [] issuers) {
    return this.km.getServerAliases(keyType, issuers);
  }

  @Override
  public @Nullable  String chooseServerAlias(String keyType, Principal @Nullable [] issuers, @Nullable Socket socket) {
    return this.km.chooseServerAlias(keyType, issuers, socket);
  }

  @Override
  public X509Certificate @Nullable[] getCertificateChain(String alias) {
    return this.km.getCertificateChain(alias);
  }

  @Override
  public @Nullable PrivateKey getPrivateKey(String alias) {
    return this.km.getPrivateKey(alias);
  }

}
