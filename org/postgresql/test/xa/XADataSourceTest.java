/*-------------------------------------------------------------------------
*
* Copyright (c) 2009-2011, PostgreSQL Global Development Group
*
*-------------------------------------------------------------------------
*/
package org.postgresql.test.xa;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;

import junit.framework.TestCase;
import org.postgresql.PGConnection;
import org.postgresql.xa.PGXADataSource;

public class XADataSourceTest extends TestCase {

    private XADataSource _ds;

    private Connection _conn;

    private XAConnection xaconn;
    private XAResource xaRes;
    private Connection conn;

    public XADataSourceTest(String name) {
        super(name);

        _ds = new PGXADataSource();
        BaseDataSourceTest.setupDataSource((PGXADataSource)_ds);
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();

        TestUtil.createTable(_conn, "testxa1", "foo int");
        TestUtil.createTable(_conn, "testxa2", "foo int");
        TestUtil.createTable(_conn, "testxa3", "foo int");
        TestUtil.createTable(_conn, "testxathreads1", "foo int");
        TestUtil.createTable(_conn, "testxathreads2", "foo int");
        TestUtil.createTable(_conn, "testxathreads3", "foo int");
        TestUtil.createTable(_conn, "testxathreads4", "foo int");

        clearAllPrepared();

        xaconn = _ds.getXAConnection();
        xaRes = xaconn.getXAResource();
        conn = xaconn.getConnection();
    }

    protected void tearDown() throws SQLException {
        xaconn.close();
        assertEquals(0, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        clearAllPrepared();

        TestUtil.dropTable(_conn, "testxa1");
        TestUtil.dropTable(_conn, "testxa2");
        TestUtil.dropTable(_conn, "testxa3");
        TestUtil.dropTable(_conn, "testxathreads1");
        TestUtil.dropTable(_conn, "testxathreads2");
        TestUtil.dropTable(_conn, "testxathreads3");
        TestUtil.dropTable(_conn, "testxathreads4");
        TestUtil.closeDB(_conn);
        ((PGXADataSource)_ds).setXAAcquireTimeout(50);
    }

    private void clearAllPrepared() throws SQLException
    {
        Statement st = _conn.createStatement();
        try
        {
            ResultSet rs = st.executeQuery("SELECT gid FROM pg_prepared_xacts");

            Statement st2 = _conn.createStatement();
            while (rs.next())
            {
                st2.executeUpdate("ROLLBACK PREPARED '" + rs.getString(1) + "'");
            }
            st2.close();
        }
        finally
        {
            st.close();
        }
    }

    static class CustomXid implements Xid {
        private static Random rand = new Random(System.currentTimeMillis());
        byte[] gtrid = new byte[Xid.MAXGTRIDSIZE];
        byte[] bqual = new byte[Xid.MAXBQUALSIZE];

        CustomXid(int i)
        {
            rand.nextBytes(gtrid);
            gtrid[0] = (byte)i;
            gtrid[1] = (byte)i;
            gtrid[2] = (byte)i;
            gtrid[3] = (byte)i;
            gtrid[4] = (byte)i;
            bqual[0] = 4;
            bqual[1] = 5;
            bqual[2] = 6;
        }

        public int getFormatId() {
            return 0;
        }


        public byte[] getGlobalTransactionId() {
            return gtrid;
        }

        public byte[] getBranchQualifier() {
            return bqual;
        }
        public boolean equals(Object o) {
            Xid other = (Xid)o;
            if (o == null || !(o instanceof Xid)) {
                return false;
            }
            if (other.getFormatId() != this.getFormatId())
                return false;
            if (!Arrays.equals(other.getBranchQualifier(), this.getBranchQualifier()))
                return false;
            if (!Arrays.equals(other.getGlobalTransactionId(), this.getGlobalTransactionId()))
                return false;

            return true;
        }
    }

    public void testOnePhase() throws Exception {
        Xid xid = new CustomXid(1);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);
    }

    public void testTwoPhaseCommit() throws Exception {
        Xid xid = new CustomXid(1);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.commit(xid, false);
    }
    
    public void testCloseBeforeCommit() throws Exception {
        Xid xid = new CustomXid(5);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        conn.close();
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);

        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
    }
    
    public void testCloseBeforeRollback() throws Exception {
        Xid xid = new CustomXid(5);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        conn.close();
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.rollback(xid);

        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertFalse(rs.next());
    }

    
    public void testCloseLogicalBeforeRollback() throws Exception {
        Xid xid = new CustomXid(5);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        xaRes.end(xid, XAResource.TMSUCCESS);
        
        conn.close();
        xaconn.close();
        
        // This should throw an exception. The physical connection is closed, and there's no prepared TX in the DB.
        boolean expected = false;
        try {
            xaRes.rollback(xid);
        } catch (XAException xae) {
            expected = true;
        }
        assertTrue(expected);

        // Restore state so we can cleanup.
        xaconn = _ds.getXAConnection();
        
        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertFalse(rs.next());
    }
    
    
    public void testClosePhysicalConnectionCounts() throws Exception {
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        // Open a second connection so that closing the first does not kill all physical backends.
        XAConnection xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        Xid xid = new CustomXid(8657309);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUCCESS);
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.close();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        xaconn.close();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        xaRes.rollback(xid);
        
        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertFalse(rs.next());
        xaconn = xaconn2;
    }
    

    public void testPhysicalConnectionCountCloseBeforeRollbackLocalTXInProgress() throws Exception {
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        // Open a second logical connection so that closing the first does not kill all physical backends.
        XAConnection xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        Connection conn2 = xaconn2.getConnection();
        conn2.setAutoCommit(false);
        conn2.createStatement().execute("BEGIN");
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        Xid xid = new CustomXid(8657309);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUCCESS);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.close();
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // we should have 1 connection with an in-progress TX, and 1 associated to a xid.
        xaconn.close();
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // This ends the association of the xid to a backend connection (making it closeable).
        xaRes.rollback(xid);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Get another connection. This should be able to use the connection that was just rolledback, keeping the pool a stable size.
        xaconn = _ds.getXAConnection();
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Close the new one, and the old one that was associated to the xid. This should leave us with 1.
        xaconn.close();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Do a check to make sure we're rolled back properly.
        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertFalse(rs.next());
        xaconn = xaconn2;
    }
    
    
    public void testDoNotCloseLocalAssociated() throws Exception {
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Open a second logical connection so that closing the first does not kill all physical backends.
        XAConnection xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        Connection conn2 = xaconn2.getConnection();
        conn2.setAutoCommit(false);
        conn2.createStatement().execute("BEGIN");
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        conn2.close();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // We should close the in-progress local tx for the closed logical.
        xaconn2.close();
        assertEquals(0, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // There isn't anything to close!
        xaconn.close();
        assertEquals(0, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        
        // Open a new set of XAConnections and try closing the one that didn't create the local tx.
        xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        xaconn = _ds.getXAConnection();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Open new handles.
        conn2 = xaconn2.getConnection();
        conn = xaconn.getConnection();
        
        // Force association to different physicals
        assertNotSame(((PGConnection)conn2).getBackendPID(), ((PGConnection)conn).getBackendPID());
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        conn.setAutoCommit(false);
        conn.createStatement().execute("BEGIN");
        
        assertFalse(conn.getAutoCommit());
        
        // Close conn2, which did not open conn, so conn should still be open.
        conn2.close();
        xaconn2.close();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // clean up.
        conn.close();
        xaconn.close();
        
        assertEquals(0, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Open two XAConnections, both use the Connection handle. One starts, ends, and prepares a global tx.
        // The other gets closed.
        xaconn2 = _ds.getXAConnection();
        conn2 = xaconn2.getConnection();
        
        xaconn = _ds.getXAConnection();
        conn = xaconn.getConnection();
        xaRes = xaconn.getXAResource();
        
        // Force different backends.
        assertNotSame(((PGConnection)conn2).getBackendPID(), ((PGConnection)conn).getBackendPID());
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        Xid xid = new CustomXid(321);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount()); // no tx in progress.
        assertFalse(conn.getAutoCommit());
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        
        conn2.close();
        xaconn2.close();
        assertEquals(0, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        xaRes.commit(xid, false);
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
    }
    
    public void testStatementLongevity() throws Exception {
        Xid xid = new CustomXid(5);
        
        int rows = 100;
        for (int i = 0; i < rows; i++) {
            assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (" + i + ")"));
        }
        
        xaRes.start(xid, XAResource.TMNOFLAGS);
        PreparedStatement select = conn.prepareStatement("SELECT foo FROM testxa1", ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY, ResultSet.CLOSE_CURSORS_AT_COMMIT);
        select.setFetchSize(25);
        ResultSet rs = select.executeQuery();
        assertTrue(rs.next());
        conn.close(); // Close the handle
        conn = xaconn.getConnection(); // Open the handle.
        for (int i = 0; i < rows - 1; i++) {
            assertTrue(rs.next());
        }
        rs.close();
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);
        

        rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertTrue(rs.next());
    }
    
    /**
     * This test checks to make sure that an XAConnection.close() prior to a rollback does not create an issue.
     * When XAResource sharing is properly working, an XAConnection.close() will not invalidate the XAResource which may be used to 
     * control a TX. The end result, is that it's valid to close the XAConnection prior to the XAResource invoking control methods.
     * 
     * @throws Exception 
     */
    public void testXACloseBeforeRollback() throws Exception {
        // Open a second connection so that closing the first does not kill all physical backends.
        XAConnection xaconn2 = _ds.getXAConnection();
        
        Xid xid = new CustomXid(5);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        conn.close();
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaconn.close();
        
        xaRes.rollback(xid);

        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertFalse(rs.next());
        xaconn = xaconn2;
    }
    
    public void testRecover() throws Exception {
        Xid xid = new CustomXid(12345);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);

        {
            Xid[] recoveredXidArray = xaRes.recover(XAResource.TMSTARTRSCAN);

            boolean recoveredXid = false;

            for (int i = 0; i < recoveredXidArray.length; i++)
            {
                if (xid.equals(recoveredXidArray[i]))
                {
                    recoveredXid = true;
                    break;
                }
            }

            assertTrue("Did not recover prepared xid", recoveredXid);
            assertEquals(0, xaRes.recover(XAResource.TMNOFLAGS).length);
        }

        xaRes.rollback(xid);

        {
            Xid[] recoveredXidArray = xaRes.recover(XAResource.TMSTARTRSCAN);

            boolean recoveredXid = false;

            for (int c = 0; c < recoveredXidArray.length; c++)
            {
                if (xaRes.equals(recoveredXidArray[c]))
                {
                    recoveredXid = true;
                    break;
                }
            }

            assertFalse("Recovered rolled back xid", recoveredXid);
        }
    }

    public void testRollback() throws XAException {
        Xid xid = new CustomXid(3);

        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.rollback(xid);
    }

    public void testRollbackWithoutPrepare() throws XAException {
        Xid xid = new CustomXid(4);

        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.rollback(xid);
    }

    public void testAutoCommit() throws Exception {
        Xid xid = new CustomXid(6);

        // When not in an XA transaction, autocommit should be true
        // per normal JDBC rules.
        assertTrue(conn.getAutoCommit());

        // When in an XA transaction, autocommit should be false
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertFalse(conn.getAutoCommit());
        xaRes.end(xid, XAResource.TMSUCCESS);
        // The assert below would allocate a new physical connection starting
        // in autocommit mode for local TX semantics. I believe that would be
        // the 'more correct' of the options.
        // assertFalse(conn.getAutoCommit());
        xaRes.commit(xid, true);
        assertTrue(conn.getAutoCommit());

        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        assertTrue(conn.getAutoCommit());
        xaRes.commit(xid, false);
        assertTrue(conn.getAutoCommit());

        // Check that autocommit is reset to true after a 1-phase rollback
        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.rollback(xid);
        assertTrue(conn.getAutoCommit());

        // Check that autocommit is reset to true after a 2-phase rollback
        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.rollback(xid);
        assertTrue(conn.getAutoCommit());

        // Check that autoCommit is set correctly after a getConnection-call
        conn = xaconn.getConnection();
        assertTrue(conn.getAutoCommit());

        xaRes.start(xid, XAResource.TMNOFLAGS);

        conn.createStatement().executeQuery("SELECT * FROM testxa1");

        java.sql.Timestamp ts1 = getTransactionTimestamp(conn);

        conn.close();
        conn = xaconn.getConnection();
        assertFalse(conn.getAutoCommit());

        java.sql.Timestamp ts2 = getTransactionTimestamp(conn);

        /* Check that we're still in the same transaction. 
         * close+getConnection() should not rollback the XA-transaction
         * implicitly.
         */
        assertEquals(ts1, ts2);

        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.rollback(xid);
        assertTrue(conn.getAutoCommit());
    }
    
    /**
     * Get the time the current transaction was started from the server. 
     *
     * This can be used to check that transaction doesn't get committed/
     * rolled back inadvertently, by calling this once before and after the
     * suspected piece of code, and check that they match. It's a bit iffy,
     * conceivably you might get the same timestamp anyway if the
     * suspected piece of code runs fast enough, and/or the server clock
     * is very coarse grained. But it'll do for testing purposes.
     */
    private static java.sql.Timestamp getTransactionTimestamp(Connection conn) throws SQLException
    {
        ResultSet rs = conn.createStatement().executeQuery("SELECT now()");
        rs.next();
        return rs.getTimestamp(1);
    }

    public void testEndThenJoin() throws XAException {
        Xid xid = new CustomXid(5);

        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.start(xid, XAResource.TMJOIN);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);
    }

    public void testRestoreOfAutoCommit() throws Exception {
        conn.setAutoCommit(false);

        Xid xid = new CustomXid(14);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);

        assertTrue(!conn.getAutoCommit());
    }


    public void testInterleaving1() throws Exception {
     Xid xid1 = new CustomXid(1);
     Xid xid2 = new CustomXid(2);
     
     // Added this to validate DB isolation in interleaved situations.
     conn.setAutoCommit(true);
     assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
     assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa2 VALUES (2)"));
     assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (3)"));
     
     xaRes.start(xid1, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = '111'");
     xaRes.end(xid1, XAResource.TMSUCCESS);

     // assert uncommited.
     ResultSet rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
     assertTrue(rs.next());
     assertEquals(1, rs.getInt(1));
     
     xaRes.start(xid2, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa2 SET foo = '222'");

     // xid2 was started after xid1, we should not be able to see the update
     rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
     assertTrue(rs.next());
     assertEquals(1, rs.getInt(1));
     
     // Commit xid1!
     xaRes.commit(xid1, true);
     
     // Now we should see it.
     rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
     assertTrue(rs.next());
     assertEquals(111, rs.getInt(1));
     
     xaRes.end(xid2, XAResource.TMSUCCESS);

     // now that xid1 is commited, xid2 is ended, local TX mode should see 
     // the updates from xid1, but not yet from xid2.
     rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
     assertTrue(rs.next());
     assertEquals(111, rs.getInt(1));
     
     rs = conn.createStatement().executeQuery("SELECT foo FROM testxa2");
     assertTrue(rs.next());
     assertEquals(2, rs.getInt(1));
     
     xaRes.commit(xid2, true);

     // Now we should see the results of xid2
     rs = conn.createStatement().executeQuery("SELECT foo FROM testxa2");
     assertTrue(rs.next());
     assertEquals(222, rs.getInt(1));
    }
    
    public void testInterleaving2() throws Exception {
     Xid xid1 = new CustomXid(1);
     Xid xid2 = new CustomXid(2);
     Xid xid3 = new CustomXid(3);
     
     xaRes.start(xid1, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = '11'");
     xaRes.end(xid1, XAResource.TMSUCCESS);
     
     xaRes.start(xid2, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa2 SET foo = '22'");
     xaRes.end(xid2, XAResource.TMSUCCESS);

     xaRes.start(xid3, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa3 SET foo = '33'");
     xaRes.end(xid3, XAResource.TMSUCCESS);

     xaRes.commit(xid1, true);
     xaRes.commit(xid2, true);
     xaRes.commit(xid3, true);
    }

    public void testNoInterleaving1() throws Exception {
        ((PGXADataSource)_ds).setXAAcquireTimeout(0);
        Xid xid1 = new CustomXid(1);

        // Added this to validate DB isolation in interleaved situations.
        conn.setAutoCommit(true);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa2 VALUES (2)"));
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (3)"));

        xaRes.start(xid1, XAResource.TMNOFLAGS);
        conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = '111'");
        xaRes.end(xid1, XAResource.TMSUCCESS);

        // In a non-interleaving situation, this local TX query will blow up, as there won't be a physical connection
        // to service the query, and we won't be allowed to open new physical connections to service it. We expect an error.
        try {
            conn.createStatement().executeQuery("SELECT foo FROM testxa1");
            fail("With interleaving disabled, createStatement outside of the started XA TX should fail.");
        } catch (SQLException sqle) { 
        } catch (Exception ex) {
            fail(ex.toString());
        }
    }
    
    public void testNoInterleaving2() throws Exception {
        ((PGXADataSource)_ds).setXAAcquireTimeout(0);
        Xid xid1 = new CustomXid(1);
        Xid xid2 = new CustomXid(2);
        Xid xid3 = new CustomXid(3);

        xaRes.start(xid1, XAResource.TMNOFLAGS);
        conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = '11'");
        xaRes.end(xid1, XAResource.TMSUCCESS);

        // In a non-interleaving situation, this will blow up.
        try {
            xaRes.start(xid2, XAResource.TMNOFLAGS);
            fail();
        } catch (XAException xaerr) { }
    }
    
    public void testNoInterleavingTwoPhaseCommit() throws Exception {
        ((PGXADataSource)_ds).setXAAcquireTimeout(0);
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
        
        Xid xid = new CustomXid(1);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.commit(xid, false);
        
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
    }
    
    public void testNoInterleavingSuspend() throws Exception {
        ((PGXADataSource)_ds).setXAAcquireTimeout(0);
        Xid xid1 = new CustomXid(1);

        conn.setAutoCommit(true);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa2 VALUES (2)"));
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (3)"));
        
        xaRes.start(xid1, XAResource.TMNOFLAGS);
        assertFalse(conn.getAutoCommit());
        conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = '11'");
        
        // Interleaving should disallow SUSPEND.
        try {
            xaRes.end(xid1, XAResource.TMSUSPEND);
            fail();
        } catch (XAException xaer) {}
    }
    
    public void testSuspendResume() throws Exception {
        Xid xid1 = new CustomXid(1);
        Xid xid2 = new CustomXid(2);

        conn.setAutoCommit(true);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa2 VALUES (2)"));
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa3 VALUES (3)"));
        
        xaRes.start(xid1, XAResource.TMNOFLAGS);
        assertFalse(conn.getAutoCommit());
        conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = '11'");
        xaRes.end(xid1, XAResource.TMSUSPEND);
       
        // Let's make sure nothing has happened.
        ResultSet rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        
        xaRes.start(xid2, XAResource.TMNOFLAGS);
        // paranoid isolation check.
        rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        conn.createStatement().executeUpdate("UPDATE testxa2 SET foo = '22'");
        xaRes.end(xid2, XAResource.TMSUCCESS);
        xaRes.prepare(xid2);
        xaRes.commit(xid2, false);
        
        // Resume the suspended TX.
        xaRes.start(xid1, XAResource.TMRESUME);
        // We should see the update.
        rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertTrue(rs.next());
        assertEquals(11, rs.getInt(1));
        xaRes.end(xid1, XAResource.TMSUCCESS);
        xaRes.prepare(xid1);
        xaRes.commit(xid1, false);
        
        // make sure it all worked.
        rs = conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertTrue(rs.next());
        assertEquals(11, rs.getInt(1));
        
        // make sure it all worked.
        rs = conn.createStatement().executeQuery("SELECT foo FROM testxa2");
        assertTrue(rs.next());
        assertEquals(22, rs.getInt(1));
    }
    
    /**
     * Tests XA JOIN semantics with two threads.
     * @throws Exception 
     */
    public void testXAJoin() throws Exception {
        Xid xid1 = new CustomXid(1);
        
        Thread jackie = new Thread(new XAThread(conn, "testxathreads1", 2, xid1, xaRes, XAResource.TMJOIN));
        
        xaRes.start(xid1, XAResource.TMNOFLAGS);
        jackie.start();
        conn.createStatement().executeUpdate("INSERT INTO testxathreads1 VALUES (1)");
        xaRes.end(xid1, XAResource.TMSUCCESS);
        jackie.join();
        
        xaRes.prepare(xid1);
        xaRes.commit(xid1, false);
        
        // Validate that both rows exist.
        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM testxathreads1 ORDER BY foo");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));
    }
    
    /**
     * Tests a Connection shared between two threads in localTX mode.
     * 
     * @throws Exception 
     */
    public void testLocalTXThreading() throws Exception {
        LocalThread lt = new LocalThread(conn, "testxathreads2", 2);
        Thread jackie = new Thread(lt);

        jackie.start();
        conn.createStatement().executeUpdate("INSERT INTO testxathreads2 VALUES (1)");
        jackie.join();

        // Validate that both rows exist.
        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM testxathreads2 ORDER BY foo");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));

        // Validate connection clean up. We had two threads using this conn,
        // but neither one was in a TX. I would expect an access from another
        // thread to go boom.
        conn.close();
        lt.setTrigger(3);
        lt.resetError();

        jackie = new Thread(lt);
        jackie.start();
        jackie.join();
        assertTrue(lt.hadError());
    }
    

    public void testNoInterleavingCloseBeforeCommitOrPrepare() throws Exception {
        ((PGXADataSource)_ds).setXAAcquireTimeout(0);
        Xid xid1 = new CustomXid(1);
        
        xaRes.start(xid1, XAResource.TMNOFLAGS);
        conn.createStatement().executeUpdate("INSERT INTO testxathreads3 VALUES (1)");
        xaRes.end(xid1, XAResource.TMSUCCESS);
        
        // Closing the connection handle will force a logical connection to invoke some basic cleanup
        // on it's physical connection. In the case of disabled interleaving, this will cause the invocation handler
        // created by the XADataSource to attempt to allocate a physical connection...
        // 
        // The fix for this is to track the closing state of the PGXAConnection handle, and simply
        // ignore invocations in the proxy if we're not interleaving and we're closing a logical connection handle.
        //
        // Doing that will require that we proxy the handle returned by the PGXAConnection and if close() is invoked
        // we then have to set state on the logical so we can disable XA Proxying to a physical backend.
        // 
        conn.close(); // Close the connection handle. This should implicitly roll-back an in-progress TX.
    }
    
    /**
     * Test that commit prepared will not be attempted on a connection already servicing an xid
     * Specifically - ERROR: COMMIT PREPARED cannot run inside a transaction block
     *
     * Unfortunately, this is a race condition and not at all very good for a "test" since it
     * isn't always repeatable.
     *
     * TODO Make this test (consistently) repeatable! (on all machines)
     *
     * @throws Exception
     */
    public void testCommitPreparedThreading() throws Exception {
        final Xid xid1 = new CustomXid(1);
        final Xid xid2 = new CustomXid(2);

        xaRes.start(xid1, XAResource.TMNOFLAGS);
        conn.createStatement().executeUpdate("INSERT INTO testxathreads4 VALUES (1)");
        xaRes.end(xid1, XAResource.TMSUCCESS);
        xaRes.prepare(xid1);

        Thread t = new Thread() {
            @Override
            public void run() {
                try {
                    xaRes.start(xid2, XAResource.TMNOFLAGS);
                    conn.createStatement().executeUpdate("INSERT INTO testxathreads4 VALUES (2)");
                    xaRes.end(xid2, XAResource.TMSUCCESS);
                    xaRes.prepare(xid2);
                    xaRes.commit(xid2, false);
                } catch (Throwable t) {
                    t.printStackTrace();
                    fail();
                }
            }
        };

        t.start();
        xaRes.commit(xid1, false);
        t.join();

        ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM testxathreads4 ORDER BY foo");
        assertTrue(rs.next());
        assertEquals(1, rs.getInt(1));
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1));

        conn.close();
        assertTrue(conn.isClosed());
    }
    
    
    public void testCloseMixedTXPhysicalCorrectness() throws Exception {
        // One connection starting off.
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.setAutoCommit(false);
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.createStatement().execute("BEGIN");
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        
        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUCCESS);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        xaRes.prepare(xid);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        
        // Back to local TX Mode.
        Statement st = conn.createStatement();
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        ResultSet rs = st.executeQuery("SELECT * FROM testxa1");
        assertFalse(rs.next()); // Read Committed, we haven't committed yet.
        
        // Commit it, 2pc.
        xaRes.commit(xid, false);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        rs = st.executeQuery("SELECT * FROM testxa1");
        assertTrue(rs.next()); // Read Committed!
        
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // We should be able to commit, since we're on our original local tx connection
        conn.commit();
    }
    
    public void testLocalTXInterleaving() throws Exception {
        conn.setAutoCommit(false);
        conn.createStatement().execute("BEGIN");
        
        int localTxPID = ((PGConnection)conn).getBackendPID();

        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        int xaPID = ((PGConnection)conn).getBackendPID();
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        xaRes.end(xid, XAResource.TMSUSPEND);
        
        // Back to local TX Mode, on the same connection.
        Statement st = conn.createStatement();
        assertEquals(localTxPID, ((PGConnection)conn).getBackendPID());
        assertFalse(conn.getAutoCommit());
        
        // Resume, end, prepare, commit 2p.
        xaRes = xaconn.getXAResource();
        xaRes.start(xid, XAResource.TMRESUME);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.commit(xid, false);
        
        assertEquals(localTxPID, ((PGConnection)conn).getBackendPID());
        conn.commit();
    }
    
    public void testCloseSuspendedPhysicalCorrectness() throws Exception {
        // One connection starting off.
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.setAutoCommit(false);
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.createStatement().execute("BEGIN");
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Opens a second connection.
        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUSPEND);
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        
        // Back to local TX Mode, on the same connection.
        Statement st = conn.createStatement();
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        ResultSet rs = st.executeQuery("SELECT * FROM testxa1");
        assertFalse(rs.next());
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Closing the xaconnection should result in 1 physical connection remaining (the suspended xid);
        xaconn.close();
        assertEquals(1, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Get a new xaConn to mess with.
        xaconn = _ds.getXAConnection();
        
        // Get a new connection, which will have autocommit ENABLED.
        conn = xaconn.getConnection();
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
        
        // Resume, end, prepare, commit 2p.
        xaRes = xaconn.getXAResource();
        xaRes.start(xid, XAResource.TMRESUME);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.commit(xid, false);
        
        rs = conn.createStatement().executeQuery("SELECT * FROM testxa1");
        assertTrue(rs.next());
        assertEquals(2, ((PGXADataSource)_ds).getPhysicalConnectionCount());
    }
    
    /**
     * Exercises a shared resource manager closing XAConnection handles and
     * connections before a TM can invoke commit();
     * 
     * @throws Exception 
     */
    public void testTwoPhaseCommitSharedCloseRace() throws Exception {
        // Close the existing connections so we'll exhaust the logical pool
        conn.close();
        
        final int THREADS = 12;
        
        ArrayList<SharedXACommitter> committers = new ArrayList<SharedXACommitter>(THREADS);
        CountDownLatch trigger = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREADS);
        
        for (int i = 0; i < THREADS; i++) {
            XAConnection xac = _ds.getXAConnection();
            Connection c = xac.getConnection();

            final Xid xid = new CustomXid(i);

            xaRes.start(xid, XAResource.TMNOFLAGS);
            c.createStatement().executeUpdate("INSERT INTO testxathreads1 VALUES (" + i + ")");
            xaRes.end(xid, XAResource.TMSUCCESS);
            xaRes.prepare(xid);
            
            SharedXACommitter committer = new SharedXACommitter(xaRes, xid, trigger, endLatch);
            committer.start();
            committers.add(committer);
            new XACloser(xac, c, trigger).start();
        }
        
        // Run all the committers while we close things.
        trigger.countDown();
        
        // Wait for all the committers to count down.
        endLatch.await(15, TimeUnit.SECONDS);
        
        assertEquals(0, endLatch.getCount());
        
        while (!committers.isEmpty()) {
            Exception ex = committers.remove(0).getFailure();
            if (ex != null) {
                ex.printStackTrace();
                fail(ex.getMessage());
            }
        }
        
        // Restore expected end state.
        conn = xaconn.getConnection();
    }
    
    private class XACloser extends Thread {
        private XAConnection xaconn;
        private Connection handle;
        private CountDownLatch latch;
        
        XACloser(XAConnection xaconn, Connection handle, CountDownLatch latch) {
            this.xaconn = xaconn;
            this.handle = handle;
            this.latch = latch;
        }

        @Override
        public void run() {
            try {
                this.latch.await();
                this.handle.close();
                this.xaconn.close();
            } catch (Exception ex) {
                ex.printStackTrace();
                fail(ex.getMessage());
            }
        }
    }
    
    
    private class SharedXACommitter extends Thread {
        private Xid xid;
        private Exception ex;
        private CountDownLatch latch;
        private CountDownLatch endLatch;
        
        public SharedXACommitter(XAResource xares, Xid xid, CountDownLatch latch, CountDownLatch endLatch) {
            this.xid = xid;
            this.latch = latch;
            this.endLatch = endLatch;
            this.ex = null;
        }

        @Override
        public void run() {
            try {
                latch.await();
                xaRes.commit(xid, false);
            } catch (Exception e) {
                ex = e;
            } finally {
                endLatch.countDown();
            }
        }
        
        public Exception getFailure() {
            return ex;
        }
    }
    

    private class XAThread extends LocalThread {
        // A TM will be aware of an XAResource and a Xid.
        private Xid joinXid;
        private XAResource xares;
        private int flags;
        
        public XAThread(Connection c, String tablename, int trigger, Xid xid, XAResource xares, int flags) {
            super(c, tablename, trigger);
            this.joinXid = xid;
            this.xares = xares;
            this.flags = flags;
        }
        
        @Override
        public void run() {
            try {
                xares.start(joinXid, flags);
                super.run();
                xares.end(joinXid, XAResource.TMSUCCESS);
            } catch (Exception ex) {
                error = true;
            }
        }
    }
    
    private class LocalThread implements Runnable {
        protected Connection conn;
        
        protected int trigger;
        protected String tablename;
        
        protected boolean error;
        
        public LocalThread(Connection c, String tablename, int trigger) {
            this.conn = c;
            this.tablename = tablename;
            this.trigger = trigger;
            this.error = false;
        }

        public void setTrigger(int trigger) {
            this.trigger = trigger;
        }
        
        @Override
        public void run() {
            try {
                conn.createStatement().executeUpdate("INSERT INTO " + tablename + " VALUES (" + trigger + ")");
            } catch (Exception ex) {
                error = true;
            }
        }
        
        public void resetError() {
            error = false;
        }
        
        public boolean hadError() {
            return error;
        }
    }
}
