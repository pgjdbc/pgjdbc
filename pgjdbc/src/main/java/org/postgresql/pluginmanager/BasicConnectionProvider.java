/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.pluginmanager;

import org.postgresql.core.BaseConnection;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.util.HostSpec;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * This class is a basic implementation of IConnectionProvider interface. It creates and returns an
 * instance of PgConnection.
 */
public class BasicConnectionProvider implements ConnectionProvider {

  /**
   * Called to create a connection.
   *
   * @param hostSpecs The HostSpec containing the host-port information for the host to connect to
   * @param props     The Properties to use for the connection
   * @param url       The connection URL
   * @return {@link Connection} resulting from the given connection information
   * @throws SQLException if an error occurs
   */
  @Override
  public BaseConnection connect(HostSpec[] hostSpecs, Properties props, @Nullable String url)
      throws SQLException {
    return new PgConnection(hostSpecs, props, url == null ? "" : url);
  }
}
