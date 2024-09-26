/*
 * Copyright (c) 2014, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.hostchooser;

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
  void registerFailure(String host, Exception ex);

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
   * @return true or false.
   */
  boolean isHostDrainingConnections(String host);

  /**
   * Api to ask HostChooser for the amount of time it should
   * wait for a successful connection to this host before moving
   * to the next preferred host
   * It is an opportunity for the HostChooser impementation to specify
   * timeout on granular level for each host. For example hosts which are in the
   * same network can have a smaller connection timeouts whereas hosts which are
   * geographically far off can have a larger timeout.
   *
   * @return timeout value in milliseconds
   */
  long getConnectionTimeout(String host);

  boolean isInbuilt();
  /**
   * APIs related to metrics can be exposed (not finalised)
   */

}
