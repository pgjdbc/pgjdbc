/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.ssl;

import org.postgresql.PGProperty;
import org.postgresql.core.SocketFactoryFactory;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.security.NoSuchProviderException;
import java.util.Properties;

class SSLFactoryTest {

  @Test
  void TestDefault() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.SSL_FACTORY.set(props, "org.postgresql.ssl.DefaultJavaSSLFactory");

    try {
      SocketFactoryFactory.getSslSocketFactory(props);
    } catch (Throwable t) {
      /* ignore NoSuchProviderException */
      if (!(t instanceof PSQLException)
              || (!(t.getCause() instanceof InvocationTargetException))
              || (!(t.getCause().getCause() instanceof PSQLException))
              || (!(t.getCause().getCause().getCause() instanceof NoSuchProviderException))
      ) {
        throw t;
      }
    }

  }

  @Test
  void TestKeychain() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.SSL_FACTORY.set(props, "org.postgresql.ssl.KeychainSSLFactory");

    try {
      SocketFactoryFactory.getSslSocketFactory(props);
    } catch (Throwable t) {
      /* ignore NoSuchProviderException */
      if (!(t instanceof PSQLException)
              || (!(t.getCause() instanceof InvocationTargetException))
              || (!(t.getCause().getCause() instanceof PSQLException))
              || (!(t.getCause().getCause().getCause() instanceof NoSuchProviderException))
      ) {
        throw t;
      }
    }

  }

  @Test
  void TestMSCurrentUserSSLFactory() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.SSL_FACTORY.set(props, "org.postgresql.ssl.MSCAPISSLFactory");

    try {
      SocketFactoryFactory.getSslSocketFactory(props);
    } catch (Throwable t) {
      /* ignore NoSuchProviderException */
      if (!(t instanceof PSQLException)
              || (!(t.getCause() instanceof InvocationTargetException))
              || (!(t.getCause().getCause() instanceof PSQLException))
              || (!(t.getCause().getCause().getCause() instanceof NoSuchProviderException))
      ) {
        throw t;
      }
    }

  }

  @Test
  void TestMSLocalMachineSSLFactory() throws Exception {
    TestUtil.assumeSslTestsEnabled();

    Properties props = new Properties();
    PGProperty.SSL_FACTORY.set(props, "org.postgresql.ssl.MSCAPILocalMachineSSLFactory");

    try {
      SocketFactoryFactory.getSslSocketFactory(props);
    } catch (Throwable t) {
      /* ignore NoSuchProviderException */
      if (!(t instanceof PSQLException)
              || (!(t.getCause() instanceof InvocationTargetException))
              || (!(t.getCause().getCause() instanceof PSQLException))
              || (!(t.getCause().getCause().getCause() instanceof NoSuchProviderException))
      ) {
        throw t;
      }
    }

  }

}
