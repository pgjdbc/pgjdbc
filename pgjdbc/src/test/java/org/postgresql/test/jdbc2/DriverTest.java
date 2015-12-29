/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.Driver;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;
import org.junit.Assert;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;

/*
 * Tests the dynamically created class org.postgresql.Driver
 *
 */
public class DriverTest extends TestCase {

  public DriverTest(String name) {
    super(name);
  }

  /*
   * This tests the acceptsURL() method with a couple of well and poorly
   * formed jdbc urls.
   */
  public void testAcceptsURL() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    // Load the driver (note clients should never do it this way!)
    org.postgresql.Driver drv = new org.postgresql.Driver();
    assertNotNull(drv);

    // These are always correct
    verifyUrl(drv, "jdbc:postgresql:test", "localhost", "5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost/test", "localhost", "5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost:5432/test", "localhost", "5432", "test");
    verifyUrl(drv, "jdbc:postgresql://127.0.0.1/anydbname", "127.0.0.1", "5432", "anydbname");
    verifyUrl(drv, "jdbc:postgresql://127.0.0.1:5433/hidden", "127.0.0.1", "5433", "hidden");
    verifyUrl(drv, "jdbc:postgresql://[::1]:5740/db", "[::1]", "5740", "db");

    // Badly formatted url's
    assertTrue(!drv.acceptsURL("jdbc:postgres:test"));
    assertTrue(!drv.acceptsURL("postgresql:test"));
    assertTrue(!drv.acceptsURL("db"));
    assertTrue(!drv.acceptsURL("jdbc:postgresql://localhost:5432a/test"));

    // failover urls
    verifyUrl(drv, "jdbc:postgresql://localhost,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5432,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://localhost:5433,127.0.0.1:5432/test", "localhost,127.0.0.1",
        "5433,5432", "test");
    verifyUrl(drv, "jdbc:postgresql://[::1],[::1]:5432/db", "[::1],[::1]", "5432,5432", "db");
    verifyUrl(drv, "jdbc:postgresql://[::1]:5740,127.0.0.1:5432/db", "[::1],127.0.0.1", "5740,5432",
        "db");
  }

  private void verifyUrl(Driver drv, String url, String hosts, String ports, String dbName)
      throws Exception {
    assertTrue(url, drv.acceptsURL(url));
    Method parseMethod =
        drv.getClass().getDeclaredMethod("parseURL", new Class[]{String.class, Properties.class});
    parseMethod.setAccessible(true);
    Properties p = (Properties) parseMethod.invoke(drv, new Object[]{url, null});
    assertEquals(url, dbName, p.getProperty("PGDBNAME"));
    assertEquals(url, hosts, p.getProperty("PGHOST"));
    assertEquals(url, ports, p.getProperty("PGPORT"));
  }

  /**
   * Tests the connect method by connecting to the test database
   */
  public void testConnect() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    // Test with the url, username & password
    Connection con =
        DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(con);
    con.close();

    // Test with the username in the url
    con = DriverManager.getConnection(
        TestUtil.getURL() + "&user=" + TestUtil.getUser() + "&password=" + TestUtil.getPassword());
    assertNotNull(con);
    con.close();

    // Test with failover url
  }

  /**
   * Tests that pgjdbc performs connection failover if unable to connect to the first host in the
   * URL.
   *
   * @throws Exception if something wrong happens
   */
  public void testConnectFailover() throws Exception {
    String url =
        "jdbc:postgresql://invalidhost.not.here," + TestUtil.getServer() + ":" + TestUtil.getPort()
            + "/" + TestUtil.getDatabase() + "?connectTimeout=5";
    Connection con = DriverManager.getConnection(url, TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(con);
    con.close();
  }

  /*
   * Test that the readOnly property works.
   */
  public void testReadOnly() throws Exception {
    TestUtil.initDriver(); // Set up log levels, etc.

    Connection con =
        DriverManager.getConnection(TestUtil.getURL() + "&readOnly=true", TestUtil.getUser(),
            TestUtil.getPassword());
    assertNotNull(con);
    assertTrue(con.isReadOnly());
    con.close();

    con = DriverManager.getConnection(TestUtil.getURL() + "&readOnly=false", TestUtil.getUser(),
        TestUtil.getPassword());
    assertNotNull(con);
    assertFalse(con.isReadOnly());
    con.close();

    con =
        DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());
    assertNotNull(con);
    assertFalse(con.isReadOnly());
    con.close();
  }

  public void testRegistration() throws Exception {
    TestUtil.initDriver();
    ArrayList<java.sql.Driver> drivers;

    // Driver is initially registered because it is automatically done when class is loaded
    Assert.assertTrue(org.postgresql.Driver.isRegistered());

    drivers = Collections.list(DriverManager.getDrivers());
    searchInstanceOf:
    {

      for (java.sql.Driver driver : drivers) {
        if (driver instanceof org.postgresql.Driver) {
          break searchInstanceOf;
        }
      }
      Assert.fail("Driver has not been found in DriverManager's list but it should be registered");
    }

    // Deregister the driver
    Driver.deregister();
    Assert.assertFalse(Driver.isRegistered());

    drivers = Collections.list(DriverManager.getDrivers());
    for (java.sql.Driver driver : drivers) {
      if (driver instanceof org.postgresql.Driver) {
        Assert.fail(
            "Driver should be deregistered but it is still present in DriverManager's list");
      }
    }

    // register again the driver
    Driver.register();
    Assert.assertTrue(Driver.isRegistered());

    drivers = Collections.list(DriverManager.getDrivers());
    for (java.sql.Driver driver : drivers) {
      if (driver instanceof org.postgresql.Driver) {
        return;
      }
    }
    Assert.fail("Driver has not been found in DriverManager's list but it should be registered");
  }
}
