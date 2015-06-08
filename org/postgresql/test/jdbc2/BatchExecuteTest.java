/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLException;

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
            Class.forName("org.postgresql.Driver");
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
        stmt.close();

        TestUtil.createTable(con, "prep", "a integer, b integer");

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

    public void testMixedBatch() throws SQLException {
        try {
            Statement st = con.createStatement();
            st.executeUpdate("DELETE FROM prep;");
            st.close();

            st = con.createStatement();
            st.addBatch("INSERT INTO prep (a, b) VALUES (1,2)");
            st.addBatch("INSERT INTO prep (a, b) VALUES (100,200)");
            st.addBatch("DELETE FROM prep WHERE a = 1 AND b = 2");
            st.addBatch("CREATE TEMPORARY TABLE waffles(sauce text)");
            st.addBatch("INSERT INTO waffles(sauce) VALUES ('cream'), ('strawberry jam')");
            int[] batchResult = st.executeBatch();
            assertEquals(1, batchResult[0]);
            assertEquals(1, batchResult[1]);
            assertEquals(1, batchResult[2]);
            assertEquals(0, batchResult[3]);
            assertEquals(2, batchResult[4]);
        } catch (SQLException ex) {
            ex.getNextException().printStackTrace();
            throw ex;
        }
    }

	/*
	 * A user reported that a query that uses RETURNING (via getGeneratedKeys)
	 * in a batch, and a 'text' field value in a table is assigned NULL in the first 
	 * execution of the batch then non-NULL afterwards using 
	 * PreparedStatement.setObject(int, Object) (i.e. no Types param or setString call)
	 * the batch may fail with:
	 * 
	 * "Received resultset tuples, but no field structure for them"
	 * 
	 * at org.postgresql.core.v3.QueryExecutorImpl.processResults
	 * 
	 * Prior to 245b388 it would instead fail with a NullPointerException
	 * in AbstractJdbc2ResultSet.checkColumnIndex
	 * 
	 * The cause is complicated. The failure arises because the query gets
	 * re-planned mid-batch. This re-planning clears the cached information
	 * about field types. The field type information for parameters gets
	 * re-acquired later but the information for *returned* values does not.
	 * 
	 * (The reason why the returned value types aren't recalculated is not
	 *  yet known.)
	 * 
	 * The re-plan's cause is its self complicated.
	 * 
	 * The first bind of the parameter, which is null, gets the type oid 0 
	 * (unknown/unspecified). Unless Types.VARCHAR is specified or setString
	 * is used, in which case the oid is set to 1043 (varchar).
	 * 
	 * The second bind identifies the object class as String so it calls
	 * setString internally. This sets the type to 1043 (varchar).
	 * 
	 * The third and subsequent binds, whether null or non-null, will get type
	 * 1043, becaues there's logic to avoid overwriting a known parameter type
	 * with the unknown type oid. This is why the issue can only occur when
	 * null is the first entry.
	 * 
	 * When executed the first time a describe is run. This reports the parameter
	 * oid to be 25 (text), because that's the type of the table column the param
	 * is being assigned to. That's why the cast to ?::varchar works - because it
	 * overrides the type for the parameter to 1043 (varchar).
	 * 
	 * The second execution sees that the bind parameter type is already known
	 * to PgJDBC as 1043 (varchar). PgJDBC doesn't see that text and varchar are
	 * the same - and, in fact, under some circumstances they aren't exactly the
	 * same. So it discards the planned query and re-plans.
	 * 
	 * This issue can be reproduced with any pair of implicitly or assignment
	 * castable types; for example, using Integer in JDBC and bigint in the Pg
	 * table will do it.
	 */
    public void testBatchReturningMixedNulls() throws SQLException {
	    String[] testData = new String[] { null, "test", null, null, null };

    	try {
    		Statement setup = con.createStatement();
    		setup.execute("DROP TABLE IF EXISTS mixednulltest;");
    		// It's significant that "value' is 'text' not 'varchar' here;
    		// if 'varchar' is used then everything works fine.
    		setup.execute("CREATE TABLE mixednulltest (key serial primary key, value text);");
    		setup.close();

    		// If the parameter is given as ?::varchar then this issue
    		// does not arise.
		    PreparedStatement st = con.prepareStatement(
                "INSERT INTO mixednulltest (value) VALUES (?)",
                new String[] { "key" });

			for (String val : testData) {
				/*
				 * This is the crucial bit. It's set to null first time around,
				 * so the RETURNING clause's type oid is undefined.
				 *
				 * The second time around the value is assigned so Pg reports
				 * the type oid is TEXT, like the table. But we expected VARCHAR.
				 *
				 * This causes PgJDBC to replan the query, and breaks other things.
				 */
				st.setObject(1, val);
				st.addBatch();
			}
			st.executeBatch();
			ResultSet rs = st.getGeneratedKeys();
			for (int i = 1; i <= testData.length; i++) {
				rs.next();
				assertEquals(i, rs.getInt(1));
			}
			assertTrue(!rs.next());
    	} catch (SQLException ex) {
            ex.getNextException().printStackTrace();
            throw ex;
    	}
    }
}
