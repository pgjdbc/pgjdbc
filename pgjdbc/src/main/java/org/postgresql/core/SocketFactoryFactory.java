/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGProperty;
import org.postgresql.exception.PgSqlState;
import org.postgresql.ssl.LibPQFactory;
import org.postgresql.util.GT;
import org.postgresql.util.ObjectFactory;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Properties;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * Instantiates {@link SocketFactory} based on the {@link PGProperty#SOCKET_FACTORY}.
 */
public class SocketFactoryFactory {

  /**
   * Instantiates {@link SocketFactory} based on the {@link PGProperty#SOCKET_FACTORY}.
   *
   * @param info connection properties
   * @return socket factory
   * @throws SQLException if something goes wrong
   */
  public static SocketFactory getSocketFactory(Properties info) throws SQLException {
    // Socket factory
    String socketFactoryClassName = PGProperty.SOCKET_FACTORY.get(info);
    if (socketFactoryClassName == null) {
      return SocketFactory.getDefault();
    }
    try {
      return (SocketFactory) ObjectFactory.instantiate(socketFactoryClassName, info, true,
          PGProperty.SOCKET_FACTORY_ARG.get(info));
    } catch (Exception e) {
      throw new SQLNonTransientConnectionException(
          GT.tr("The SocketFactory class provided {0} could not be instantiated.",
              socketFactoryClassName),
          PgSqlState.CONNECTION_EXCEPTION, e);
    }
  }

  /**
   * Instantiates {@link SSLSocketFactory} based on the {@link PGProperty#SSL_FACTORY}.
   *
   * @param info connection properties
   * @return SSL socket factory
   * @throws SQLException if something goes wrong
   */
  public static SSLSocketFactory getSslSocketFactory(Properties info) throws SQLException {
    String classname = PGProperty.SSL_FACTORY.get(info);
    if (classname == null
        || "org.postgresql.ssl.jdbc4.LibPQFactory".equals(classname)
        || "org.postgresql.ssl.LibPQFactory".equals(classname)) {
      return new LibPQFactory(info);
    }
    try {
      return (SSLSocketFactory) ObjectFactory.instantiate(classname, info, true,
          PGProperty.SSL_FACTORY_ARG.get(info));
    } catch (Exception e) {
      throw new SQLNonTransientConnectionException(
          GT.tr("The SSLSocketFactory class provided {0} could not be instantiated.", classname),
          PgSqlState.CONNECTION_EXCEPTION, e);
    }
  }

}
