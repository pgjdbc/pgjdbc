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
class SingleHostChooser extends AbstractHostChooser {
  private final Collection<CandidateHost> candidateHost;

  SingleHostChooser(HostSpec hostSpec, HostRequirement targetServerType) {
    this.candidateHost = Collections.singletonList(new CandidateHost(hostSpec, targetServerType));
  }

  @Override
  public Iterator<CandidateHost> iterator() {
    return candidateHost.iterator();
  }
}
