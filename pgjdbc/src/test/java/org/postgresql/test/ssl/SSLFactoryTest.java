/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.SocketFactoryFactory;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Properties;

import javax.net.ssl.SSLSocketFactory;

/**
 * Verifies that the bundled SSLSocketFactory implementations can build an
 * SSLContext from the platform key and trust stores. The native factories only
 * work on the operating system that provides the underlying security provider,
 * so each test runs only on the matching platform and is skipped elsewhere.
 *
 * <p>These tests exercise factory construction only; they do not open a
 * connection, so they do not require a running server or SSL test setup.</p>
 */
class SSLFactoryTest {

  private static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

  private static SSLSocketFactory build(String factoryClass) throws Exception {
    Properties props = new Properties();
    PGProperty.SSL_FACTORY.set(props, factoryClass);
    return SocketFactoryFactory.getSslSocketFactory(props);
  }

  @Test
  void defaultJavaFactory() throws Exception {
    assertNotNull(build("org.postgresql.ssl.DefaultJavaSSLFactory"));
  }

  @Test
  void keychainFactoryOnMac() throws Exception {
    assumeTrue(OS.contains("mac"), "KeychainSSLFactory requires the macOS Apple provider");
    assertNotNull(build("org.postgresql.ssl.KeychainSSLFactory"));
  }

  @Test
  void mscapiCurrentUserFactoryOnWindows() throws Exception {
    assumeTrue(OS.contains("windows"), "MSCAPISSLFactory requires the Windows SunMSCAPI provider");
    assertNotNull(build("org.postgresql.ssl.MSCAPISSLFactory"));
  }

  @Test
  void mscapiLocalMachineFactoryOnWindows() throws Exception {
    assumeTrue(OS.contains("windows"), "MSCAPILocalMachineSSLFactory requires the Windows SunMSCAPI provider");
    assertNotNull(build("org.postgresql.ssl.MSCAPILocalMachineSSLFactory"));
  }
}
