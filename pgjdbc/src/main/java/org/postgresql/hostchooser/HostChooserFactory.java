/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Properties;

/**
 * Chooses a {@link HostChooser} instance based on the number of hosts and properties.
 */
public class HostChooserFactory {

  public static HostChooser createHostChooser(HostSpec[] hostSpecs,
      HostRequirement targetServerType, Properties info) throws PSQLException {
    if (hostSpecs.length != 1) {
      return new MultiHostChooser(hostSpecs, targetServerType, info);
    }
    HostSpec host = hostSpecs[0];
    if (!host.shouldResolve()) {
      return new SingleHostChooser(host, targetServerType);
    }
    final InetAddress[] all;
    try {
      all = InetAddress.getAllByName(host.getHost());
    } catch (UnknownHostException e) {
      throw new PSQLException(GT.tr("The connection attempt failed."),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT, e);
    }
    if (all.length == 1) {
      return new SingleHostChooser(host, targetServerType);
    }
    HostSpec[] resultHosts = new HostSpec[all.length];
    for (int i = 0; i < all.length; i++) {
      InetAddress inetAddress = all[i];
      resultHosts[i] = new HostSpec(inetAddress.getHostAddress(), host.getPort(),
          host.getLocalSocketAddress());
    }
    return new MultiHostChooser(resultHosts, targetServerType, info);
  }
}
