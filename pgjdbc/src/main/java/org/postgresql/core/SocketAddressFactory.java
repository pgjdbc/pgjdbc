package org.postgresql.core;

import org.postgresql.util.HostSpec;

import java.net.SocketAddress;

public interface SocketAddressFactory {

  SocketAddress create(HostSpec host);
}
