/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.postgresql.util.HostSpec;


/**
 * Candidate host to be connected.
 */
public interface CandidateHost {

  HostSpec getHostSpec();

  boolean allowConnectingTo(HostStatus status);
}
