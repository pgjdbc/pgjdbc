/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc3;

import java.sql.*;
import junit.framework.TestCase;

import org.postgresql.jdbc2.AbstractJdbc2Connection;
import org.postgresql.test.TestUtil;

public class SendRecvBufferSizeTest extends TestCase {

    private Connection _conn;

    public SendRecvBufferSizeTest(String name) {
        super(name);
    }

    protected void setUp() throws Exception {
		System.setProperty("sendBufferSize", "1024");
		System.setProperty("receiveBufferSize","1024");
		
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE TEMP TABLE hold(a int)");
        stmt.execute("INSERT INTO hold VALUES (1)");
        stmt.execute("INSERT INTO hold VALUES (2)");
        stmt.close();
    }

    protected void tearDown() throws SQLException {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP TABLE hold");
        stmt.close();
        TestUtil.closeDB(_conn);
    }

    
	// dummy test
	public void testSelect() throws SQLException {
		Statement stmt = _conn.createStatement();
		stmt.execute("select * from hold");
		stmt.close();
	}
	
}