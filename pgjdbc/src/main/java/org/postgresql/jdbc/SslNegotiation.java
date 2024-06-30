/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.Properties;

public enum SslNegotiation {
  /**
   * Perform PostgreSQL protocol negotiation.
   */
  POSTGRES("postgres"),

  /**
   * Start SSL handshake directly after establishing the TCP/IP connection.
   */
  DIRECT("direct"),
  ;

  public static final SslNegotiation[] VALUES = values();
  public static final SslMode[] inValidSslModes = { SslMode.ALLOW, SslMode.DISABLE, SslMode.PREFER};

  public final String value;

  SslNegotiation(String value) {
    this.value = value;
  }

  public static boolean validSslMode(SslMode sslMode) {
    for (SslMode invalidModes : inValidSslModes) {
      if (sslMode == invalidModes) {
        return false;
      }
    }
    return true;
  }

  public static SslNegotiation of(Properties info) throws PSQLException {
    String sslnegotiation = PGProperty.SSL_NEGOTIATION.getOrDefault(info);
    SslMode sslMode = SslMode.of(info);

    // If sslnegotiation is not set, fallback to postgres negotiation protocol
    if (sslnegotiation == null) {
      return POSTGRES;
    }

    /*
     * Don't allow direct SSL negotiation with sslmode='prefer', because
     * that poses a risk of unintentional fallback to plaintext connection
     * when connecting to a pre-v17 server that does not support direct
     * SSL connections. To keep things simple, don't allow it with
     * sslmode='allow' or sslmode='disable' either. If a user goes through
     * the trouble of setting sslnegotiation='direct', they probably
     * intend to use SSL, and sslmode=disable or allow is probably a user
     * user mistake anyway.
     */
    if (DIRECT.value.equalsIgnoreCase(sslnegotiation) && !validSslMode(sslMode)) {
      throw new PSQLException(GT.tr("Weak sslmode \"{0}\" may not be used with sslnegotiation=direct (use \"{1}\", \"{2}\", or \"{3}\")",
          sslMode, SslMode.REQUIRE, SslMode.VERIFY_CA, SslMode.VERIFY_FULL),
          PSQLState.INVALID_NAME);
    }

    for (SslNegotiation sslNegotiation : VALUES) {
      if (sslNegotiation.value.equalsIgnoreCase(sslnegotiation)) {
        return sslNegotiation;
      }
    }
    throw new PSQLException(GT.tr("Invalid sslnegotation value: {0}", sslnegotiation),
        PSQLState.CONNECTION_UNABLE_TO_CONNECT);
  }
}
