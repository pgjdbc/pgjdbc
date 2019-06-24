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

  @Test
  public void testFullDefault() throws ClassNotFoundException, NamingException, IOException {
    doTestFailoverUrl("jdbc:postgresql://server/database", "jdbc:postgresql://server:5432/database");
  }

  @Test
  public void testTwoNoPorts() throws ClassNotFoundException, NamingException, IOException {
    doTestFailoverUrl("jdbc:postgresql://server1,server2/database", "jdbc:postgresql://server1:5432,server2:5432/database");
  }

  @Test
  public void testTwoWithPorts() throws ClassNotFoundException, NamingException, IOException {
    doTestFailoverUrl("jdbc:postgresql://server1:1234,server2:2345/database", "jdbc:postgresql://server1:1234,server2:2345/database");
  }

  @Test
  public void testTwoFirstPort() throws ClassNotFoundException, NamingException, IOException {
    doTestFailoverUrl("jdbc:postgresql://server1,server2:2345/database", "jdbc:postgresql://server1:5432,server2:2345/database");
  }

  @Test
  public void testTwoLastPort() throws ClassNotFoundException, NamingException, IOException {
    doTestFailoverUrl("jdbc:postgresql://server1:2345,server2/database", "jdbc:postgresql://server1:2345,server2:5432/database");
  }

  @Test
  public void testNullPorts() {
    BaseDataSource bds = newDS();
    bds.setPortNumbers(null);
    assertEquals("jdbc:postgresql://server:5432/database", bds.getURL().replaceAll("\\?.*$", ""));
  }

  @Test
  public void testEmptyPorts() {
    BaseDataSource bds = newDS();
    bds.setPortNumbers(new int[0]);
    assertEquals("jdbc:postgresql://server:5432/database", bds.getURL().replaceAll("\\?.*$", ""));
  }

  private BaseDataSource newDS() {
    return new BaseDataSource() {
      @Override
      public String getDescription() {
        return "BaseDataSourceFailoverUrlsTest-DS";
      }
    };
  }

  private void doTestFailoverUrl(String in, String expected) throws NamingException, ClassNotFoundException, IOException {
    BaseDataSource bds = newDS();

    bds.setUrl(in);
    assertEquals(expected, bds.getURL().replaceAll("\\?.*$", ""));

    bds.setFromReference(bds.getReference());
    assertEquals(expected, bds.getURL().replaceAll("\\?.*$", ""));

    bds.initializeFrom(bds);
    assertEquals(expected, bds.getURL().replaceAll("\\?.*$", ""));
  }
}
