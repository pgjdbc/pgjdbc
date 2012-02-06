/*-------------------------------------------------------------------------
*
* Copyright (c) 2010-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import java.sql.*;
import java.util.Properties;
import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

public class ConnectionTest extends TestCase {

    private Connection _conn;

    public ConnectionTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();
    }

    protected void tearDown() throws SQLException {
        TestUtil.closeDB(_conn);
    }

    private String getAppName() throws SQLException {
        Statement stmt = _conn.createStatement();
        ResultSet rs = stmt.executeQuery("SHOW application_name");
        rs.next();
        String appName = rs.getString(1);
        rs.close();
        stmt.close();
        return appName;
    }

    public void testSetAppName() throws SQLException
    {
        if (!TestUtil.haveMinimumServerVersion(_conn, "9.0"))
            return;

        _conn.setClientInfo("ApplicationName", "my app");
        assertEquals("my app", getAppName());
        assertEquals("my app", _conn.getClientInfo("ApplicationName"));
        assertEquals("my app", _conn.getClientInfo().getProperty("ApplicationName"));
    }

    public void testSetAppNameProps() throws SQLException
    {
        if (!TestUtil.haveMinimumServerVersion(_conn, "9.0"))
            return;

        Properties props = new Properties();
        props.put("ApplicationName", "my app");
        _conn.setClientInfo(props);
        assertEquals("my app", getAppName());
        assertEquals("my app", _conn.getClientInfo("ApplicationName"));
        assertEquals("my app", _conn.getClientInfo().getProperty("ApplicationName"));
    }

}
