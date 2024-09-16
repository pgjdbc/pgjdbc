/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.PGProperty;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;

import java.util.Properties;

/**
 * Chooses a {@link HostChooser} instance based on the number of hosts and properties.
 */
public class HostChooserFactory {

  public static HostChooser createHostChooser(HostSpec[] hostSpecs,
      HostRequirement targetServerType, Properties info, String url) throws PSQLException {
    String customImplClass = info.getProperty(PGProperty.HOST_CHOOSER_IMPL.getName());
    if (customImplClass != null) {
      return CustomHostChooserManager.getInstance().getOrCreateHostChooser(url, info, customImplClass);
    }
    if (hostSpecs.length == 1) {
      return new SingleHostChooser(hostSpecs[0], targetServerType);
    }
    return new MultiHostChooser(hostSpecs, targetServerType, info);
  }
}
