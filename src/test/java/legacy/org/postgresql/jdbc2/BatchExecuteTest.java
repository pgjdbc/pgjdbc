/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.TestUtil;
import junit.framework.TestCase;
import java.sql.*;

/* TODO tests that can be added to this test case
 * - SQLExceptions chained to a BatchUpdateException
 * - test PreparedStatement as thoroughly as Statement
 */

/*
 * Test case for Statement.batchExecute()
 */
public class BatchExecuteTest extends TestCase
{

    private Connection con;

    public BatchExecuteTest(String name)
    {
        super(name);
        try
        {
            Class.forName("legacy.org.postgresql.Driver");
        }
        catch( Exception ex){}
    }

    // Set up the fixture for this testcase: a connection to a database with
    // a table for this test.
    protected void setUp() throws Exception
    {
        con = TestUtil.openDB();
        Statement stmt = con.createStatement();

        // Drop the test table if it already exists for some reason. It is
        // not an error if it doesn't exist.
        TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER");

        stmt.executeUpdate("INSERT INTO testbatch VALUES (1, 0)");

        // Generally recommended with batch updates. By default we run all
        // tests in this test case with autoCommit disabled.
        con.setAutoCommit(false);
    }

    // Tear down the fixture for this test case.
    protected void tearDown() throws Exception
    {
        con.setAutoCommit(true);

        TestUtil.dropTable(con, "testbatch");
        TestUtil.closeDB(con);
    }

    public void testSupportsBatchUpdates() throws Exception
    {
        DatabaseMetaData dbmd = con.getMetaData();
        assertTrue(dbmd.supportsBatchUpdates());
    }

    public void testEmptyClearBatch() throws Exception
    {
        Statement stmt = con.createStatement();
        stmt.clearBatch(); // No-op.

        PreparedStatement ps = con.prepareStatement("SELECT ?");
        ps.clearBatch(); // No-op.
    }

    private void assertCol1HasValue(int expected) throws Exception
    {
        Statement getCol1 = con.createStatement();

        ResultSet rs =
            getCol1.executeQuery("SELECT col1 FROM testbatch WHERE pk = 1");
        assertTrue(rs.next());

        int actual = rs.getInt("col1");

        assertEquals(expected, actual);

        assertEquals(false, rs.next());

        rs.close();
        getCol1.close();
    }

    public void testExecuteEmptyBatch() throws Exception
    {
        Statement stmt = con.createStatement();
        int[] updateCount = stmt.executeBatch();
        assertEquals(0, updateCount.length);

        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
        stmt.clearBatch();
        updateCount = stmt.executeBatch();
        assertEquals(0, updateCount.length);
        stmt.close();
    }

    public void testClearBatch() throws Exception
    {
        Statement stmt = con.createStatement();

        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
        assertCol1HasValue(0);
        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");
        assertCol1HasValue(0);
        stmt.clearBatch();
        assertCol1HasValue(0);
        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 4 WHERE pk = 1");
        assertCol1HasValue(0);
        stmt.executeBatch();
        assertCol1HasValue(4);
        con.commit();
        assertCol1HasValue(4);

        stmt.close();
    }

    public void testSelectThrowsException() throws Exception
    {
        Statement stmt = con.createStatement();

        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
        stmt.addBatch("SELECT col1 FROM testbatch WHERE pk = 1");
        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");

        try
        {
            stmt.executeBatch();
            fail("Should raise a BatchUpdateException because of the SELECT");
        }
        catch (BatchUpdateException e)
        {
            int [] updateCounts = e.getUpdateCounts();
            assertEquals(1, updateCounts.length);
            assertEquals(1, updateCounts[0]);
        }
        catch (SQLException e)
        {
            fail( "Should throw a BatchUpdateException instead of " +
                  "a generic SQLException: " + e);
        }

        stmt.close();
    }

    public void testStringAddBatchOnPreparedStatement() throws Exception
    {
        PreparedStatement pstmt = con.prepareStatement("UPDATE testbatch SET col1 = col1 + ? WHERE PK = ?" );
        pstmt.setInt(1, 1);
        pstmt.setInt(2, 1);
        pstmt.addBatch();

        try
        {
            pstmt.addBatch("UPDATE testbatch SET col1 = 3");
            fail("Should have thrown an exception about using the string addBatch method on a prepared statement.");
        }
        catch (SQLException sqle)
        {
        }

        pstmt.close();
    }

    public void testPreparedStatement() throws Exception
    {
        PreparedStatement pstmt = con.prepareStatement(
                                      "UPDATE testbatch SET col1 = col1 + ? WHERE PK = ?" );

        // Note that the first parameter changes for every statement in the
        // batch, whereas the second parameter remains constant.
        pstmt.setInt(1, 1);
        pstmt.setInt(2, 1);
        pstmt.addBatch();
        assertCol1HasValue(0);

        pstmt.setInt(1, 2);
        pstmt.addBatch();
        assertCol1HasValue(0);

        pstmt.setInt(1, 4);
        pstmt.addBatch();
        assertCol1HasValue(0);

        pstmt.executeBatch();
        assertCol1HasValue(7);

        //now test to see that we can still use the statement after the execute
        pstmt.setInt(1, 3);
        pstmt.addBatch();
        assertCol1HasValue(7);

        pstmt.executeBatch();
        assertCol1HasValue(10);

        con.commit();
        assertCol1HasValue(10);

        con.rollback();
        assertCol1HasValue(10);

        pstmt.close();
    }

    public void testTransactionalBehaviour() throws Exception
    {
        Statement stmt = con.createStatement();

        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 1 WHERE pk = 1");
        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 2 WHERE pk = 1");
        stmt.executeBatch();
        con.rollback();
        assertCol1HasValue(0);

        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 4 WHERE pk = 1");
        stmt.addBatch("UPDATE testbatch SET col1 = col1 + 8 WHERE pk = 1");

        // The statement has been added to the batch, but it should not yet
        // have been executed.
        assertCol1HasValue(0);

        int[] updateCounts = stmt.executeBatch();
        assertEquals(2, updateCounts.length);
        assertEquals(1, updateCounts[0]);
        assertEquals(1, updateCounts[1]);

        assertCol1HasValue(12);
        con.commit();
        assertCol1HasValue(12);
        con.rollback();
        assertCol1HasValue(12);

        stmt.close();
    }

    public void testWarningsAreCleared() throws SQLException
    {
        Statement stmt = con.createStatement();
        stmt.addBatch("CREATE TEMP TABLE unused (a int primary key)");
        stmt.executeBatch();
        // Execute an empty batch to clear warnings.
        stmt.executeBatch();
        assertNull(stmt.getWarnings());
        stmt.close();
    }

    public void testBatchEscapeProcessing() throws SQLException
    {
        Statement stmt = con.createStatement();
        stmt.execute("CREATE TEMP TABLE batchescape (d date)");

        stmt.addBatch("INSERT INTO batchescape (d) VALUES ({d '2007-11-20'})");
        stmt.executeBatch();

        PreparedStatement pstmt = con.prepareStatement("INSERT INTO batchescape (d) VALUES ({d '2007-11-20'})");
        pstmt.addBatch();
        pstmt.executeBatch();
        pstmt.close();

        ResultSet rs = stmt.executeQuery("SELECT d FROM batchescape");
        assertTrue(rs.next());
        assertEquals("2007-11-20", rs.getString(1));
        assertTrue(rs.next());
        assertEquals("2007-11-20", rs.getString(1));
        assertTrue(!rs.next());
        rs.close();
        stmt.close();
    }

    public void testBatchWithEmbeddedNulls() throws SQLException
    {
        Statement stmt = con.createStatement();
        stmt.execute("CREATE TEMP TABLE batchstring (a text)");

        con.commit();

        PreparedStatement pstmt = con.prepareStatement("INSERT INTO batchstring VALUES (?)");

        try {
            pstmt.setString(1, "a");
            pstmt.addBatch();
            pstmt.setString(1, "\u0000");
            pstmt.addBatch();
            pstmt.setString(1, "b");
            pstmt.addBatch();
            pstmt.executeBatch();
            fail("Should have thrown an exception.");
        } catch (SQLException sqle) {
            con.rollback();
        }
        pstmt.close();

        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM batchstring");
        assertTrue(rs.next());
        assertEquals(0, rs.getInt(1));
        rs.close();
        stmt.close();
    }

}
