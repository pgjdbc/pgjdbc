package org.postgresql.core;

import org.postgresql.util.HostSpec;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class DefaultSocketAddressFactory implements SocketAddressFactory {

  @Override
  public SocketAddress create(HostSpec hostSpec) {
    return new InetSocketAddress(hostSpec.getHost(), hostSpec.getPort());
  }

}
