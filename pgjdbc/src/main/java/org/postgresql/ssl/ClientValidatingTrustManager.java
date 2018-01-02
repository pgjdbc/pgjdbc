package org.postgresql.ssl;

import org.postgresql.util.GT;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.UUID;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public class ClientValidatingTrustManager implements X509TrustManager {
  X509Certificate cert;
  X509TrustManager trustManager;

  public ClientValidatingTrustManager() throws IOException, GeneralSecurityException {
    InputStream in=null;
    KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
    // Note: KeyStore requires it be loaded even if you don't load anything into it:
    ks.load(null);
    CertificateFactory cf = CertificateFactory.getInstance("X509");
    cert = (X509Certificate) cf.generateCertificate(in);
    ks.setCertificateEntry(UUID.randomUUID().toString(), cert);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ks);
    for (TrustManager tm : tmf.getTrustManagers()) {
      if (tm instanceof X509TrustManager) {
        trustManager = (X509TrustManager) tm;
        break;
      }
    }
    if (trustManager == null) {
      throw new GeneralSecurityException(GT.tr("No X509TrustManager found"));
    }
  }
  @Override
  public void checkClientTrusted(X509Certificate[] x509Certificates, String s)
      throws CertificateException {

  }

  @Override
  public void checkServerTrusted(X509Certificate[] x509Certificates, String s)
      throws CertificateException {

  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    return new X509Certificate[0];
  }
}
