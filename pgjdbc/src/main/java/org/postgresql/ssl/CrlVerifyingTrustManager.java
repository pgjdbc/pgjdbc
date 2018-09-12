/*
 * Copyright (c) 2018, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */


package org.postgresql.ssl;

import org.postgresql.util.GT;

import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;

import javax.net.ssl.X509TrustManager;


/**
 * A TrustManager that validated each certificate in the server chain against a certificate revocation list (CRL).
 * Only certificates in the server's chain are validated against the CRL. Client certificates are not validated.
 */
public class CrlVerifyingTrustManager implements X509TrustManager {
  private final X509CRL crl;

  public CrlVerifyingTrustManager(X509CRL crl) {
    if (crl == null) {
      throw new NullPointerException("crl is null");
    }
    this.crl = crl;
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    // no-op
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
    for (X509Certificate cert : chain) {
      if (crl.isRevoked(cert)) {
        throw new CertificateException(GT.tr("Server certificate with serial number {0} is revoked.", cert.getSerialNumber()));
      }
    }
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[] {};
  }
}
