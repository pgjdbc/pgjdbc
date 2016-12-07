/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.PGProperty;
import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.ssl.jdbc4.LibPQFactory;
import org.postgresql.util.GT;
import org.postgresql.util.ObjectFactory;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public class MakeSSL extends ObjectFactory {

  public static SSLContext getSSLContext(Properties info, String defaultCipher) throws NoSuchAlgorithmException {

    // Default to grabbing from the property, no prop set try default.
    String sslCipher = null;
    if (info != null) {
      sslCipher = PGProperty.SSL_CONTEXT_PROTOCOL.get(info);
    }
    if (sslCipher == null || sslCipher.isEmpty()) {
      sslCipher = defaultCipher;
    }

    // If at the end of the day we still don't have one default to the hardcoded default (TLS)
    if (sslCipher == null || sslCipher.isEmpty()) {
      sslCipher = PGProperty.SSL_CONTEXT_PROTOCOL.getDefaultValue();
    }
    return SSLContext.getInstance(sslCipher);
  }

  public static void convert(PGStream stream, Properties info, Logger logger)
      throws PSQLException, IOException {
    logger.debug("converting regular socket connection to ssl");

    SSLSocketFactory factory;

    String sslmode = PGProperty.SSL_MODE.get(info);
    // Use the default factory if no specific factory is requested
    // unless sslmode is set
    String classname = PGProperty.SSL_FACTORY.get(info);
    if (classname == null) {
      // If sslmode is set, use the libp compatible factory
      if (sslmode != null) {
        factory = new LibPQFactory(info);
      } else {
        factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
      }
    } else {
      try {
        factory = (SSLSocketFactory) instantiate(classname, info);
      } catch (Exception e) {
        throw new PSQLException(
            GT.tr("The SSLSocketFactory class provided {0} could not be instantiated.", classname),
            PSQLState.CONNECTION_FAILURE, e);
      }
    }

    SSLSocket newConnection;
    try {
      newConnection = (SSLSocket) factory.createSocket(stream.getSocket(),
          stream.getHostSpec().getHost(), stream.getHostSpec().getPort(), true);
      // We must invoke manually, otherwise the exceptions are hidden
      newConnection.startHandshake();
    } catch (IOException ex) {
      if (factory instanceof LibPQFactory) { // throw any KeyManager exception
        ((LibPQFactory) factory).throwKeyManagerException();
      }
      throw new PSQLException(GT.tr("SSL error: {0}", ex.getMessage()),
          PSQLState.CONNECTION_FAILURE, ex);
    }

    String sslhostnameverifier = PGProperty.SSL_HOSTNAME_VERIFIER.get(info);
    if (sslhostnameverifier != null) {
      HostnameVerifier hvn;
      try {
        hvn = (HostnameVerifier) instantiate(sslhostnameverifier, info);
      } catch (Exception e) {
        throw new PSQLException(
            GT.tr("The HostnameVerifier class provided {0} could not be instantiated.",
                sslhostnameverifier),
            PSQLState.CONNECTION_FAILURE, e);
      }
      if (!hvn.verify(stream.getHostSpec().getHost(), newConnection.getSession())) {
        throw new PSQLException(
            GT.tr("The hostname {0} could not be verified by hostnameverifier {1}.",
                stream.getHostSpec().getHost(), sslhostnameverifier),
            PSQLState.CONNECTION_FAILURE);
      }
    } else {
      if ("verify-full".equals(sslmode) && factory instanceof LibPQFactory) {
        if (!(((LibPQFactory) factory).verify(stream.getHostSpec().getHost(),
            newConnection.getSession()))) {
          throw new PSQLException(
              GT.tr("The hostname {0} could not be verified.", stream.getHostSpec().getHost()),
              PSQLState.CONNECTION_FAILURE);
        }
      }

    }
    stream.changeSocket(newConnection);
  }

}
