/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl;

import org.postgresql.util.PSQLException;

import java.util.Properties;

/**
 * <p>Provides a SSLSocketFactory that authenticates the remote server against
 * the local machine's certificate store provided by Windows.</p>
 *
 * <p>The remote certificate is validated against the local machine's
 * certificate trust store.</p>
 *
 * <p>When multiple certificates match for the given connection, the optional
 * <code>sslsubject</code> connection property can be used to choose the
 * desired certificate from the matching set. Note that this property does not
 * override the certificate selection outside of the matching set.
 */
public class MSCAPILocalMachineSSLFactory extends SSLFactory {

  public MSCAPILocalMachineSSLFactory(Properties info) throws PSQLException {
    super(info, "TLS", "PKIX", "SunMSCAPI",
            "Windows-MY-LOCALMACHINE",
            "SunMSCAPI", "Windows-ROOT");
  }

}
