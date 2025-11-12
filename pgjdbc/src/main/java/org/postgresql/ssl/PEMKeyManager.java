/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

public class PEMKeyManager extends BaseX509KeyManager {

  private final String keyFilePath;
  private final String certFilePath;
  private final String keyAlgorithm;

  public PEMKeyManager(String pemKeyPath, String pemCertsPath, String keyAlgorithm) {
    this.keyFilePath = pemKeyPath;
    this.certFilePath = pemCertsPath;
    this.keyAlgorithm = keyAlgorithm;
  }

  @Override
  public @Nullable PrivateKey getPrivateKey(String s) {
    try {
      Path keyPath = Paths.get(keyFilePath);

      // Validate file permissions before reading
      validateKeyFilePermissions(keyPath);

      List<String> lines = Files.readAllLines(keyPath);
      StringBuilder keyContent = new StringBuilder();
      for (String line : lines) {
        // as we are using PKCS#8 format, we just expect "BEGIN PRIVATE KEY" as the start of the key
        // ref: https://datatracker.ietf.org/doc/html/rfc5208#section-5
        if (line.contains("BEGIN PRIVATE KEY"))  {
          // ignore the start of the key
          continue;
        }
        // as we are using PKCS#8 format, we just expect "END PRIVATE KEY" as the end of the key
        // ref: https://datatracker.ietf.org/doc/html/rfc5208#section-5
        if (line.contains("END PRIVATE KEY")) {
          // stop reading after we encounter end of the key
          break;
        }
        keyContent.append(line.trim());
      }

      byte[] privateKeyDERBytes = Base64.getDecoder().decode(keyContent.toString());
      PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(privateKeyDERBytes);
      KeyFactory kf = KeyFactory.getInstance(this.keyAlgorithm);

      // to prevent security attacks, lets wipe out the contents of variables holding sensitive data
      Arrays.fill(privateKeyDERBytes, (byte) 0);
      for (int i = 0; i < keyContent.length(); i++) {
        keyContent.setCharAt(i, '\0');
      }

      return kf.generatePrivate(keySpec);
    } catch (Exception e) {
      error = new PSQLException(GT.tr("Could not load the private key"), PSQLState.CONNECTION_FAILURE, e);
    }
    return null;
  }

  @Override
  public X509Certificate @Nullable [] getCertificateChain(String alias) {
    try (InputStream inStream = Files.newInputStream(Paths.get(this.certFilePath))) {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");

      Collection<? extends Certificate> certs = cf.generateCertificates(inStream);
      List<X509Certificate> certChain = new ArrayList<>();

      for (Certificate cert : certs) {
        if (cert instanceof X509Certificate) {
          certChain.add((X509Certificate) cert);
        }
      }

      return certChain.toArray(new X509Certificate[0]);
    } catch (Exception e) {
      error = new PSQLException(GT.tr("Could not load cert chain"), PSQLState.CONNECTION_FAILURE, e);
    }
    return null;
  }

}
