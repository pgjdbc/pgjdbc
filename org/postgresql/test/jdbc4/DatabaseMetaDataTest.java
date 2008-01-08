/*-------------------------------------------------------------------------
*
* Copyright (c) 2007, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/test/jdbc4/DatabaseMetaDataTest.java,v 1.1 2007/10/07 19:40:02 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

public class DatabaseMetaDataTest extends TestCase
{

    private Connection _conn;

    public DatabaseMetaDataTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        _conn = TestUtil.openDB();
        TestUtil.dropSequence(_conn, "sercoltest_a_seq");
        TestUtil.createTable(_conn, "sercoltest", "a serial, b int");
    }

    protected void tearDown() throws Exception
    {
        TestUtil.dropSequence(_conn, "sercoltest_a_seq");
        TestUtil.dropTable(_conn, "sercoltest");
        TestUtil.closeDB( _conn );
    }

    public void testGetClientInfoProperties() throws Exception
    {
        DatabaseMetaData dbmd = _conn.getMetaData();

        ResultSet rs = dbmd.getClientInfoProperties();
        assertTrue( !rs.next() );
    }

    public void testGetColumnsForAutoIncrement() throws Exception
    {
        DatabaseMetaData dbmd = _conn.getMetaData();

        ResultSet rs = dbmd.getColumns("%","%","sercoltest", "%");
        assertTrue( rs.next() );
        assertEquals("a", rs.getString("COLUMN_NAME"));
        assertEquals("YES", rs.getString("IS_AUTOINCREMENT"));

        assertTrue( rs.next() );
        assertEquals("b", rs.getString("COLUMN_NAME"));
        assertEquals("NO", rs.getString("IS_AUTOINCREMENT"));

        assertTrue( !rs.next() );
    }

}
