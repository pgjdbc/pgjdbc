/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc2/NotifyTest.java,v 1.4 2004/11/09 08:54:42 jurka Exp $
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

    protected void setUp() throws SQLException
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
}
