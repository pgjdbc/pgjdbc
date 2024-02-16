/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.PSQLException;

import java.util.Properties;

/**
 * <p>Provides a SSLSocketFactory that authenticates the remote server against
 * the logged in user's certificate store provided by Windows.</p>
 *
 * <p>The remote certificate is validated against the logged in user's
 * certificate trust store.</p>
 *
 * <p>When multiple certificates match for the given connection, the optional
 * <code>sslsubject</code> connection property can be used to choose the
 * desired certificate from the matching set. Note that this property does not
 * override the certificate selection outside of the matching set.
 */
public class MSCAPISSLFactory extends SSLFactory {

  public MSCAPISSLFactory(Properties info) throws PSQLException {
    super(info, "TLS", "PKIX", "SunMSCAPI",
            "Windows-MY",
            "SunMSCAPI", "Windows-ROOT");
  }

}
