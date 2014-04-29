/*-------------------------------------------------------------------------
*
* Copyright (c) 2010-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc4;

import java.sql.*;
import junit.framework.TestCase;
import org.postgresql.test.TestUtil;

public class SchemaTest extends TestCase
{

    private Connection _conn;

    public SchemaTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        _conn = TestUtil.openDB();
        Statement stmt = _conn.createStatement();
        stmt.execute("CREATE SCHEMA schema1");
        stmt.execute("CREATE SCHEMA schema2");
        TestUtil.createTable(_conn, "schema1.table1", "id integer");
        TestUtil.createTable(_conn, "schema2.table2", "id integer");
    }

    protected void tearDown() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        stmt.execute("DROP SCHEMA schema1 CASCADE");
        stmt.execute("DROP SCHEMA schema2 CASCADE");
        TestUtil.closeDB(_conn);
    }

    /**
     * Test that what you set is what you get
     */
    public void testGetSetSchema() throws SQLException
    {
        _conn.setSchema("schema1");
        assertEquals("schema1", _conn.getSchema());
        _conn.setSchema("schema2");
        assertEquals("schema2", _conn.getSchema());
    }

    /**
     * Test that setting the schema allows to access objects of this schema
     * without prefix, hide objects from other schemas but doesn't prevent
     * to prefix-access to them.
     */
    public void testUsingSchema() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        try
        {
            try
            {
                _conn.setSchema("schema1");
                stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
                stmt.executeQuery(TestUtil.selectSQL("schema2.table2", "*"));
                try
                {
                    stmt.executeQuery(TestUtil.selectSQL("table2", "*"));
                    fail("Objects of schema2 should not be visible without prefix");
                }
                catch (SQLException e)
                {
                    // expected
                }

                _conn.setSchema("schema2");
                stmt.executeQuery(TestUtil.selectSQL("table2", "*"));
                stmt.executeQuery(TestUtil.selectSQL("schema1.table1", "*"));
                try
                {
                    stmt.executeQuery(TestUtil.selectSQL("table1", "*"));
                    fail("Objects of schema1 should not be visible without prefix");
                }
                catch (SQLException e)
                {
                    // expected
                }
            }
            catch (SQLException e)
            {
                fail("Could not find expected schema elements: " + e.getMessage());
            }
        }
        finally
        {
            try
            {
                stmt.close();
            }
            catch (SQLException e)
            {
            }
        }
    }

    /**
     * Test that get schema returns the schema with the highest priority
     * in the search path
     */
    public void testMultipleSearchPath() throws SQLException
    {
        Statement stmt = _conn.createStatement();
        try
        {
            stmt.execute("SET search_path TO schema1,schema2");
        }
        finally
        {
            try
            {
                stmt.close();
            }
            catch (SQLException e)
            {
            }
        }
        assertEquals("schema1", _conn.getSchema());
    }

}
