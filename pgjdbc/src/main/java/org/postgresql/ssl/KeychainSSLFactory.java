/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.PSQLException;

import java.util.Properties;

/**
 * <p>Provides a SSLSocketFactory that authenticates the remote server against
 * the keychain provided by MacOS.</p>
 *
 * <p>Older versions of the JDK support MacOS key stores but not trust stores.
 * If the trust store implementation is not found, this factory falls back to
 * the default JDK trust store in <code>cacerts</code>.
 *
 * <p>When multiple certificates match for the given connection, the optional
 * <code>sslsubject</code> connection property can be used to choose the
 * desired certificate from the matching set. Note that this property does not
 * override the certificate selection outside of the matching set.
 */
public class KeychainSSLFactory extends SSLFactory {

  public KeychainSSLFactory(Properties info) throws PSQLException {
    super(info, "TLS", "PKIX", "Apple",
                "KeychainStore",
                "Apple", "KeychainStore-ROOT");
  }

}
