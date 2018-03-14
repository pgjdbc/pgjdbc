package org.postgresql.ssl.jdbc4;

import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.security.auth.x500.X500Principal;

public class LibPQVerifier implements HostnameVerifier {

  private static final int ALT_DNS_NAME = 2;

  public static boolean verifyHostName(String hostname, String pattern) {
    if (hostname == null || pattern == null) {
      return false;
    }
    if (!pattern.startsWith("*")) {
      // No wildcard => just compare hostnames
      return hostname.equalsIgnoreCase(pattern);
    }
    // pattern starts with *, so hostname should be at least (pattern.length-1) long
    if (hostname.length() < pattern.length() - 1) {
      return false;
    }
    // Compare ignore case
    final boolean ignoreCase = true;
    // Below code is "hostname.endsWithIgnoreCase(pattern.withoutFirstStar())"

    // E.g. hostname==sub.host.com; pattern==*.host.com
    // We need to start the offset of ".host.com" in hostname
    // For this we take hostname.length() - pattern.length()
    // and +1 is required since pattern is known to start with *
    int toffset = hostname.length() - pattern.length() + 1;

    // Wildcard covers just one domain level
    // a.b.c.com should not be covered by *.c.com
    if (hostname.lastIndexOf('.', toffset - 1) >= 0) {
      // If there's a dot in between 0..toffset
      return false;
    }

    return hostname.regionMatches(ignoreCase, toffset,
        pattern, 1, pattern.length() - 1);
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
   */
  public boolean verify(String hostname, SSLSession session) {
    X509Certificate[] peerCerts;
    try {
      peerCerts = (X509Certificate[]) session.getPeerCertificates();
    } catch (SSLPeerUnverifiedException e) {
      return false;
    }
    if (peerCerts == null || peerCerts.length == 0) {
      return false;
    }
    // Extract the common name
    X509Certificate serverCert = peerCerts[0];

    try {
      // Check for Subject Alternative Names (see RFC 6125)
      Collection<List<?>> subjectAltNames = serverCert.getSubjectAlternativeNames();

      if (subjectAltNames != null) {
        for (List<?> sanit : subjectAltNames) {
          Integer type = (Integer) sanit.get(0);
          String san = (String) sanit.get(1);

          // this mimics libpq check for ALT_DNS_NAME
          if (type != null && type == ALT_DNS_NAME && verifyHostName(hostname, san)) {
            return true;
          }
        }
      }
    } catch (CertificateParsingException e) {
      return false;
    }

    LdapName DN;
    try {
      DN = new LdapName(serverCert.getSubjectX500Principal().getName(X500Principal.RFC2253));
    } catch (InvalidNameException e) {
      return false;
    }
    String CN = null;
    for (Rdn rdn : DN.getRdns()) {
      if ("CN".equals(rdn.getType())) {
        // Multiple AVAs are not treated
        CN = (String) rdn.getValue();
        break;
      }
    }
    return verifyHostName(hostname, CN);
  }
}
