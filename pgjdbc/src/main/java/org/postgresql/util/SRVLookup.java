/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import org.checkerframework.checker.nullness.qual.Nullable;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves DNS SRV records for PostgreSQL service discovery.
 *
 * <p>Looks up {@code _postgresql._tcp.<srvHost>} and returns the resulting {@link HostSpec} array
 * sorted by priority ascending then weight descending, as required by RFC 2782.
 */
public final class SRVLookup {

  private static final Logger LOGGER = Logger.getLogger(SRVLookup.class.getName());

  private SRVLookup() {
  }

  /**
   * Resolve SRV records for the given domain using the system default DNS resolver.
   *
   * @param srvHost the cluster domain (e.g. {@code cluster.example.com})
   * @return non-empty array of {@link HostSpec} in RFC 2782 order
   * @throws PSQLException if the lookup fails or returns no records
   */
  public static HostSpec[] resolve(String srvHost) throws PSQLException {
    return resolve(srvHost, null);
  }

  /**
   * Resolve SRV records for the given domain.
   *
   * @param srvHost   the cluster domain (e.g. {@code cluster.example.com})
   * @param dnsServer optional DNS server address such as {@code "8.8.8.8"} or
   *                  {@code "8.8.8.8:53"}, or {@code null} to use the system default
   * @return non-empty array of {@link HostSpec} in RFC 2782 order
   * @throws PSQLException if the lookup fails or returns no records
   */
  public static HostSpec[] resolve(String srvHost, @Nullable String dnsServer) throws PSQLException {
    String dnsName = "_postgresql._tcp." + srvHost;
    LOGGER.log(Level.FINE, "Looking up SRV records for {0}", dnsName);

    Hashtable<String, String> env = new Hashtable<>();
    env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
    env.put("java.naming.provider.url", dnsServer != null ? "dns://" + dnsServer : "dns:");

    try {
      DirContext ctx = new InitialDirContext(env);
      try {
        Attributes attrs = ctx.getAttributes(dnsName, new String[]{"SRV"});
        Attribute srvAttr = attrs.get("SRV");
        if (srvAttr == null || srvAttr.size() == 0) {
          throw new PSQLException(
              GT.tr("No SRV records found for {0}", dnsName),
              PSQLState.CONNECTION_UNABLE_TO_CONNECT);
        }

        List<String> rawRecords = new ArrayList<>();
        for (int i = 0; i < srvAttr.size(); i++) {
          rawRecords.add((String) srvAttr.get(i));
        }
        return parseAndSort(rawRecords, dnsName);
      } finally {
        ctx.close();
      }
    } catch (NamingException e) {
      throw new PSQLException(
          GT.tr("SRV lookup failed for {0}: {1}", dnsName, e.getMessage()),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT, e);
    }
  }

  /**
   * Parse and sort a list of raw SRV record strings as returned by JNDI
   * ({@code "priority weight port target"}) and return a {@link HostSpec} array in RFC 2782 order.
   *
   * <p>Package-private so unit tests can exercise sorting and parsing without a live DNS resolver.
   */
  static HostSpec[] parseAndSort(List<String> rawRecords, String dnsName) throws PSQLException {
    List<SRVRecord> records = new ArrayList<>();
    for (String entry : rawRecords) {
      SRVRecord rec = parseSRVRecord(entry);
      if (rec != null) {
        records.add(rec);
      }
    }

    if (records.isEmpty()) {
      throw new PSQLException(
          GT.tr("No valid SRV records found for {0}", dnsName),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT);
    }

    // RFC 2782: sort by priority ascending, then weight descending
    records.sort((a, b) -> {
      if (a.priority != b.priority) {
        return Integer.compare(a.priority, b.priority);
      }
      return Integer.compare(b.weight, a.weight);
    });

    HostSpec[] result = new HostSpec[records.size()];
    for (int i = 0; i < records.size(); i++) {
      SRVRecord rec = records.get(i);
      result[i] = new HostSpec(rec.target, rec.port);
      LOGGER.log(Level.FINE, "SRV target [{0}]: {1}:{2} (priority={3}, weight={4})",
          new Object[]{i, rec.target, rec.port, rec.priority, rec.weight});
    }
    return result;
  }

  private static @Nullable SRVRecord parseSRVRecord(String record) {
    // JNDI returns each SRV entry as the string "priority weight port target"
    String[] parts = record.trim().split("\\s+");
    if (parts.length < 4) {
      LOGGER.log(Level.WARNING, "Skipping malformed SRV record: {0}", record);
      return null;
    }
    try {
      int priority = Integer.parseInt(parts[0]);
      int weight = Integer.parseInt(parts[1]);
      int port = Integer.parseInt(parts[2]);
      String target = parts[3];
      if (target.endsWith(".")) {
        target = target.substring(0, target.length() - 1);
      }
      return new SRVRecord(priority, weight, port, target);
    } catch (NumberFormatException e) {
      LOGGER.log(Level.WARNING, "Skipping SRV record with invalid numbers: {0}", record);
      return null;
    }
  }

  private static final class SRVRecord {
    final int priority;
    final int weight;
    final int port;
    final String target;

    SRVRecord(int priority, int weight, int port, String target) {
      this.priority = priority;
      this.weight = weight;
      this.port = port;
      this.target = target;
    }
  }
}
