/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2003-2016, PostgreSQL Global Development Group
 *
 *
 *-------------------------------------------------------------------------
 */

package org.postgresql.core.v3;

import org.postgresql.core.Logger;
import org.postgresql.core.PGStream;
import org.postgresql.core.v2.SocketFactoryFactory;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest;
import org.postgresql.util.HostSpec;

import org.junit.Before;

import java.sql.SQLException;
import java.util.Properties;

import javax.net.SocketFactory;

/**
 * Test cases to make sure the parameterlist implementation works as expected.
 *
 * @author Jeremy Whiting jwhiting@redhat.com
 *
 */
public class V3ParameterListTests extends BaseTest {

  /**
   * Test to check the merging of two collections of parameters. All elements
   * are kept.
   *
   * @throws SQLException
   *           raised exception if setting parameter fails.
   */
  public void testMergeOfParameterLists() throws SQLException {
    SimpleParameterList s1SPL = new SimpleParameterList(8, pci);
    s1SPL.setIntParameter(1, 1);
    s1SPL.setIntParameter(2, 2);
    s1SPL.setIntParameter(3, 3);
    s1SPL.setIntParameter(4, 4);

    SimpleParameterList s2SPL = new SimpleParameterList(4, pci);
    s2SPL.setIntParameter(1, 5);
    s2SPL.setIntParameter(2, 6);
    s2SPL.setIntParameter(3, 7);
    s2SPL.setIntParameter(4, 8);

    s1SPL.appendAll(s2SPL);
    assertEquals(
        "Expected string representation of values does not match outcome.",
        "<[1 ,2 ,3 ,4 ,5 ,6 ,7 ,8]>", s1SPL.toString());
  }

  public V3ParameterListTests(String test) {
    super(test);
  }

  private ProtocolConnectionImpl pci;

  @Override
  @Before
  protected void setUp() throws Exception {
    SocketFactory socketFactory = SocketFactoryFactory.getSocketFactory(System
        .getProperties());
    HostSpec hostSpec = new HostSpec(TestUtil.getServer(), 5432);
    pci = new ProtocolConnectionImpl(new PGStream(socketFactory, hostSpec), "",
        "", new Properties(), new Logger(), 5000);
  }
}
