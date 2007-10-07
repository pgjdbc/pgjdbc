/*-------------------------------------------------------------------------
*
* Copyright (c) 2007, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL$
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
    }

    protected void tearDown() throws Exception
    {
        TestUtil.closeDB( _conn );
    }

    public void testGetClientInfoProperties() throws Exception
    {
        DatabaseMetaData dbmd = _conn.getMetaData();

        ResultSet rs = dbmd.getClientInfoProperties();
        assertTrue( !rs.next() );
    }

}
