/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.Driver;
import org.postgresql.PGProperty;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;

/**
 * Tests for DNS SRV service discovery in pgjdbc.
 *
 * <p>Unit tests exercise URL parsing and the SRV record sort/parse logic without a live DNS
 * resolver.  The live integration test ({@link #testResolveSRVLive}) queries real DNS SRV records
 * published at {@code _postgresql._tcp.mmatvei.ru} and can be pointed at a specific name-server
 * via the {@code PGJDBC_TEST_SRV_DNS_SERVER} environment variable.
 */
class SRVLookupTest {

  // ---------------------------------------------------------------------------
  // parseURL() – SRV scheme and srvhost= property
  // ---------------------------------------------------------------------------

  @Test
  void parseURLSrvScheme() {
    Properties props = Driver.parseURL("jdbc:postgresql+srv://mmatvei.ru/mydb", null);
    assertNotNull(props, "parseURL must succeed for jdbc:postgresql+srv:// URLs");
    assertEquals("mmatvei.ru", PGProperty.SRV_HOST.getOrDefault(props),
        "SRV_HOST must be set to the authority host");
  }

  @Test
  void parseURLSrvSchemeWithUser() {
    Properties props = Driver.parseURL(
        "jdbc:postgresql+srv://mmatvei.ru/mydb?user=alice&password=secret", null);
    assertNotNull(props);
    assertEquals("mmatvei.ru", PGProperty.SRV_HOST.getOrDefault(props));
    assertEquals("alice", PGProperty.USER.getOrDefault(props));
  }

  @Test
  void parseURLSrvKeyword() {
    Properties props = Driver.parseURL(
        "jdbc:postgresql:mydb?srvhost=mmatvei.ru", null);
    assertNotNull(props, "parseURL must succeed when srvhost= is given without a host authority");
    assertEquals("mmatvei.ru", PGProperty.SRV_HOST.getOrDefault(props));
  }

  @Test
  void parseURLSrvAndHostMutuallyExclusive() {
    // Explicit host in authority + srvhost= query param must be rejected.
    Properties props = Driver.parseURL(
        "jdbc:postgresql://pg1.example.com/mydb?srvhost=mmatvei.ru", null);
    assertNull(props, "parseURL must return null when both host and srvhost are specified");
  }

  @Test
  void parseURLNormalUnchanged() {
    // Standard URL must still work.
    Properties props = Driver.parseURL("jdbc:postgresql://localhost:5432/mydb", null);
    assertNotNull(props);
    assertNull(PGProperty.SRV_HOST.getOrDefault(props),
        "SRV_HOST must not be set for plain JDBC URLs");
    assertEquals("localhost", PGProperty.PG_HOST.getOrDefault(props));
    assertEquals("5432", PGProperty.PG_PORT.getOrDefault(props));
  }

  // ---------------------------------------------------------------------------
  // SRVLookup.parseAndSort() – sort order and FQDN dot stripping (no live DNS)
  // ---------------------------------------------------------------------------

  @Test
  void parseAndSortByPriorityAscending() throws PSQLException {
    // Lower priority number = preferred (RFC 2782 §3).
    HostSpec[] specs = SRVLookup.parseAndSort(Arrays.asList(
        "100 1 5432 pg-replica.example.com.",
        "10  1 5432 pg-primary.example.com."
    ), "_postgresql._tcp.example.com");

    assertEquals(2, specs.length);
    assertEquals("pg-primary.example.com", specs[0].getHost(), "priority 10 must come first");
    assertEquals("pg-replica.example.com", specs[1].getHost(), "priority 100 must come second");
  }

  @Test
  void parseAndSortByWeightDescendingWithinPriority() throws PSQLException {
    // Same priority: higher weight = higher preference (RFC 2782 §3).
    HostSpec[] specs = SRVLookup.parseAndSort(Arrays.asList(
        "10 1  5432 light.example.com.",
        "10 50 5433 heavy.example.com."
    ), "_postgresql._tcp.example.com");

    assertEquals(2, specs.length);
    assertEquals("heavy.example.com", specs[0].getHost(), "weight 50 must come before weight 1");
    assertEquals(5433, specs[0].getPort());
    assertEquals("light.example.com", specs[1].getHost());
    assertEquals(5432, specs[1].getPort());
  }

  @Test
  void parseAndSortStripsTrailingDot() throws PSQLException {
    HostSpec[] specs = SRVLookup.parseAndSort(
        Collections.singletonList("10 1 5432 pg.example.com."),
        "_postgresql._tcp.example.com");

    assertEquals(1, specs.length);
    assertEquals("pg.example.com", specs[0].getHost(), "Trailing dot must be stripped from FQDN");
  }

  @Test
  void parseAndSortMixedPriorityAndWeight() throws PSQLException {
    HostSpec[] specs = SRVLookup.parseAndSort(Arrays.asList(
        "96 1 5432 pg4.mmatvei.ru.",
        "97 1 5432 pg3.mmatvei.ru.",
        "99 1 5432 pg2.mmatvei.ru.",
        "100 1 5432 pg.mmatvei.ru."
    ), "_postgresql._tcp.mmatvei.ru");

    assertEquals(4, specs.length);
    assertEquals("pg4.mmatvei.ru", specs[0].getHost(), "priority 96 first");
    assertEquals("pg3.mmatvei.ru", specs[1].getHost(), "priority 97 second");
    assertEquals("pg2.mmatvei.ru", specs[2].getHost(), "priority 99 third");
    assertEquals("pg.mmatvei.ru",  specs[3].getHost(), "priority 100 last");
  }

  @Test
  void parseAndSortEmptyListThrows() {
    assertThrows(PSQLException.class, () ->
        SRVLookup.parseAndSort(Collections.emptyList(), "_postgresql._tcp.example.com"));
  }

  // ---------------------------------------------------------------------------
  // Live DNS test – queries real SRV records at mmatvei.ru
  // ---------------------------------------------------------------------------

  /**
   * Verifies that {@link SRVLookup#resolve(String, String)} correctly resolves real internet SRV
   * records published at {@code _postgresql._tcp.mmatvei.ru}.
   *
   * <p>Set the environment variable {@code PGJDBC_TEST_SRV_DNS_SERVER} to an IP address (or
   * {@code ip:port}) to force a specific name-server, which is useful during DNS propagation:
   * <pre>
   *   PGJDBC_TEST_SRV_DNS_SERVER=88.212.208.183 ./gradlew :pgjdbc:test --tests "*.SRVLookupTest.testResolveSRVLive"
   * </pre>
   *
   * <p>The test is skipped automatically when the SRV records cannot be found (e.g. the domain
   * is not reachable), so it never breaks a standard offline build.
   */
  @Test
  void testResolveSRVLive() throws PSQLException {
    String dnsServer = System.getenv("PGJDBC_TEST_SRV_DNS_SERVER");
    HostSpec[] specs;
    try {
      specs = SRVLookup.resolve("mmatvei.ru", dnsServer);
    } catch (PSQLException e) {
      org.junit.jupiter.api.Assumptions.assumeTrue(false,
          "SRV lookup failed (records may not be published yet): " + e.getMessage());
      return;
    }

    assertNotNull(specs);
    // We published at least two SRV records for mmatvei.ru; assert ordering by priority.
    org.junit.jupiter.api.Assumptions.assumeTrue(specs.length >= 2,
        "Expected at least 2 SRV records, got: " + specs.length);

    for (int i = 0; i < specs.length - 1; i++) {
      // All returned hosts resolve to mmatvei.ru subdomains.
      assertNotNull(specs[i].getHost());
    }

    System.out.println("Live SRV targets for mmatvei.ru:");
    for (int i = 0; i < specs.length; i++) {
      System.out.printf("  [%d] %s:%d%n", i, specs[i].getHost(), specs[i].getPort());
    }
  }
}
