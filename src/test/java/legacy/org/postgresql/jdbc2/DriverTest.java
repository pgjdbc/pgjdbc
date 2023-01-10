/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.Driver;
import legacy.org.postgresql.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

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
        Driver drv = new Driver();
        assertNotNull(drv);

        // These are always correct
        assertTrue(drv.acceptsURL("jdbc:postgresqllegacy:test"));
        assertTrue(drv.acceptsURL("jdbc:postgresqllegacy://localhost/test"));
        assertTrue(drv.acceptsURL("jdbc:postgresqllegacy://localhost:5432/test"));
        assertTrue(drv.acceptsURL("jdbc:postgresqllegacy://127.0.0.1/anydbname"));
        assertTrue(drv.acceptsURL("jdbc:postgresqllegacy://127.0.0.1:5433/hidden"));
        assertTrue(drv.acceptsURL("jdbc:postgresqllegacy://[::1]:5740/db"));

        // Badly formatted url's
        assertTrue(!drv.acceptsURL("jdbc:postgres:test"));
        assertTrue(!drv.acceptsURL("postgresqllegacy:test"));
        assertTrue(!drv.acceptsURL("db"));

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
    }
}
