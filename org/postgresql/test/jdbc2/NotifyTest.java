/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

import org.postgresql.PGNotification;

public class NotifyTest extends TestCase
{
    private Connection conn;

    public NotifyTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        conn = TestUtil.openDB();
    }

    protected void tearDown() throws SQLException
    {
        TestUtil.closeDB(conn);
    }

    public void testNotify() throws SQLException
    {
        Statement stmt = conn.createStatement();
        stmt.executeUpdate("LISTEN mynotification");
        stmt.executeUpdate("NOTIFY mynotification");

        PGNotification notifications[] = ((org.postgresql.PGConnection)conn).getNotifications();
        assertNotNull(notifications);
        assertEquals(1, notifications.length);
        assertEquals("mynotification", notifications[0].getName());
        assertEquals("", notifications[0].getParameter());

        stmt.close();
    }

    public void testNotifyArgument() throws Exception
    {
        if (!TestUtil.haveMinimumServerVersion(conn, "9.0") || TestUtil.isProtocolVersion(conn, 2))
            return;

        Statement stmt = conn.createStatement();
        stmt.executeUpdate("LISTEN mynotification");
        stmt.executeUpdate("NOTIFY mynotification, 'message'");

        PGNotification notifications[] = ((org.postgresql.PGConnection)conn).getNotifications();
        assertNotNull(notifications);
        assertEquals(1, notifications.length);
        assertEquals("mynotification", notifications[0].getName());
        assertEquals("message", notifications[0].getParameter());

        stmt.close();
    }

    public void testAsyncNotify() throws Exception
    {
        Statement stmt = conn.createStatement();
        stmt.executeUpdate("LISTEN mynotification");

        // Notify on a separate connection to get an async notify on the first.
        Connection conn2 = TestUtil.openDB();
        try {
            Statement stmt2 = conn2.createStatement();
            stmt2.executeUpdate("NOTIFY mynotification");
            stmt2.close();
        } finally {
            conn2.close();
        }

        // Wait a bit to let the notify come through..
        try {
            Thread.sleep(2000);
        } catch (InterruptedException ie) {}

        PGNotification notifications[] = ((org.postgresql.PGConnection)conn).getNotifications();
        assertNotNull(notifications);
        assertEquals(1, notifications.length);
        assertEquals("mynotification", notifications[0].getName());
        assertEquals("", notifications[0].getParameter());

        stmt.close();
    }
}
