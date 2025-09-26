/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.PSQLException;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.net.Socket;
import java.security.Principal;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509KeyManager;
import javax.security.auth.x500.X500Principal;

public abstract class BaseX509KeyManager implements X509KeyManager {

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
        X509Certificate cert = certchain[certchain.length - 1];
        X500Principal ourissuer = cert.getIssuerX500Principal();
        String certKeyType = cert.getPublicKey().getAlgorithm();
        boolean keyTypeFound = false;
        boolean found = false;
        if (keyType != null && keyType.length > 0) {
          for (String kt : keyType) {
            if (kt.equalsIgnoreCase(certKeyType)) {
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
}
