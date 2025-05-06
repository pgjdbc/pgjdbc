/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Iterator;
import java.util.Properties;

/**
 * Lists connections in preferred order.
 */
public interface HostChooser extends Iterable<CandidateHost> {
  /**
   * Lists connection hosts in preferred order.
   *
   * @return connection hosts in preferred order.
   */
  @Override
  Iterator<CandidateHost> iterator();

  // Enum response for isValid call. The fine grained response may help avoid the sending of
  // empty prepared statement to the server if the plugin is confident about the validity
  // of the connection and the host.
  enum IsValidResponse {
    VALID,
    INVALID,
    RECHECK_VALID
  }

  // New methods
  /**
   * Initialize and setup the custom host chooser
   * The url and the property can be passed as is in the init method
   * The custom implement would know how to use the properties
   */
  void init(String url, Properties info, HostRequirement targetServerType);

  /**
   * Api to inform the HostChooser that a
   * connection to this host has been created
   * Driver calls this after creating a connection to a host was successful
   *
   * @param host to which a connection has been closed.
   */
  void registerSuccess(String host);

  /**
   * Api to inform the HostChooser that the connection
   * attempt failed with the passed SQL exception
   *
   * @param host to which a connection has been closed.
   * @param ex - Exception with which the connection attempt failed
   */
  void registerFailure(String host, @Nullable Exception ex);

  /**
   * Api to inform the HostChooser that a
   * connection to this host has been closed
   * Driver calls this after closing a connection to this host
   *
   * @param host to which a connection has been closed.
   */
  void registerDisconnect(String host);

  /**
   * Api to ask the HostChooser if the host is going
   * in the shutdown mode.
   * When planned shutdown of a server happens let's say during rolling
   * upgrades etc then the custom HostChooser can detect this and make
   * those connections invalid so that pooling solutions like Hikari evict connections
   * to those so that newer connections are created on healthy nodes.
   *
   * @return VALID, INVALID or RECHECK_VALID.
   */
  IsValidResponse isValid(String host);

  /**
   * For future use. The driver may want to do certain things based on whether the
   * the host chooser object is an inbuilt one or an externally provided one.
   * @return true or false
   */
  boolean isInbuilt();
}
