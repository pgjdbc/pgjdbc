/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code inet} values: the IPv4 and IPv6 range ends, addresses with and without a netmask, and
 * the all-zero and all-ones hosts.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class InetEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private InetEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("ipv4_zero", "0.0.0.0"));
    out.add(at("ipv4_broadcast", "255.255.255.255"));
    out.add(at("ipv4_host", "192.168.1.1"));
    out.add(at("ipv4_with_mask", "10.0.0.0/8"));
    out.add(at("ipv6_unspecified", "::"));
    out.add(at("ipv6_loopback", "::1"));
    out.add(at("ipv6_host", "2001:db8::1"));
    out.add(at("ipv6_all_ones", "ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"));
    out.add(at("ipv6_with_mask", "2001:db8::/32"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
