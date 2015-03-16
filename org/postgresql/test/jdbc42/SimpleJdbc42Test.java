/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc42;

import java.sql.Connection;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.postgresql.test.TestUtil;

/**
 * Most basic test to check that the right package is compiled
 */
public class SimpleJdbc42Test
{

    private Connection _conn;

    @Before
    public void setUp() throws Exception
    {
        _conn = TestUtil.openDB();
    }

    @After
    public void tearDown() throws Exception
    {
        TestUtil.closeDB(_conn);
    }

    /**
     * Test presence of JDBC 4.2 specific methods
     */
    @Test
    public void testDatabaseMetaData() throws Exception
    {
        _conn.getMetaData().supportsRefCursors();
    }
}
