package org.postgresql.core;

import org.postgresql.PGProperty;
import org.postgresql.util.GT;
import org.postgresql.util.ObjectFactory;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.util.Properties;

public class SocketAddressFactoryFactory {

  public static SocketAddressFactory getSocketAddressFactory(Properties info) throws PSQLException {
    // Socket factory
    String socketAddressFactoryClassName = PGProperty.SOCKET_ADDRESS_FACTORY.get(info);
    if (socketAddressFactoryClassName == null) {
      return new DefaultSocketAddressFactory();
    }
    try {
      return (SocketAddressFactory) ObjectFactory.instantiate(socketAddressFactoryClassName, info, true,
          PGProperty.SOCKET_ADDRESS_FACTORY_ARG.get(info));
    } catch (Exception e) {
      throw new PSQLException(
          GT.tr("The SocketAddressFactory class provided {0} could not be instantiated.",
                socketAddressFactoryClassName), PSQLState.CONNECTION_FAILURE, e);
    }
  }
}
