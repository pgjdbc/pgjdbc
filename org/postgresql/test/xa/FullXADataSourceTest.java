/*-------------------------------------------------------------------------
 *
 * Copyright (c) 2009-2011, PostgreSQL Global Development Group
 *
 *-------------------------------------------------------------------------
 */
package org.postgresql.test.xa;

import junit.framework.TestCase;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import org.postgresql.PGConnection;


import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.test.xa.XADataSourceTest.CustomXid;
import org.postgresql.xa.PGFullXAConnection;
import org.postgresql.xa.PGFullXADataSource;

public class FullXADataSourceTest extends XADataSourceTest {

    public FullXADataSourceTest(String name) {
        super(name);

        _ds = new PGFullXADataSource();
        BaseDataSourceTest.setupDataSource((PGFullXADataSource) _ds);
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
        assertEquals(0, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        clearAllPrepared();

        TestUtil.dropTable(_conn, "testxa1");
        TestUtil.dropTable(_conn, "testxa2");
        TestUtil.dropTable(_conn, "testxa3");
        TestUtil.dropTable(_conn, "testxathreads1");
        TestUtil.dropTable(_conn, "testxathreads2");
        TestUtil.dropTable(_conn, "testxathreads3");
        TestUtil.dropTable(_conn, "testxathreads4");
        TestUtil.closeDB(_conn);

        ((PGFullXADataSource) _ds).setXAAcquireTimeout(50);
    }

    /**
     * Overrides the testAutoCommit, becuase in XAFull mode (interleaving!) one
     * of the asserts (documented below) causes a new physical connection to be
     * allocated to return false for autocommit while a connection is pending a
     * second stage (prepare or commit) operation.
     *
     * @throws Exception
     */
    public void testAutoCommit() throws Exception {
        Xid xid = new CustomXid(6);

        // When not in an XA transaction, autocommit should be true
        // per normal JDBC rules.
        assertTrue(conn.getAutoCommit());

        // When in an XA transaction, autocommit should be false
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertFalse(conn.getAutoCommit());
        xaRes.end(xid, XAResource.TMSUCCESS);
        // XAFull Semantics Note
        // assertFalse(conn.getAutoCommit());
        // This assertion is correct, the autocommit should be false.
        // However, in an XAFull situation, this will spawn a second 
        // physical connection, as the current connection is 'busy' pending
        // either a commit or prepare.
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
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        // Open a second connection so that closing the first does not kill all physical backends.
        XAConnection xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        Xid xid = new CustomXid(8657309);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUCCESS);
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.close();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        xaconn.close();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        xaconn = _ds.getXAConnection(); // Open a new XAConnection...
        assertFalse(xaRes.isSameRM(xaconn.getXAResource())); // One is closed, the other isn't.

        xaRes = xaconn.getXAResource();
        xaRes.rollback(xid);

        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertFalse(rs.next());
        xaconn = xaconn2;
    }

    
    public void testPhysicalConnectionCountCloseBeforeRollbackLocalTXInProgress() throws Exception {
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        // Open a second logical connection so that closing the first does not kill all physical backends.
        XAConnection xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        Connection conn2 = xaconn2.getConnection();
        conn2.setAutoCommit(false);
        conn2.createStatement().execute("BEGIN");
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        Xid xid = new CustomXid(8657309);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUCCESS);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.close();
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // we should have 1 connection with an in-progress TX, and 1 associated to a xid.
        xaconn.close();
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // This ends the association of the xid to a backend connection (making it closeable).
        xaRes.rollback(xid);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Get another connection. This should be able to use the connection that was just rolledback, keeping the pool a stable size.
        xaconn = _ds.getXAConnection();
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Close the new one, and the old one that was associated to the xid. This should leave us with 1.
        xaconn.close();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Do a check to make sure we're rolled back properly.
        ResultSet rs = _conn.createStatement().executeQuery("SELECT foo FROM testxa1");
        assertFalse(rs.next());
        xaconn = xaconn2;
    }

    
    public void testDoNotCloseLocalAssociated() throws Exception {
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Open a second logical connection so that closing the first does not kill all physical backends.
        XAConnection xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        Connection conn2 = xaconn2.getConnection();
        conn2.setAutoCommit(false);
        conn2.createStatement().execute("BEGIN");
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        conn2.close();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // We should close the in-progress local tx for the closed logical.
        xaconn2.close();
        assertEquals(0, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // There isn't anything to close!
        xaconn.close();
        assertEquals(0, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());


        // Open a new set of XAConnections and try closing the one that didn't create the local tx.
        xaconn2 = _ds.getXAConnection();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        xaconn = _ds.getXAConnection();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Open new handles.
        conn2 = xaconn2.getConnection();
        conn = xaconn.getConnection();

        // Force association to different physicals
        assertNotSame(((PGConnection) conn2).getBackendPID(), ((PGConnection) conn).getBackendPID());
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        conn.setAutoCommit(false);
        conn.createStatement().execute("BEGIN");

        assertFalse(conn.getAutoCommit());

        // Close conn2, which did not open conn, so conn should still be open.
        conn2.close();
        xaconn2.close();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // clean up.
        conn.close();
        xaconn.close();

        assertEquals(0, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Open two XAConnections, both use the Connection handle. One starts, ends, and prepares a global tx.
        // The other gets closed.
        xaconn2 = _ds.getXAConnection();
        conn2 = xaconn2.getConnection();

        xaconn = _ds.getXAConnection();
        conn = xaconn.getConnection();
        xaRes = xaconn.getXAResource();

        // Force different backends.
        assertNotSame(((PGConnection) conn2).getBackendPID(), ((PGConnection) conn).getBackendPID());
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        Xid xid = new CustomXid(321);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount()); // no tx in progress.
        assertFalse(conn.getAutoCommit());
        assertEquals(1, conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (1)"));
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);

        conn2.close();
        xaconn2.close();
        assertEquals(0, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        xaRes.commit(xid, false);
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
    }

    
    /**
     * This test checks to make sure that an XAConnection.close() prior to a
     * rollback does not create an issue. When XAResource sharing is properly
     * working, an XAConnection.close() will not invalidate the XAResource which
     * may be used to control a TX. The end result, is that it's valid to close
     * the XAConnection prior to the XAResource invoking control methods.
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
        ((PGFullXADataSource) _ds).setXAAcquireTimeout(0);
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
        ((PGFullXADataSource) _ds).setXAAcquireTimeout(0);
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
        } catch (XAException xaerr) {
        }
    }

    public void testNoInterleavingTwoPhaseCommit() throws Exception {
        ((PGFullXADataSource) _ds).setXAAcquireTimeout(0);
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
        ((PGFullXADataSource) _ds).setXAAcquireTimeout(0);
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
        } catch (XAException xaer) {
        }
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
     *
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
        ((PGFullXADataSource) _ds).setXAAcquireTimeout(0);
        Xid xid1 = new CustomXid(1);

        xaRes.start(xid1, XAResource.TMNOFLAGS);
        conn.createStatement().executeUpdate("INSERT INTO testxathreads3 VALUES (1)");
        xaRes.end(xid1, XAResource.TMSUCCESS);

        // Closing the connection handle will force a logical connection to invoke some basic cleanup
        // on it's physical connection. In the case of disabled interleaving, this will cause the invocation handler
        // created by the XADataSource to attempt to allocate a physical connection...
        // 
        // The fix for this is to track the closing state of the PGFullXAConnection handle, and simply
        // ignore invocations in the proxy if we're not interleaving and we're closing a logical connection handle.
        //
        // Doing that will require that we proxy the handle returned by the PGFullXAConnection and if close() is invoked
        // we then have to set state on the logical so we can disable XA Proxying to a physical backend.
        // 
        conn.close(); // Close the connection handle. This should implicitly roll-back an in-progress TX.
    }

    /**
     * Test that commit prepared will not be attempted on a connection already
     * servicing an xid Specifically - ERROR: COMMIT PREPARED cannot run inside
     * a transaction block
     *
     * Unfortunately, this is a race condition and not at all very good for a
     * "test" since it isn't always repeatable.
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
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.setAutoCommit(false);
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.createStatement().execute("BEGIN");
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());


        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUCCESS);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        xaRes.prepare(xid);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());


        // Back to local TX Mode.
        Statement st = conn.createStatement();
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        ResultSet rs = st.executeQuery("SELECT * FROM testxa1");
        assertFalse(rs.next()); // Read Committed, we haven't committed yet.

        // Commit it, 2pc.
        xaRes.commit(xid, false);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        rs = st.executeQuery("SELECT * FROM testxa1");
        assertTrue(rs.next()); // Read Committed!

        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // We should be able to commit, since we're on our original local tx connection
        conn.commit();
    }

    public void testLocalTXInterleaving() throws Exception {
        conn.setAutoCommit(false);
        conn.createStatement().execute("BEGIN");

        int localTxPID = ((PGConnection) conn).getBackendPID();

        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        int xaPID = ((PGConnection) conn).getBackendPID();
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        xaRes.end(xid, XAResource.TMSUSPEND);

        // Back to local TX Mode, on the same connection.
        Statement st = conn.createStatement();
        assertEquals(localTxPID, ((PGConnection) conn).getBackendPID());
        assertFalse(conn.getAutoCommit());

        // Resume, end, prepare, commit 2p.
        xaRes = xaconn.getXAResource();
        xaRes.start(xid, XAResource.TMRESUME);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.commit(xid, false);

        assertEquals(localTxPID, ((PGConnection) conn).getBackendPID());
        conn.commit();
    }

    public void testCloseSuspendedPhysicalCorrectness() throws Exception {
        // One connection starting off.
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.setAutoCommit(false);
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.createStatement().execute("BEGIN");
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Opens a second connection.
        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        xaRes.end(xid, XAResource.TMSUSPEND);
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());


        // Back to local TX Mode, on the same connection.
        Statement st = conn.createStatement();
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        ResultSet rs = st.executeQuery("SELECT * FROM testxa1");
        assertFalse(rs.next());
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Closing the xaconnection should result in 1 physical connection remaining (the suspended xid);
        xaconn.close();
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Get a new xaConn to mess with.
        xaconn = _ds.getXAConnection();

        // Get a new connection, which will have autocommit ENABLED.
        conn = xaconn.getConnection();
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());

        // Resume, end, prepare, commit 2p.
        xaRes = xaconn.getXAResource();
        xaRes.start(xid, XAResource.TMRESUME);
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.prepare(xid);
        xaRes.commit(xid, false);

        rs = conn.createStatement().executeQuery("SELECT * FROM testxa1");
        assertTrue(rs.next());
        assertEquals(2, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
    }

    
    public void testCloseInTx() throws Exception {
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        // Open a 2nd xaconnection so we don't automagically close everything.
        XAConnection xaconn2 = _ds.getXAConnection();

        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        conn.close();
        xaconn.close(); // This will close the existing physical for the Xid.

        assertEquals(0, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        assertEquals(0, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        // This will result in a new physical for the Xid.
        xaRes.end(xid, XAResource.TMSUCCESS);

        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        assertEquals(0, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        xaRes.rollback(xid);

        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        // So the tests will continue and clean-up.
        xaconn = xaconn2;
    }

    public void testCloseEndFailedTx() throws Exception {
        assertEquals(1, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        // Open a 2nd xaconnection so we don't automagically close everything.
        XAConnection xaconn2 = _ds.getXAConnection();

        Xid xid = new CustomXid(42);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(42)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(43)");
        conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES(44)");
        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        xaRes.end(xid, XAResource.TMFAIL);

        assertEquals(0, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        xaRes.prepare(xid);

        assertEquals(1, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        conn.close();
        xaconn.close();

        assertEquals(0, ((PGFullXADataSource) _ds).getPhysicalConnectionCount());
        assertEquals(0, ((PGFullXADataSource) _ds).getCloseableConnectionCount((PGFullXAConnection) xaconn));

        // So the tests will continue and clean-up.
        xaconn = xaconn2;
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
