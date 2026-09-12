/*
 * Copyright (c) 2025, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.socketfactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import javax.net.SocketFactory;

/**
 * Verifies that connecting over a Unix domain socket via
 * {@code org.postgresql.unixsocket.UnixDomainSocketFactory} works.
 *
 * <p>The socket query test only runs when {@code -DdomainSocketDir=<dir>} points at the directory
 * that holds the server socket and {@code -DdomainSocketMode} is {@code smoke} or {@code all} (the
 * CI {@code domain_socket}/{@code domain_socket_mode} axes set these), and the JVM is new enough for
 * the multi-release implementation to be active.</p>
 */
class UnixDomainSocketFactoryTest {

  @Test
  void selectOverUnixDomainSocket() throws Exception {
    String dir = TestUtil.getDomainSocketDir();
    assumeTrue(dir != null && !"none".equals(TestUtil.getDomainSocketMode()),
        "Set -DdomainSocketDir=<dir> and -DdomainSocketMode in {smoke, all} to enable these tests");
    assumeTrue(hasUnixDomainSocketAddress(),
        "Unix domain sockets require Java 16 or later");

    Properties props = new Properties();
    props.put(PGProperty.SOCKET_FACTORY.getName(),
        "org.postgresql.unixsocket.UnixDomainSocketFactory");
    props.put(PGProperty.SOCKET_FACTORY_ARG.getName(), dir);

    try (Connection conn = TestUtil.openDB(props);
         Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT 1")) {
      assertEquals(true, rs.next());
      assertEquals(1, rs.getInt(1));
    }
  }

  /**
   * Exercises the factory without a server: it must build an unconnected socket. This runs on any
   * Java 16+ JVM (no {@code domain_socket} directory required), so it also covers the multi-release
   * implementation in the coverage job.
   */
  @Test
  void createsUnconnectedSocket() throws Exception {
    assumeTrue(hasUnixDomainSocketAddress(),
        "Unix domain sockets require Java 16 or later");

    Properties props = new Properties();
    props.put(PGProperty.SOCKET_FACTORY_ARG.getName(), "/var/run/postgresql");
    SocketFactory factory;
    try {
      factory = new org.postgresql.unixsocket.UnixDomainSocketFactory(props);
    } catch (UnsupportedOperationException e) {
      // The multi-release implementation is not active, so the base stub was loaded. This happens
      // when tests run against exploded classes rather than the JAR (for example the Maven
      // source-distribution build), where META-INF/versions/17 is not consulted.
      assumeTrue(false, "Multi-release Unix domain socket implementation is not active");
      return;
    }
    try (Socket socket = factory.createSocket()) {
      assertFalse(socket.isConnected(), "createSocket() must return an unconnected socket");
    }
  }

  private static boolean hasUnixDomainSocketAddress() {
    // java.net.UnixDomainSocketAddress is available on Java 16 and later
    try {
      Class.forName("java.net.UnixDomainSocketAddress", false,
          UnixDomainSocketFactoryTest.class.getClassLoader());
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }
}
