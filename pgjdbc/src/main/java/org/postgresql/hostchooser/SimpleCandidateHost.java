/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.HostSpec;


/**
 * Candidate host to be connected, which use targetServerType to check whether allow connecting to.
 */
public class SimpleCandidateHost implements CandidateHost {
  private final HostSpec hostSpec;
  private final HostRequirement targetServerType;

  protected SimpleCandidateHost(HostSpec hostSpec, HostRequirement targetServerType) {
    this.hostSpec = hostSpec;
    this.targetServerType = targetServerType;
  }

  @Override
  public HostSpec getHostSpec() {
    return hostSpec;
  }

  @Override
  public boolean allowConnectingTo(HostStatus status) {
    return targetServerType.allowConnectingTo(status);
  }
}
