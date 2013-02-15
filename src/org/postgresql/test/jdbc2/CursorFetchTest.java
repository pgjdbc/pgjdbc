/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import java.sql.*;

import junit.framework.TestCase;

import org.postgresql.test.TestUtil;

/*
 *  Tests for using non-zero setFetchSize().
 */
public class CursorFetchTest extends TestCase
{
    private Connection con;

    public CursorFetchTest(String name)
    {
        super(name);
    }

    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        TestUtil.createTable(con, "test_fetch", "value integer");
        con.setAutoCommit(false);
    }

    protected void tearDown() throws Exception
    {
        if (!con.getAutoCommit())
            con.rollback();

        con.setAutoCommit(true);
        TestUtil.dropTable(con, "test_fetch");
        TestUtil.closeDB(con);
    }

    protected void createRows(int count) throws Exception
    {
        PreparedStatement stmt = con.prepareStatement("insert into test_fetch(value) values(?)");
        for (int i = 0; i < count; ++i)
        {
            stmt.setInt(1, i);
            stmt.executeUpdate();
        }
    }

    // Test various fetchsizes.
    public void testBasicFetch() throws Exception
    {
        createRows(100);

        PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
        int[] testSizes = { 0, 1, 49, 50, 51, 99, 100, 101 };
        for (int i = 0; i < testSizes.length; ++i)
        {
            stmt.setFetchSize(testSizes[i]);
            assertEquals(testSizes[i], stmt.getFetchSize());

            ResultSet rs = stmt.executeQuery();
            assertEquals(testSizes[i], rs.getFetchSize());

            int count = 0;
            while (rs.next())
            {
                assertEquals("query value error with fetch size " + testSizes[i], count, rs.getInt(1));
                ++count;
            }

            assertEquals("total query size error with fetch size " + testSizes[i], 100, count);
        }
    }


    // Similar, but for scrollable resultsets.
    public void testScrollableFetch() throws Exception
    {
        createRows(100);

        PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value",
                                 ResultSet.TYPE_SCROLL_INSENSITIVE,
                                 ResultSet.CONCUR_READ_ONLY);

        int[] testSizes = { 0, 1, 49, 50, 51, 99, 100, 101 };
        for (int i = 0; i < testSizes.length; ++i)
        {
            stmt.setFetchSize(testSizes[i]);
            assertEquals(testSizes[i], stmt.getFetchSize());

            ResultSet rs = stmt.executeQuery();
            assertEquals(testSizes[i], rs.getFetchSize());

            for (int j = 0; j <= 50; ++j)
            {
                assertTrue("ran out of rows at position " + j + " with fetch size " + testSizes[i], rs.next());
                assertEquals("query value error with fetch size " + testSizes[i], j, rs.getInt(1));
            }

            int position = 50;
            for (int j = 1; j < 100; ++j)
            {
                for (int k = 0; k < j; ++k)
                {
                    if (j % 2 == 0)
                    {
                        ++position;
                        assertTrue("ran out of rows doing a forward fetch on iteration " + j + "/" + k + " at position " + position + " with fetch size " + testSizes[i], rs.next());
                    }
                    else
                    {
                        --position;
                        assertTrue("ran out of rows doing a reverse fetch on iteration " + j + "/" + k + " at position " + position + " with fetch size " + testSizes[i], rs.previous());
                    }

                    assertEquals("query value error on iteration " + j + "/" + k + " with fetch size " + testSizes[i], position, rs.getInt(1));
                }
            }
        }
    }

    public void testScrollableAbsoluteFetch() throws Exception
    {
        createRows(100);

        PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value",
                                 ResultSet.TYPE_SCROLL_INSENSITIVE,
                                 ResultSet.CONCUR_READ_ONLY);

        int[] testSizes = { 0, 1, 49, 50, 51, 99, 100, 101 };
        for (int i = 0; i < testSizes.length; ++i)
        {
            stmt.setFetchSize(testSizes[i]);
            assertEquals(testSizes[i], stmt.getFetchSize());

            ResultSet rs = stmt.executeQuery();
            assertEquals(testSizes[i], rs.getFetchSize());

            int position = 50;
            assertTrue("ran out of rows doing an absolute fetch at " + position + " with fetch size " + testSizes[i], rs.absolute(position + 1));
            assertEquals("query value error with fetch size " + testSizes[i], position, rs.getInt(1));

            for (int j = 1; j < 100; ++j)
            {
                if (j % 2 == 0)
                    position += j;
                else
                    position -= j;

                assertTrue("ran out of rows doing an absolute fetch at " + position + " on iteration " + j + " with fetchsize" + testSizes[i], rs.absolute(position + 1));
                assertEquals("query value error with fetch size " + testSizes[i], position, rs.getInt(1));
            }
        }
    }

    //
    // Tests for ResultSet.setFetchSize().
    //

    // test one:
    //   set fetchsize = 0
    //   run query (all rows should be fetched)
    //   set fetchsize = 50 (should have no effect)
    //   process results
    public void testResultSetFetchSizeOne() throws Exception
    {
        createRows(100);

        PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
        stmt.setFetchSize(0);
        ResultSet rs = stmt.executeQuery();
        rs.setFetchSize(50); // Should have no effect.

        int count = 0;
        while (rs.next())
        {
            assertEquals(count, rs.getInt(1));
            ++count;
        }

        assertEquals(100, count);
    }

    // test two:
    //   set fetchsize = 25
    //   run query (25 rows fetched)
    //   set fetchsize = 0
    //   process results:
    //     process 25 rows
    //     should do a FETCH ALL to get more data
    //     process 75 rows
    public void testResultSetFetchSizeTwo() throws Exception
    {
        createRows(100);

        PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
        stmt.setFetchSize(25);
        ResultSet rs = stmt.executeQuery();
        rs.setFetchSize(0);

        int count = 0;
        while (rs.next())
        {
            assertEquals(count, rs.getInt(1));
            ++count;
        }

        assertEquals(100, count);
    }

    // test three:
    //   set fetchsize = 25
    //   run query (25 rows fetched)
    //   set fetchsize = 50
    //   process results:
    //     process 25 rows. should NOT hit end-of-results here.
    //     do a FETCH FORWARD 50
    //     process 50 rows
    //     do a FETCH FORWARD 50
    //     process 25 rows. end of results.
    public void testResultSetFetchSizeThree() throws Exception
    {
        createRows(100);

        PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
        stmt.setFetchSize(25);
        ResultSet rs = stmt.executeQuery();
        rs.setFetchSize(50);

        int count = 0;
        while (rs.next())
        {
            assertEquals(count, rs.getInt(1));
            ++count;
        }

        assertEquals(100, count);
    }

    // test four:
    //   set fetchsize = 50
    //   run query (50 rows fetched)
    //   set fetchsize = 25
    //   process results:
    //     process 50 rows.
    //     do a FETCH FORWARD 25
    //     process 25 rows
    //     do a FETCH FORWARD 25
    //     process 25 rows. end of results.
    public void testResultSetFetchSizeFour() throws Exception
    {
        createRows(100);

        PreparedStatement stmt = con.prepareStatement("select * from test_fetch order by value");
        stmt.setFetchSize(50);
        ResultSet rs = stmt.executeQuery();
        rs.setFetchSize(25);

        int count = 0;
        while (rs.next())
        {
            assertEquals(count, rs.getInt(1));
            ++count;
        }

        assertEquals(100, count);
    }

    public void testSingleRowResultPositioning() throws Exception
    {
        String msg;
        createRows(1);

        int[] sizes = { 0, 1, 10 };
        for (int i = 0; i < sizes.length; ++i)
        {
            Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
            stmt.setFetchSize(sizes[i]);

            // Create a one row result set.
            ResultSet rs = stmt.executeQuery("select * from test_fetch order by value");

            msg = "before-first row positioning error with fetchsize=" + sizes[i];
            assertTrue(msg, rs.isBeforeFirst());
            assertTrue(msg, !rs.isAfterLast());
            assertTrue(msg, !rs.isFirst());
            assertTrue(msg, !rs.isLast());

            msg = "row 1 positioning error with fetchsize=" + sizes[i];
            assertTrue(msg, rs.next());

            assertTrue(msg, !rs.isBeforeFirst());
            assertTrue(msg, !rs.isAfterLast());
            assertTrue(msg, rs.isFirst());
            assertTrue(msg, rs.isLast());
            assertEquals(msg, 0, rs.getInt(1));

            msg = "after-last row positioning error with fetchsize=" + sizes[i];
            assertTrue(msg, !rs.next());

            assertTrue(msg, !rs.isBeforeFirst());
            assertTrue(msg, rs.isAfterLast());
            assertTrue(msg, !rs.isFirst());
            assertTrue(msg, !rs.isLast());

            rs.close();
            stmt.close();
        }
    }

    public void testMultiRowResultPositioning() throws Exception
    {
        String msg;

        createRows(100);

        int[] sizes = { 0, 1, 10, 100 };
        for (int i = 0; i < sizes.length; ++i)
        {
            Statement stmt = con.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_UPDATABLE);
            stmt.setFetchSize(sizes[i]);

            ResultSet rs = stmt.executeQuery("select * from test_fetch order by value");
            msg = "before-first row positioning error with fetchsize=" + sizes[i];
            assertTrue(msg, rs.isBeforeFirst());
            assertTrue(msg, !rs.isAfterLast());
            assertTrue(msg, !rs.isFirst());
            assertTrue(msg, !rs.isLast());

            for (int j = 0; j < 100; ++j)
            {
                msg = "row " + j + " positioning error with fetchsize=" + sizes[i];
                assertTrue(msg, rs.next());
                assertEquals(msg, j, rs.getInt(1));

                assertTrue(msg, !rs.isBeforeFirst());
                assertTrue(msg, !rs.isAfterLast());
                if (j == 0)
                    assertTrue(msg, rs.isFirst());
                else
                    assertTrue(msg, !rs.isFirst());

                if (j == 99)
                    assertTrue(msg, rs.isLast());
                else
                    assertTrue(msg, !rs.isLast());
            }

            msg = "after-last row positioning error with fetchsize=" + sizes[i];
            assertTrue(msg, !rs.next());

            assertTrue(msg, !rs.isBeforeFirst());
            assertTrue(msg, rs.isAfterLast());
            assertTrue(msg, !rs.isFirst());
            assertTrue(msg, !rs.isLast());

            rs.close();
            stmt.close();
        }
    }

    // Test odd queries that should not be transformed into cursor-based fetches.
    public void testInsert() throws Exception
    {
        // INSERT should not be transformed.
        PreparedStatement stmt = con.prepareStatement("insert into test_fetch(value) values(1)");
        stmt.setFetchSize(100); // Should be meaningless.
        stmt.executeUpdate();
    }

    public void testMultistatement() throws Exception
    {
        // Queries with multiple statements should not be transformed.

        createRows(100); // 0 .. 99
        PreparedStatement stmt = con.prepareStatement("insert into test_fetch(value) values(100); select * from test_fetch order by value");
        stmt.setFetchSize(10);

        assertTrue(!stmt.execute());       // INSERT
        assertTrue(stmt.getMoreResults()); // SELECT
        ResultSet rs = stmt.getResultSet();
        int count = 0;
        while (rs.next())
        {
            assertEquals(count, rs.getInt(1));
            ++count;
        }

        assertEquals(101, count);
    }

    // if the driver tries to use a cursor with autocommit on
    // it will fail because the cursor will disappear partway
    // through execution
    public void testNoCursorWithAutoCommit() throws Exception
    {
        createRows(10); // 0 .. 9
        con.setAutoCommit(true);
        Statement stmt = con.createStatement();
        stmt.setFetchSize(3);
        ResultSet rs = stmt.executeQuery("SELECT * FROM test_fetch ORDER BY value");
        int count = 0;
        while (rs.next())
        {
            assertEquals(count++, rs.getInt(1));
        }

        assertEquals(10, count);
    }

    public void testGetRow() throws SQLException
    {
        Statement stmt = con.createStatement();
        stmt.setFetchSize(1);
        ResultSet rs = stmt.executeQuery("SELECT 1 UNION SELECT 2 UNION SELECT 3");
        int count = 0;
        while (rs.next())
        {
            count++;
            assertEquals(count, rs.getInt(1));
            assertEquals(count, rs.getRow());
        }
        assertEquals(3, count);
    }

}
