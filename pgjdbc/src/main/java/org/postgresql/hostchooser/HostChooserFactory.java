/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.HostSpec;

import org.postgresql.util.PGProperties;

/**
 * Chooses a {@link HostChooser} instance based on the number of hosts and properties.
 */
public class HostChooserFactory {

  public static HostChooser createHostChooser(HostSpec[] hostSpecs,
      HostRequirement targetServerType, PGProperties info) {
    if (hostSpecs.length == 1) {
      return new SingleHostChooser(hostSpecs[0]);
    }
    return new MultiHostChooser(hostSpecs, targetServerType, info);
  }
}
