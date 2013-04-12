/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2013, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

/*
 * TestCase to test the internal functionality of org.postgresql.jdbc2.DatabaseMetaData
 *
 */

public class SearchPathLookupTest extends TestCase
{

    private Connection con;
    /*
     * Constructor
     */
    public SearchPathLookupTest(String name)
    {
        super(name);
    }

    public void testSearchPathNormalLookup() throws Exception
    {
        con = TestUtil.openDB();
        Statement stmt = con.createStatement();
        try {
            TestUtil.createSchema( con, "first_schema" );
            TestUtil.createTable( con, "first_schema.x", "first_schema_field_n int4");
            TestUtil.createSchema( con, "second_schema" );
            TestUtil.createTable( con, "second_schema.x", "second_schema_field_n text");
            TestUtil.createSchema( con, "third_schema" );
            TestUtil.createTable( con, "third_schema.x", "third_schema_field_n float");
            TestUtil.createSchema( con, "last_schema" );
            TestUtil.createTable( con, "last_schema.x", "last_schema_field_n text");
            stmt.execute("SET search_path TO third_schema;");
            DatabaseMetaData dbmd = con.getMetaData();
            ResultSet rs = dbmd.getColumns("", "", "x", "");
            assertTrue(rs.next());
            assertEquals("third_schema_field_n", rs.getString("COLUMN_NAME"));
            assertTrue(!rs.next());
            TestUtil.dropSchema( con, "first_schema" );
            TestUtil.dropSchema( con, "second_schema" );
            TestUtil.dropSchema( con, "third_schema" );
            TestUtil.dropSchema( con, "last_schema" );
        } finally {
            if ( stmt != null ) stmt.close();
            TestUtil.closeDB( con );
        }
    }
    
    /* -- TODO: make this test work 
    public void testSearchPathBackwardsCompatibleLookup() throws Exception
    {
        con = TestUtil.openDB();
        try {
            TestUtil.createSchema( con, "first_schema" );
            TestUtil.createTable( con, "first_schema.x", "first_schema_field int4");
            TestUtil.createSchema( con, "second_schema" );
            TestUtil.createTable( con, "second_schema.x", "second_schema_field text");
            try {
                DatabaseMetaData dbmd = con.getMetaData();
                ResultSet rs = dbmd.getColumns("", "", "x", "");
                assertTrue(rs.next());
                assertEquals("second_schema_field", rs.getString("COLUMN_NAME"));
                assertTrue(!rs.next());
            } finally {
                TestUtil.dropSchema( con, "first_schema" );
                TestUtil.dropSchema( con, "second_schema" );
            }
        } finally {
            TestUtil.closeDB( con );
        }
    }
    */
}
