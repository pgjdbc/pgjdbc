package org.postgresql.test.socketfactory;

import java.sql.Connection;
import java.util.Properties;

import junit.framework.TestCase;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

public class SocketFactoryTestSuite extends TestCase {

  private static final String STRING_ARGUMENT = "name of a socket";

  private Connection _conn;

  @Before
  public void setUp() throws Exception {
    Properties properties = new Properties();
    properties.put(PGProperty.SOCKET_FACTORY.getName(), CustomSocketFactory.class.getName());
    properties.put(PGProperty.SOCKET_FACTORY_ARG.getName(), STRING_ARGUMENT);
    _conn = TestUtil.openDB(properties);
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.closeDB(_conn);
  }

  /**
   * Test custom socket factory
   */
  @Test
  public void testDatabaseMetaData() throws Exception {
    assertNotNull("Custom socket factory not null", CustomSocketFactory.getInstance());
    assertEquals(STRING_ARGUMENT, CustomSocketFactory.getInstance().getArgument());
    assertEquals(1, CustomSocketFactory.getInstance().getSocketCreated());
  }

}
