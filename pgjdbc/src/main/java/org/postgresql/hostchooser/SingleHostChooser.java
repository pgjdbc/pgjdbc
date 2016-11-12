/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.HostSpec;

import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;

/**
 * Host chooser that returns the single host.
 */
public class SingleHostChooser implements HostChooser {
  private final Collection<HostSpec> hostSpec;

  public SingleHostChooser(HostSpec hostSpec) {
    this.hostSpec = Collections.singletonList(hostSpec);
  }

  @Override
  public Iterator<HostSpec> iterator() {
    return hostSpec.iterator();
  }
}
