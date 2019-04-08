/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.postgresql.util.HostSpec;

import org.junit.After;
import org.junit.Test;

/**
 * @author Joe Kutner on 10/19/17.
 *         Twitter: @codefinger
 */
public class HostSpecTest {

  @After
  public void cleanup() {
    System.clearProperty("socksProxyHost");
    System.clearProperty("socksProxyPort");
    System.clearProperty("socksNonProxyHosts");
  }

  @Test
  public void testShouldResolve() throws Exception {
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  public void testShouldResolveWithEmptySocksProxyHost() throws Exception {
    System.setProperty("socksProxyHost", "");
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  public void testShouldResolveWithWhiteSpaceSocksProxyHost() throws Exception {
    System.setProperty("socksProxyHost", " ");
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  public void testShouldResolveWithSocksProxyHost() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    HostSpec hostSpec = new HostSpec("example.com", 5432);
    assertFalse(hostSpec.shouldResolve());
  }

  @Test
  public void testShouldResolveWithSocksProxyHostWithLocalhost() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    HostSpec hostSpec = new HostSpec("localhost", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  public void testShouldResolveWithSocksNonProxyHost() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    System.setProperty("socksNonProxyHosts", "example.com");
    HostSpec hostSpec = new HostSpec("example.com", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  public void testShouldResolveWithSocksNonProxyHosts() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    System.setProperty("socksNonProxyHosts", "example.com|localhost");
    HostSpec hostSpec = new HostSpec("example.com", 5432);
    assertTrue(hostSpec.shouldResolve());
  }

  @Test
  public void testShouldResolveWithSocksNonProxyHostsNotMatching() throws Exception {
    System.setProperty("socksProxyHost", "fake-socks-proxy");
    System.setProperty("socksNonProxyHosts", "example.com|localhost");
    HostSpec hostSpec = new HostSpec("example.org", 5432);
    assertFalse(hostSpec.shouldResolve());
  }
}
