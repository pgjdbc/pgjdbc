/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.ssl.jdbc4;

import org.postgresql.jdbc.SslMode;
import org.postgresql.ssl.PGjdbcHostnameVerifier;
import org.postgresql.util.PSQLException;

import java.net.IDN;
import java.util.Properties;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSession;

/**
 * @deprecated prefer {@link org.postgresql.ssl.LibPQFactory}
 */
@Deprecated
public class LibPQFactory extends org.postgresql.ssl.LibPQFactory implements HostnameVerifier {
  private final SslMode sslMode;

  /**
   * @param info the connection parameters The following parameters are used:
   *             sslmode,sslcert,sslkey,sslrootcert,sslhostnameverifier,sslpasswordcallback,sslpassword
   * @throws PSQLException if security error appears when initializing factory
   * @deprecated prefer {@link org.postgresql.ssl.LibPQFactory}
   */
  @Deprecated
  public LibPQFactory(Properties info) throws PSQLException {
    super(info);

    sslMode = SslMode.of(info);
  }

  /**
   * Verifies if given hostname matches pattern.
   *
   * @param hostname input hostname
   * @param pattern domain name pattern
   * @return true when domain matches pattern
   * @deprecated use {@link PGjdbcHostnameVerifier}
   */
  @Deprecated
  public static boolean verifyHostName(String hostname, String pattern) {
    String canonicalHostname;
    if (hostname.startsWith("[") && hostname.endsWith("]")) {
      // IPv6 address like [2001:db8:0:1:1:1:1:1]
      canonicalHostname = hostname.substring(1, hostname.length() - 1);
    } else {
      // This converts unicode domain name to ASCII
      try {
        canonicalHostname = IDN.toASCII(hostname);
      } catch (IllegalArgumentException e) {
        // e.g. hostname is invalid
        return false;
      }
    }
    return PGjdbcHostnameVerifier.INSTANCE.verifyHostName(canonicalHostname, pattern);
  }

  /**
   * Verifies the server certificate according to the libpq rules. The cn attribute of the
   * certificate is matched against the hostname. If the cn attribute starts with an asterisk (*),
   * it will be treated as a wildcard, and will match all characters except a dot (.). This means
   * the certificate will not match subdomains. If the connection is made using an IP address
   * instead of a hostname, the IP address will be matched (without doing any DNS lookups).
   *
   * @param hostname Hostname or IP address of the server.
   * @param session The SSL session.
   * @return true if the certificate belongs to the server, false otherwise.
   * @see PGjdbcHostnameVerifier
   * @deprecated use PgjdbcHostnameVerifier
   */
  @Deprecated
  public boolean verify(String hostname, SSLSession session) {
    if (!sslMode.verifyPeerName()) {
      return true;
    }
    return PGjdbcHostnameVerifier.INSTANCE.verify(hostname, session);
  }

}
