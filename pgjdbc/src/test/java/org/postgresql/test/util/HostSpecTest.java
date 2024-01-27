/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * @author Joe Kutner on 10/19/17.
 *         Twitter: @codefinger
 */
class HostSpecTest {

  @AfterEach
  void cleanup() {
    System.clearProperty("socksProxyHost");
    System.clearProperty("socksProxyPort");
    System.clearProperty("socksNonProxyHosts");
  }

  @Test
  void shouldResolve() throws Exception {
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  void shouldResolveWithEmptySocksProxyHost() throws Exception {
    System.setProperty("socksProxyHost", "");
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  void shouldResolveWithWhiteSpaceSocksProxyHost() throws Exception {
    System.setProperty("socksProxyHost", " ");
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  void shouldResolveWithSocksProxyHost() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    HostSpec hostSpec = new HostSpec("example.com", 5432);
    assertFalse(hostSpec.shouldResolve());
  }

  @Test
  void shouldResolveWithSocksProxyHostWithLocalhost() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  void shouldResolveWithSocksNonProxyHost() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    System.setProperty("socksNonProxyHosts", "example.com");
    HostSpec hostSpec = new HostSpec("example.com", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  void shouldResolveWithSocksNonProxyHosts() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    System.setProperty("socksNonProxyHosts", "example.com|localhost");
    HostSpec hostSpec = new HostSpec("example.com", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  void shouldResolveWithSocksNonProxyHostsNotMatching() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    System.setProperty("socksNonProxyHosts", "example.com|localhost");
    HostSpec hostSpec = new HostSpec("example.org", 5432);
    assertFalse(hostSpec.shouldResolve());
  }

  @Test
  void shouldReturnEmptyLocalAddressBind() throws Exception {
    HostSpec hostSpec = new HostSpec("example.org", 5432);
    assertNull(hostSpec.getLocalSocketAddress());
  }

  @Test
  void shouldReturnLocalAddressBind() throws Exception {
    HostSpec hostSpec = new HostSpec("example.org", 5432, "foo");
    assertEquals("foo", hostSpec.getLocalSocketAddress());
  }
}
