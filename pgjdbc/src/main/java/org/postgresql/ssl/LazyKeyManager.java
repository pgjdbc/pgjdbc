/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.Socket;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collection;

import javax.crypto.Cipher;
import javax.crypto.EncryptedPrivateKeyInfo;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.X509KeyManager;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.auth.x500.X500Principal;

/**
 * A Key manager that only loads the keys, if necessary.
 */
public class LazyKeyManager implements X509KeyManager {
  private X509Certificate @Nullable [] cert = null;
  private @Nullable PrivateKey key = null;
  private final @Nullable String certfile;
  private final @Nullable String keyfile;
  private final CallbackHandler cbh;
  private final boolean defaultfile;
  private @Nullable PSQLException error = null;

  /**
   * Constructor. certfile and keyfile can be null, in that case no certificate is presented to the
   * server.
   *
   * @param certfile certfile
   * @param keyfile key file
   * @param cbh callback handler
   * @param defaultfile default file
   */
  public LazyKeyManager(@Nullable String certfile, @Nullable String keyfile, CallbackHandler cbh, boolean defaultfile) {
    this.certfile = certfile;
    this.keyfile = keyfile;
    this.cbh = cbh;
    this.defaultfile = defaultfile;
  }

  /**
   * getCertificateChain and getPrivateKey cannot throw exeptions, therefore any exception is stored
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
  public @Nullable String chooseClientAlias(String[] keyType,
      Principal @Nullable [] issuers, @Nullable Socket socket) {
    if (certfile == null) {
      return null;
    } else {
      if (issuers == null || issuers.length == 0) {
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
          X500Principal ourissuer = certchain[certchain.length - 1].getIssuerX500Principal();
          boolean found = false;
          for (Principal issuer : issuers) {
            if (ourissuer.equals(issuer)) {
              found = true;
            }
          }
          return (found ? "user" : null);
        }
      }
    }
  }

  @Override
  public @Nullable String chooseServerAlias(String keyType,
      Principal @Nullable [] issuers, @Nullable Socket socket) {
    return null; // We are not a server
  }

  @Override
  public X509Certificate @Nullable [] getCertificateChain(String alias) {
    if (cert == null && certfile != null) {
      // If certfile is null, we do not load the certificate
      // The certificate must be loaded
      CertificateFactory cf;
      try {
        cf = CertificateFactory.getInstance("X.509");
      } catch (CertificateException ex) {
        // For some strange reason it throws CertificateException instead of
        // NoSuchAlgorithmException...
        error = new PSQLException(GT.tr(
            "Could not find a java cryptographic algorithm: X.509 CertificateFactory not available."),
            PSQLState.CONNECTION_FAILURE, ex);
        return null;
      }
      Collection<? extends Certificate> certs;
      FileInputStream certfileStream = null;
      try {
        certfileStream = new FileInputStream(certfile);
        certs = cf.generateCertificates(certfileStream);
      } catch (FileNotFoundException ioex) {
        if (!defaultfile) { // It is not an error if there is no file at the default location
          error = new PSQLException(
              GT.tr("Could not open SSL certificate file {0}.", certfile),
              PSQLState.CONNECTION_FAILURE, ioex);
        }
        return null;
      } catch (CertificateException gsex) {
        error = new PSQLException(GT.tr("Loading the SSL certificate {0} into a KeyManager failed.",
            certfile), PSQLState.CONNECTION_FAILURE, gsex);
        return null;
      } finally {
        if (certfileStream != null) {
          try {
            certfileStream.close();
          } catch (IOException ioex) {
            if (!defaultfile) { // It is not an error if there is no file at the default location
              error = new PSQLException(
                  GT.tr("Could not close SSL certificate file {0}.", certfile),
                  PSQLState.CONNECTION_FAILURE, ioex);
            }
          }
        }
      }
      cert = certs.toArray(new X509Certificate[0]);
    }
    return cert;
  }

  @Override
  public String @Nullable [] getClientAliases(String keyType,
      Principal @Nullable [] issuers) {
    String alias = chooseClientAlias(new String[]{keyType}, issuers, (Socket) null);
    return (alias == null ? new String[]{} : new String[]{alias});
  }

  private static byte[] readFileFully(String path) throws IOException {
    RandomAccessFile raf = new RandomAccessFile(path, "r");
    try {
      byte[] ret = new byte[(int) raf.length()];
      raf.readFully(ret);
      return ret;
    } finally {
      raf.close();
    }
  }

  @Override
  public @Nullable PrivateKey getPrivateKey(String alias) {
    try {
      if (key == null && keyfile != null) {
        // If keyfile is null, we do not load the key
        // The private key must be loaded
        X509Certificate[] cert = getCertificateChain("user");
        if (cert == null || cert.length == 0) { // We need the certificate for the algorithm
          return null;
        }

        byte[] keydata;
        try {
          keydata = readFileFully(keyfile);
        } catch (FileNotFoundException ex) {
          if (!defaultfile) {
            // It is not an error if there is no file at the default location
            throw ex;
          }
          return null;
        }

        KeyFactory kf = KeyFactory.getInstance(cert[0].getPublicKey().getAlgorithm());
        try {
          KeySpec pkcs8KeySpec = new PKCS8EncodedKeySpec(keydata);
          key = kf.generatePrivate(pkcs8KeySpec);
        } catch (InvalidKeySpecException ex) {
          // The key might be password protected
          EncryptedPrivateKeyInfo ePKInfo = new EncryptedPrivateKeyInfo(keydata);
          Cipher cipher;
          try {
            cipher = Cipher.getInstance(ePKInfo.getAlgName());
          } catch (NoSuchPaddingException npex) {
            // Why is it not a subclass of NoSuchAlgorithmException?
            throw new NoSuchAlgorithmException(npex.getMessage(), npex);
          }
          // We call back for the password
          PasswordCallback pwdcb = new PasswordCallback(GT.tr("Enter SSL password: "), false);
          try {
            cbh.handle(new Callback[]{pwdcb});
          } catch (UnsupportedCallbackException ucex) {
            if ((cbh instanceof LibPQFactory.ConsoleCallbackHandler)
                && ("Console is not available".equals(ucex.getMessage()))) {
              error = new PSQLException(GT
                  .tr("Could not read password for SSL key file, console is not available."),
                  PSQLState.CONNECTION_FAILURE, ucex);
            } else {
              error =
                  new PSQLException(
                      GT.tr("Could not read password for SSL key file by callbackhandler {0}.",
                              cbh.getClass().getName()),
                      PSQLState.CONNECTION_FAILURE, ucex);
            }
            return null;
          }
          try {
            PBEKeySpec pbeKeySpec = new PBEKeySpec(pwdcb.getPassword());
            pwdcb.clearPassword();
            // Now create the Key from the PBEKeySpec
            SecretKeyFactory skFac = SecretKeyFactory.getInstance(ePKInfo.getAlgName());
            Key pbeKey = skFac.generateSecret(pbeKeySpec);
            // Extract the iteration count and the salt
            AlgorithmParameters algParams = ePKInfo.getAlgParameters();
            cipher.init(Cipher.DECRYPT_MODE, pbeKey, algParams);
            // Decrypt the encryped private key into a PKCS8EncodedKeySpec
            KeySpec pkcs8KeySpec = ePKInfo.getKeySpec(cipher);
            key = kf.generatePrivate(pkcs8KeySpec);
          } catch (GeneralSecurityException ikex) {
            error = new PSQLException(
                GT.tr("Could not decrypt SSL key file {0}.", keyfile),
                PSQLState.CONNECTION_FAILURE, ikex);
            return null;
          }
        }
      }
    } catch (IOException ioex) {
      error = new PSQLException(GT.tr("Could not read SSL key file {0}.", keyfile),
          PSQLState.CONNECTION_FAILURE, ioex);
    } catch (NoSuchAlgorithmException ex) {
      error = new PSQLException(GT.tr("Could not find a java cryptographic algorithm: {0}.",
              ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
      return null;
    }

    return key;
  }

  @Override
  public String @Nullable [] getServerAliases(String keyType, Principal @Nullable [] issuers) {
    return new String[]{};
  }
}
