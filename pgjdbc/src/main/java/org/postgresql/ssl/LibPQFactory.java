/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.SslMode;
import org.postgresql.ssl.NonValidatingFactory.NonValidatingTM;
import org.postgresql.util.GT;
import org.postgresql.util.ObjectFactory;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.internal.FileUtils;

import org.checkerframework.checker.initialization.qual.UnderInitialization;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Console;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Locale;
import java.util.Properties;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

/**
 * Provide an SSLSocketFactory that is compatible with the libpq behaviour.
 */
public class LibPQFactory extends WrappedFactory {

  @Nullable KeyManager km;
  boolean defaultfile;

  private CallbackHandler getCallbackHandler(
      @UnderInitialization(WrappedFactory.class) LibPQFactory this,
      Properties info) throws PSQLException {
    // Determine the callback handler
    CallbackHandler cbh;
    String sslpasswordcallback = PGProperty.SSL_PASSWORD_CALLBACK.getOrDefault(info);
    if (sslpasswordcallback != null) {
      try {
        cbh = ObjectFactory.instantiate(CallbackHandler.class, sslpasswordcallback, info, false, null);
      } catch (Exception e) {
        throw new PSQLException(
          GT.tr("The password callback class provided {0} could not be instantiated.",
            sslpasswordcallback),
          PSQLState.CONNECTION_FAILURE, e);
      }
    } else {
      cbh = new ConsoleCallbackHandler(PGProperty.SSL_PASSWORD.getOrDefault(info));
    }
    return cbh;
  }

  private String getCertFilePath(
          @UnderInitialization(WrappedFactory.class)LibPQFactory this, String defaultdir, Properties info) {
    // Load the client's certificate and key
    String sslcertfile = PGProperty.SSL_CERT.getOrDefault(info);
    if (sslcertfile == null) { // Fall back to default
      defaultfile = true;
      sslcertfile = defaultdir + "postgresql.crt";
    }
    return sslcertfile;
  }

  private void initPk8(
      @UnderInitialization(WrappedFactory.class)LibPQFactory this,
      String sslkeyfile, String defaultdir, Properties info) throws PSQLException {

    String sslcertfile = getCertFilePath(defaultdir, info);

    // If the properties are empty, give null to prevent client key selection
    km = new LazyKeyManager(("".equals(sslcertfile) ? null : sslcertfile),
      ("".equals(sslkeyfile) ? null : sslkeyfile), getCallbackHandler(info), defaultfile);
  }

  private void initP12(
      @UnderInitialization(WrappedFactory.class) LibPQFactory this,
      String sslkeyfile, Properties info) throws PSQLException {
    km = new PKCS12KeyManager(sslkeyfile, getCallbackHandler(info));
  }

  private void initPk8OrPem(
      @UnderInitialization(WrappedFactory.class)LibPQFactory this,
      String sslkeyfile, String defaultdir, Properties info) throws PSQLException {
    try {
      String sslcertfile = getCertFilePath(defaultdir, info);
      String algorithm = castNonNull(PGProperty.PEM_KEY_ALGORITHM.getOrDefault(info));
      PEMKeyManager pem = new PEMKeyManager(sslkeyfile, sslcertfile, algorithm);
      // Like libpq, try PEM first then fall back to PK8/DER at runtime.
      // We can't rely on file extensions (.key is ambiguous) or content sniffing
      // (PEM allows leading non-matching lines before -----BEGIN).
      LazyKeyManager pk8 = new LazyKeyManager(
          ("".equals(sslcertfile) ? null : sslcertfile),
          ("".equals(sslkeyfile) ? null : sslkeyfile),
          getCallbackHandler(info), defaultfile);
      km = new Pk8OrPemKeyManager(pem, pk8);
    } catch (Exception ex) {
      throw new PSQLException(
          GT.tr("Could not initialize key manager."),
          PSQLState.CONNECTION_FAILURE, ex);
    }
  }

  private void initPEM(
      @UnderInitialization(WrappedFactory.class)LibPQFactory this,
      String sslKeyFile, String defaultdir, Properties info) throws PSQLException {
    try {
      String sslCertFile = getCertFilePath(defaultdir, info);
      String algorithm = castNonNull(PGProperty.PEM_KEY_ALGORITHM.getOrDefault(info));
      km = new PEMKeyManager(sslKeyFile, sslCertFile, algorithm);
    } catch (Exception ex) {
      throw new PSQLException(GT.tr("Could not initialize PEMKeyManager."), PSQLState.CONNECTION_FAILURE, ex);
    }
  }

  /**
   * @param info the connection parameters The following parameters are used:
   *        sslmode,sslcert,sslkey,sslrootcert,sslhostnameverifier,sslpasswordcallback,sslpassword
   * @throws PSQLException if security error appears when initializing factory
   */
  public LibPQFactory(Properties info) throws PSQLException {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS"); // or "SSL" ?

      // Determining the default file location
      String pathsep = System.getProperty("file.separator");
      String defaultdir;

      if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows")) { // It is Windows
        defaultdir = System.getenv("APPDATA") + pathsep + "postgresql" + pathsep;
      } else {
        defaultdir = System.getProperty("user.home") + pathsep + ".postgresql" + pathsep;
      }

      String sslkeyfile = PGProperty.SSL_KEY.getOrDefault(info);
      if (sslkeyfile == null) { // Fall back to default
        defaultfile = true;
        sslkeyfile = defaultdir + "postgresql.pk8";
      }

      if (sslkeyfile.endsWith(".p12") || sslkeyfile.endsWith(".pfx")) {
        initP12(sslkeyfile, info);
      } else if (sslkeyfile.endsWith(".pem")) {
        initPEM(sslkeyfile, defaultdir, info);
      } else if (sslkeyfile.endsWith(".der")) {
        initPk8(sslkeyfile, defaultdir, info);
      } else {
        initPk8OrPem(sslkeyfile, defaultdir, info);
      }

      TrustManager[] tm;
      SslMode sslMode = SslMode.of(info);
      if (!sslMode.verifyCertificate()) {
        // server validation is not required
        tm = new TrustManager[]{new NonValidatingTM()};
      } else {
        // Load the server certificate

        TrustManagerFactory tmf = TrustManagerFactory.getInstance("PKIX");
        KeyStore ks;
        try {
          ks = KeyStore.getInstance("jks");
        } catch (KeyStoreException e) {
          // this should never happen
          throw new NoSuchAlgorithmException("jks KeyStore not available");
        }
        String sslrootcertfile = PGProperty.SSL_ROOT_CERT.getOrDefault(info);
        if (sslrootcertfile == null) { // Fall back to default
          sslrootcertfile = defaultdir + "root.crt";
        }
        InputStream is;
        try {
          is = FileUtils.newBufferedInputStream(sslrootcertfile); // NOSONAR
        } catch (FileNotFoundException ex) {
          throw new PSQLException(
              GT.tr("Could not open SSL root certificate file {0}.", sslrootcertfile),
              PSQLState.CONNECTION_FAILURE, ex);
        }
        try {
          CertificateFactory cf = CertificateFactory.getInstance("X.509");
          // Certificate[] certs = cf.generateCertificates(is).toArray(new Certificate[]{}); //Does
          // not work in java 1.4
          Object[] certs = cf.generateCertificates(is).toArray(new Certificate[]{});
          ks.load(null, null);
          for (int i = 0; i < certs.length; i++) {
            ks.setCertificateEntry("cert" + i, (Certificate) certs[i]);
          }
          tmf.init(ks);
        } catch (IOException ioex) {
          throw new PSQLException(
              GT.tr("Could not read SSL root certificate file {0}.", sslrootcertfile),
              PSQLState.CONNECTION_FAILURE, ioex);
        } catch (GeneralSecurityException gsex) {
          throw new PSQLException(
              GT.tr("Loading the SSL root certificate {0} into a TrustManager failed.",
                      sslrootcertfile),
              PSQLState.CONNECTION_FAILURE, gsex);
        } finally {
          try {
            is.close();
          } catch (IOException e) {
            /* ignore */
          }
        }
        tm = tmf.getTrustManagers();
      }

      // finally we can initialize the context
      try {
        KeyManager km = this.km;
        ctx.init(km == null ? null : new KeyManager[]{km}, tm, null);
      } catch (KeyManagementException ex) {
        throw new PSQLException(GT.tr("Could not initialize SSL context."),
            PSQLState.CONNECTION_FAILURE, ex);
      }

      factory = ctx.getSocketFactory();
    } catch (NoSuchAlgorithmException ex) {
      throw new PSQLException(GT.tr("Could not find a java cryptographic algorithm: {0}.",
              ex.getMessage()), PSQLState.CONNECTION_FAILURE, ex);
    }
  }

  /**
   * Propagates any exception from {@link LazyKeyManager}.
   *
   * @throws PSQLException if there is an exception to propagate
   */
  public void throwKeyManagerException() throws PSQLException {
    if (km != null) {
      if (km instanceof LazyKeyManager) {
        ((LazyKeyManager) km).throwKeyManagerException();
      }
      if (km instanceof PKCS12KeyManager) {
        ((PKCS12KeyManager) km).throwKeyManagerException();
      }
      if (km instanceof PEMKeyManager) {
        ((PEMKeyManager) km).throwKeyManagerException();
      }
      if (km instanceof Pk8OrPemKeyManager) {
        ((Pk8OrPemKeyManager) km).throwKeyManagerException();
      }
    }
  }

  /**
   * A CallbackHandler that reads the password from the console or returns the password given to its
   * constructor.
   */
  public static class ConsoleCallbackHandler implements CallbackHandler {

    private char @Nullable [] password;

    ConsoleCallbackHandler(@Nullable String password) {
      if (password != null) {
        this.password = password.toCharArray();
      }
    }

    /**
     * Handles the callbacks.
     *
     * @param callbacks The callbacks to handle
     * @throws UnsupportedCallbackException If the console is not available or other than
     *         PasswordCallback is supplied
     */
    @Override
    @SuppressWarnings("SystemConsoleNull")
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
      Console cons = System.console();
      char[] password = this.password;
      if (cons == null && password == null) {
        throw new UnsupportedCallbackException(callbacks[0], "Console is not available");
      }
      for (Callback callback : callbacks) {
        if (!(callback instanceof PasswordCallback)) {
          throw new UnsupportedCallbackException(callback);
        }
        PasswordCallback pwdCallback = (PasswordCallback) callback;
        if (password != null) {
          pwdCallback.setPassword(password);
          continue;
        }
        // It is used instead of cons.readPassword(prompt), because the prompt may contain '%'
        // characters
        pwdCallback.setPassword(
            castNonNull(cons, "System.console()")
                .readPassword("%s", pwdCallback.getPrompt())
        );
      }
    }
  }
}
