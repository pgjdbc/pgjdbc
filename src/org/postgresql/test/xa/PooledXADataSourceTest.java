package org.postgresql.test.xa;

import junit.framework.TestCase;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.optional.BaseDataSourceTest;
import org.postgresql.xa.PGXADataSource;

import javax.sql.XAConnection;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import javax.transaction.xa.XAException;

/**
 * @author agray@polarislabs.com
 */
public class PooledXADataSourceTest extends TestCase {

    private PGXADataSource ds;
    private List<XAConnection> pool;
    private int poolsize = 3;

    private final AtomicInteger counter = new AtomicInteger();
    
    public PooledXADataSourceTest(final String name) {
        super(name);
        ds = new PGXADataSource();
        BaseDataSourceTest.setupDataSource(ds);
        pool = new LinkedList<XAConnection>();
    }

    @Override
    protected void setUp() throws Exception {

        Connection conn = TestUtil.openDB();

        TestUtil.createTable(conn, "testxa1", "foo int");

        clearAllPrepared();

        for (int i = 0; i < poolsize; i++) {
            pool.add(ds.getXAConnection());
        }
    }

    @Override
    protected void tearDown() throws Exception {

        for (int i = 0; i < poolsize; i++) {
            pool.get(i).close();
        }

        assertEquals(0, ds.getPhysicalConnectionCount());
        clearAllPrepared();

        Connection conn = TestUtil.openDB();

        TestUtil.dropTable(conn, "testxa1");

        TestUtil.closeDB(conn);
    }

    private void clearAllPrepared() throws Exception {
        Connection conn = TestUtil.openDB();
        Statement st = conn.createStatement();
        try {
            ResultSet rs = st.executeQuery("SELECT gid FROM pg_prepared_xacts");

            Statement st2 = conn.createStatement();
            while (rs.next()) {
                st2.executeUpdate("ROLLBACK PREPARED '" + rs.getString(1) + "'");
            }
            st2.close();
        } finally {
            st.close();
        }
    }

    public void testOnePhase() throws Exception {
        XAConnection xaconn = pool.iterator().next();
        pool.remove(xaconn);
        XAResource xaRes = xaconn.getXAResource();
        Connection conn = xaconn.getConnection();

        Xid xid = new XADataSourceTest.CustomXid(1);
        xaRes.start(xid, XAResource.TMNOFLAGS);
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);

        pool.add(xaconn);
    }

    /**
     * Does a one-phase commit test on every connection in the pool plus one.
     * Each connection, once used will be put at the back of the list.
     * As such, the first connection will be reused once while the other two
     * will be used only once each.
     *
     * @throws Exception
     */
    public void testPoolUsage() throws Exception {
        for (int i = 0; i < poolsize + 1; i++) {
            XAConnection xaconn = pool.get(0);
            pool.remove(xaconn);
            XAResource xaRes = xaconn.getXAResource();
            Connection conn = xaconn.getConnection();

            Xid xid = new XADataSourceTest.CustomXid(1);
            xaRes.start(xid, XAResource.TMNOFLAGS);
            conn.createStatement().executeQuery("SELECT * FROM testxa1");
            xaRes.end(xid, XAResource.TMSUCCESS);
            xaRes.commit(xid, true);

            pool.add(pool.size(), xaconn);
        }
    }

    public void testPoolUsageThreaded() throws Exception {

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(3);

        new Thread(new OnePhaseThread(startLatch, endLatch)).start();
        new Thread(new OnePhaseThread(startLatch, endLatch)).start();
        new Thread(new OnePhaseThread(startLatch, endLatch)).start();

        startLatch.countDown();
        endLatch.await();
    }
    
    
    /**
     * Tests a race condition between commit & close(). The same condition is possible with prepare() and rollback() as well.
     * 
     * @throws Exception 
     */
    public void testCommitSharedCloseRace() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch preCommitLatch = new CountDownLatch(3);
        CountDownLatch commitLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(3);
        
        new Thread(new TwoPhaseThread(new XADataSourceTest.CustomXid(1), startLatch, preCommitLatch, commitLatch, endLatch) {
            @Override
            protected XAResource createXAResource(XAConnection connection) throws SQLException {
                return new DelayedCommitResource(super.createXAResource(connection));
            }
        }).start();
        
        new Thread(new TwoPhaseThread(new XADataSourceTest.CustomXid(2), startLatch, preCommitLatch, commitLatch, endLatch) {
            @Override
            protected XAResource createXAResource(XAConnection connection) throws SQLException {
                return new DelayedCommitResource(super.createXAResource(connection));
            }
        }).start();
        
        new Thread(new TwoPhaseThread(new XADataSourceTest.CustomXid(3), startLatch, preCommitLatch, commitLatch, endLatch) {
            @Override
            protected XAResource createXAResource(XAConnection connection) throws SQLException {
                return new DelayedCommitResource(super.createXAResource(connection));
            }
        }).start();
        
        startLatch.countDown();
        preCommitLatch.await();
        
        commitLatch.countDown();
        endLatch.await();
    }
    
    class TwoPhaseThread implements Runnable {
        private Xid xid;
        private CountDownLatch startLatch;
        private CountDownLatch preCommitLatch;
        private CountDownLatch commitLatch;
        private CountDownLatch endLatch;
        
        TwoPhaseThread(final Xid xid, final CountDownLatch startLatch, final CountDownLatch endLatch) {
            this(xid, startLatch, null, null, endLatch);
        }
        
        TwoPhaseThread(final Xid xid, final CountDownLatch startLatch, final CountDownLatch preCommitLatch, final CountDownLatch commitLatch, final CountDownLatch endLatch) {
            this.xid = xid;
            this.startLatch = startLatch;
            this.preCommitLatch = preCommitLatch;
            this.commitLatch = commitLatch;
            this.endLatch = endLatch;
        }
        
        protected XAResource createXAResource(XAConnection connection) throws SQLException {
            return connection.getXAResource();
        }
        
        @Override
        public void run() {
            try {
                startLatch.await();
                XAConnection xaconn = pool.get(0);
                pool.remove(xaconn);
                final XAResource xaRes = createXAResource(xaconn);
                Connection conn = xaconn.getConnection();

                xaRes.start(xid, XAResource.TMNOFLAGS);
                conn.createStatement().executeUpdate("INSERT INTO testxa1 VALUES (" + counter.incrementAndGet() + ")");
                conn.createStatement().executeQuery("SELECT * FROM testxa1");
                xaRes.end(xid, XAResource.TMSUCCESS);
                xaRes.prepare(xid);
                pool.add(pool.size(), xaconn);
                
                if (preCommitLatch != null) {
                    preCommitLatch.countDown();
                }
                
                // Get the thread ready.
                Committer commitThread = new Committer(xid, xaRes);
                if (commitLatch != null) {
                    commitLatch.await();
                }
                
                commitThread.start();
            } catch (Throwable t) {
                t.printStackTrace();
                fail();
            } finally {
                endLatch.countDown();
            }
        }
    }
    
    class Committer extends Thread {
        Xid xid;
        XAResource xares;
        XAException xae;

        public Committer(Xid xid, XAResource xares) {
            this.xid = xid;
            this.xares = xares;
            this.xae = null;
        }
        
        public void run() {
            try {
                xares.commit(xid, false);
            } catch (XAException xe) {
                xe.printStackTrace();
                fail();
            }
        }

        public XAException getXAException() {
            return xae;
        }
    }

    class OnePhaseThread implements Runnable {

        private CountDownLatch startLatch;
        private CountDownLatch endLatch;

        OnePhaseThread(final CountDownLatch startLatch, final CountDownLatch endLatch) {
            this.startLatch = startLatch;
            this.endLatch = endLatch;
        }

        @Override
        public void run() {
            try {
                startLatch.await();
                XAConnection xaconn = pool.get(0);
                pool.remove(xaconn);
                XAResource xaRes = xaconn.getXAResource();
                Connection conn = xaconn.getConnection();

                Xid xid = new XADataSourceTest.CustomXid(1);
                xaRes.start(xid, XAResource.TMNOFLAGS);
                conn.createStatement().executeQuery("SELECT * FROM testxa1");
                xaRes.end(xid, XAResource.TMSUCCESS);
                xaRes.commit(xid, true);

                pool.add(pool.size(), xaconn);

                endLatch.countDown();
            } catch (Throwable t) {
                t.printStackTrace();
                fail();
            }
        }
    }
    
    private class DelayedCommitResource implements XAResource {
        private XAResource delegate;
        
        public DelayedCommitResource(XAResource xaRes) {
            this.delegate = xaRes;
        }
                
        public void commit(Xid xid, boolean bln) throws XAException {
            // Delay the invocation to force the issue.
            try {
                Thread.currentThread().sleep(2);
            } catch (InterruptedException ie) {
            }
            delegate.commit(xid, bln);
        }

        public void end(Xid xid, int i) throws XAException {
            delegate.end(xid, i);
        }

        public void forget(Xid xid) throws XAException {
            delegate.forget(xid);
        }

        public int getTransactionTimeout() throws XAException {
            return delegate.getTransactionTimeout();
        }

        public boolean isSameRM(XAResource xar) throws XAException {
            return delegate.isSameRM(xar);
        }

        public int prepare(Xid xid) throws XAException {
            return delegate.prepare(xid);
        }

        public Xid[] recover(int i) throws XAException {
            return delegate.recover(i);
        }

        public void rollback(Xid xid) throws XAException {
            delegate.rollback(xid);
        }

        public boolean setTransactionTimeout(int i) throws XAException {
            return delegate.setTransactionTimeout(i);
        }

        public void start(Xid xid, int i) throws XAException {
            delegate.start(xid, i);
        }
    }
}
