/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.Driver;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.lang.reflect.Method;
import java.sql.*;
import java.util.Properties;

/*
 * Tests the dynamically created class org.postgresql.Driver
 *
 */
public class DriverTest extends TestCase
{

    public DriverTest(String name)
    {
        super(name);
    }

    /*
     * This tests the acceptsURL() method with a couple of well and poorly
     * formed jdbc urls.
     */
    public void testAcceptsURL() throws Exception
    {
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
        verifyUrl(drv, "jdbc:postgresql://localhost,127.0.0.1:5432/test", "localhost,127.0.0.1", "5432,5432", "test");
        verifyUrl(drv, "jdbc:postgresql://localhost:5433,127.0.0.1:5432/test", "localhost,127.0.0.1", "5433,5432", "test");
        verifyUrl(drv, "jdbc:postgresql://[::1],[::1]:5432/db", "[::1],[::1]", "5432,5432", "db");
        verifyUrl(drv, "jdbc:postgresql://[::1]:5740,127.0.0.1:5432/db", "[::1],127.0.0.1", "5740,5432", "db");
    }

    private void verifyUrl(Driver drv, String url, String hosts, String ports, String dbName) throws Exception {
        assertTrue(url, drv.acceptsURL(url));
        Method parseMethod = drv.getClass().getDeclaredMethod("parseURL", new Class[]{String.class, Properties.class});
        parseMethod.setAccessible(true);
        Properties p = (Properties) parseMethod.invoke(drv, new Object[]{url, null});
        assertEquals(url, dbName, p.getProperty("PGDBNAME"));
        assertEquals(url, hosts, p.getProperty("PGHOST"));
        assertEquals(url, ports, p.getProperty("PGPORT"));
    }

    /*
     * Tests parseURL (internal)
     */
    /*
     * Tests the connect method by connecting to the test database
     */
    public void testConnect() throws Exception
    {
        TestUtil.initDriver(); // Set up log levels, etc.

        // Test with the url, username & password
        Connection con = DriverManager.getConnection(TestUtil.getURL(), TestUtil.getUser(), TestUtil.getPassword());
        assertNotNull(con);
        con.close();

        // Test with the username in the url
        con = DriverManager.getConnection(TestUtil.getURL() + "&user=" + TestUtil.getUser() + "&password=" + TestUtil.getPassword());
        assertNotNull(con);
        con.close();
        
        // Test with failover url
        String url = "jdbc:postgresql://invalidhost.not.here," + TestUtil.getServer() + ":" + TestUtil.getPort() + "/" + TestUtil.getDatabase();
        con = DriverManager.getConnection(url, TestUtil.getUser(), TestUtil.getPassword());
        assertNotNull(con);
        con.close();

    }
}
