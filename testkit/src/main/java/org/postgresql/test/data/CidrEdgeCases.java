/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Edge-case {@code cidr} (network specification) values: the all-networks default routes, host-only
 * prefixes, and typical IPv4/IPv6 networks.
 *
 * <p>Read-only ({@link EdgeCase#value()} is {@code null}).
 */
public final class CidrEdgeCases {
  /** Every case, in a stable order. */
  public static final List<EdgeCase> ALL = Collections.unmodifiableList(all());

  private CidrEdgeCases() {
  }

  private static List<EdgeCase> all() {
    List<EdgeCase> out = new ArrayList<>();
    out.add(at("ipv4_default", "0.0.0.0/0"));
    out.add(at("ipv4_eight", "10.0.0.0/8"));
    out.add(at("ipv4_twenty_four", "192.168.1.0/24"));
    out.add(at("ipv4_host", "192.168.1.1/32"));
    out.add(at("ipv6_default", "::/0"));
    out.add(at("ipv6_thirty_two", "2001:db8::/32"));
    out.add(at("ipv6_host", "2001:db8::1/128"));
    return out;
  }

  private static EdgeCase at(String name, String literal) {
    return new EdgeCase(name, literal, null);
  }
}
