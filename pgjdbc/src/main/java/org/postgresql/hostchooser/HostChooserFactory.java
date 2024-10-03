/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

/**
 * Checks if a custom {@link HostChooser} implementation is provided. If yes then creates an
 * instance of that else chooses a {@link HostChooser} instance based on the number of hosts
 * and properties.
 */
public class HostChooserFactory {

  public static HostChooser createHostChooser(CustomHostChooserManager.HostChooserUrlProperty key,
      HostSpec[] hostSpecs,
      HostRequirement targetServerType) throws PSQLException {
    if (key.getImpl() != null) {
      return CustomHostChooserManager.getInstance().getOrCreateHostChooser(key.getUrl(),
          key.getProps(),
          key.getImpl(), targetServerType);
    }
    if (hostSpecs.length == 1) {
      return new SingleHostChooser(hostSpecs[0], targetServerType);
    }
    return new MultiHostChooser(hostSpecs, targetServerType, key.getProps());
  }
}
