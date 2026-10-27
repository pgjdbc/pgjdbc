/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core;

import org.postgresql.PGProperty;
import org.postgresql.core.v3.ConnectionFactoryImpl;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Handles protocol-specific connection setup.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public abstract class ConnectionFactory {
  /**
   * Establishes and initializes a new connection.
   *
   * <p>Accepts the {@link PGProperty#PROTOCOL_VERSION} values {@code 3}, {@code 3.0}, and
   * {@code 3.2}; that property says which protocol version each one requests.</p>
   *
   * @param hostSpecs at least one host and port to connect to; multiple elements for round-robin
   *        failover
   * @param info extra properties controlling the connection; notably, "password" if present
   *        supplies the password to authenticate with.
   * @return the new, initialized, connection
   * @throws SQLException if the connection could not be established, or if
   *         {@link PGProperty#PROTOCOL_VERSION} holds any other value.
   */
  public static QueryExecutor openConnection(HostSpec[] hostSpecs,
      Properties info) throws SQLException {
    String protoName = PGProperty.PROTOCOL_VERSION.getOrDefault(info);

    if (protoName != null && !protoName.isEmpty()
        && (protoName.equalsIgnoreCase("3")
          || protoName.equalsIgnoreCase("3.0")
          || protoName.equalsIgnoreCase("3.2"))) {
      ConnectionFactory connectionFactory = new ConnectionFactoryImpl();
      QueryExecutor queryExecutor = connectionFactory.openConnectionImpl(
          hostSpecs, info);
      if (queryExecutor != null) {
        return queryExecutor;
      }
    }

    throw new PSQLException(
        GT.tr("A connection could not be made using the requested protocol {0}.", protoName),
        PSQLState.CONNECTION_UNABLE_TO_CONNECT);
  }

  /**
   * Implementation of {@link #openConnection}, called once its protocol version check passes.
   * Implemented by subclasses of {@link ConnectionFactory}.
   *
   * @param hostSpecs at least one host and port to connect to; multiple elements for round-robin
   *        failover
   * @param info extra properties controlling the connection; notably, "password" if present
   *        supplies the password to authenticate with.
   * @return the new, initialized, connection, or <code>null</code>, which makes
   *         {@link #openConnection} throw
   * @throws SQLException if the connection could not be established
   */
  public abstract QueryExecutor openConnectionImpl(HostSpec[] hostSpecs, Properties info) throws SQLException;

  /**
   * Safely close the given stream.
   *
   * @param newStream The stream to close.
   */
  protected void closeStream(@Nullable PGStream newStream, Throwable baseThrowable) {
    if (newStream != null) {
      try {
        newStream.close();
      } catch (IOException e) {
        e.addSuppressed(baseThrowable);
      }
    }
  }
}
