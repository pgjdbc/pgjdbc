/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2.optional;

import static org.junit.Assert.assertEquals;

import org.postgresql.ds.common.BaseDataSource;

import org.junit.Test;

import java.io.IOException;

import javax.naming.NamingException;

/**
* tests that failover urls survive the parse/rebuild roundtrip with and without specific ports
*/
public class BaseDataSourceFailoverUrlsTest {

  private static final String DEFAULT_PORT = "5432";

  @Test
  public void testFullDefault() throws ClassNotFoundException, NamingException, IOException {
    roundTripFromUrl("jdbc:postgresql://server/database", "jdbc:postgresql://server:" + DEFAULT_PORT + "/database");
  }

  @Test
  public void testTwoNoPorts() throws ClassNotFoundException, NamingException, IOException {
    roundTripFromUrl("jdbc:postgresql://server1,server2/database", "jdbc:postgresql://server1:" + DEFAULT_PORT + ",server2:" + DEFAULT_PORT + "/database");
  }

  @Test
  public void testTwoWithPorts() throws ClassNotFoundException, NamingException, IOException {
    roundTripFromUrl("jdbc:postgresql://server1:1234,server2:2345/database", "jdbc:postgresql://server1:1234,server2:2345/database");
  }

  @Test
  public void testTwoFirstPort() throws ClassNotFoundException, NamingException, IOException {
    roundTripFromUrl("jdbc:postgresql://server1,server2:2345/database", "jdbc:postgresql://server1:" + DEFAULT_PORT + ",server2:2345/database");
  }

  @Test
  public void testTwoLastPort() throws ClassNotFoundException, NamingException, IOException {
    roundTripFromUrl("jdbc:postgresql://server1:2345,server2/database", "jdbc:postgresql://server1:2345,server2:" + DEFAULT_PORT + "/database");
  }

  @Test
  public void testNullPorts() {
    BaseDataSource bds = newDS();
    bds.setDatabaseName("database");
    bds.setPortNumbers(null);
    assertUrlWithoutParamsEquals("jdbc:postgresql://localhost/database", bds.getURL());
    assertEquals(0, bds.getPortNumber());
    assertEquals(0, bds.getPortNumbers()[0]);
  }

  @Test
  public void testEmptyPorts() {
    BaseDataSource bds = newDS();
    bds.setDatabaseName("database");
    bds.setPortNumbers(new int[0]);
    assertUrlWithoutParamsEquals("jdbc:postgresql://localhost/database", bds.getURL());
    assertEquals(0, bds.getPortNumber());
    assertEquals(0, bds.getPortNumbers()[0]);
  }

  private BaseDataSource newDS() {
    return new BaseDataSource() {
      @Override
      public String getDescription() {
        return "BaseDataSourceFailoverUrlsTest-DS";
      }
    };
  }

  private void roundTripFromUrl(String in, String expected) throws NamingException, ClassNotFoundException, IOException {
    BaseDataSource bds = newDS();

    bds.setUrl(in);
    assertUrlWithoutParamsEquals(expected, bds.getURL());

    bds.setFromReference(bds.getReference());
    assertUrlWithoutParamsEquals(expected, bds.getURL());

    bds.initializeFrom(bds);
    assertUrlWithoutParamsEquals(expected, bds.getURL());
  }

  private static String jdbcUrlStripParams(String in) {
    return in.replaceAll("\\?.*$", "");
  }

  private static void assertUrlWithoutParamsEquals(String expected, String url) {
    assertEquals(expected, jdbcUrlStripParams(url));
  }
}
