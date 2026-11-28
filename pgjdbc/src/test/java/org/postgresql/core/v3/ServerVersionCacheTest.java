/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.core.PGStream;
import org.postgresql.util.HostSpec;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import javax.net.SocketFactory;

class ServerVersionCacheTest {
  /**
   * Some metadata calls check the server version once per row, and the server chooses the length
   * of server_version, so a version that parses to 0 must be parsed only once, like any other.
   */
  @Test
  void versionThatParsesToZeroIsParsedOnce() throws Exception {
    ByteArrayOutputStream script = new ByteArrayOutputStream();
    parameterStatus(script, "server_version", "0.0");
    script.write(new byte[]{'Z', 0, 0, 0, 5, 'I'});
    PGStream stream = new PGStream(new CannedSocketFactory(script.toByteArray()),
        new HostSpec("localhost", 5432), 0, 8192);
    try {
      QueryExecutorImpl executor = new QueryExecutorImpl(stream, 0, new Properties());
      assertEquals(0, executor.getServerVersionNum());
      // A new string shows whether the version is parsed again.
      executor.setServerVersion("17.0");
      assertEquals(0, executor.getServerVersionNum(),
          "a version that parses to 0 must not be parsed again");
    } finally {
      stream.close();
    }
  }

  private static void parameterStatus(ByteArrayOutputStream out, String name, String value) {
    byte[] n = name.getBytes(StandardCharsets.UTF_8);
    byte[] v = value.getBytes(StandardCharsets.UTF_8);
    int length = 4 + n.length + 1 + v.length + 1;
    out.write('S');
    out.write(length >>> 24);
    out.write(length >>> 16);
    out.write(length >>> 8);
    out.write(length);
    out.write(n, 0, n.length);
    out.write(0);
    out.write(v, 0, v.length);
    out.write(0);
  }

  private static class CannedSocketFactory extends SocketFactory {
    private final byte[] script;

    CannedSocketFactory(byte[] script) {
      this.script = script;
    }

    @Override
    public Socket createSocket() {
      return new CannedSocket(script);
    }

    @Override
    public Socket createSocket(String host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) {
      return createSocket();
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress local, int localPort) {
      return createSocket();
    }
  }

  private static class CannedSocket extends Socket {
    private final InputStream in;
    private final OutputStream out = new ByteArrayOutputStream();

    CannedSocket(byte[] script) {
      this.in = new ByteArrayInputStream(script);
    }

    @Override
    public boolean isConnected() {
      return true;
    }

    @Override
    public InputStream getInputStream() {
      return in;
    }

    @Override
    public OutputStream getOutputStream() {
      return out;
    }

    @Override
    public void setTcpNoDelay(boolean on) {
    }

    @Override
    public int getSendBufferSize() {
      return 8192;
    }

    @Override
    public void close() {
    }
  }
}
