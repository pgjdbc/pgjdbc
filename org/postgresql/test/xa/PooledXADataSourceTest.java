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
import java.sql.Statement;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.PGConnection;

/**
 * @author agray@polarislabs.com
 */
public class PooledXADataSourceTest extends TestCase {

    private PGXADataSource ds;
    private List<XAConnection> pool;
    
    private static AtomicInteger xidCounter = new AtomicInteger(0);

    public PooledXADataSourceTest(final String name) {
        super(name);
        ds = new PGXADataSource();
        BaseDataSourceTest.setupDataSource(ds);
        pool = Collections.synchronizedList(new LinkedList<XAConnection>());
    }

    @Override
    protected void setUp() throws Exception {

        Connection conn = TestUtil.openDB();

        TestUtil.createTable(conn, "testxa1", "foo int");

        clearAllPrepared();

        for (int i = 0; i < 3; i++) {
            pool.add(ds.getXAConnection());
        }
    }

    @Override
    protected void tearDown() throws Exception {

        while (!pool.isEmpty()) {
            pool.remove(0).close();
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
        XAConnection xaconn = pool.remove(0);
        XAResource xaRes = xaconn.getXAResource();
        Connection conn = xaconn.getConnection();

        Xid xid = new XADataSourceTest.CustomXid(xidCounter.incrementAndGet());
        xaRes.start(xid, XAResource.TMNOFLAGS);
        conn.createStatement().executeQuery("SELECT * FROM testxa1");
        xaRes.end(xid, XAResource.TMSUCCESS);
        xaRes.commit(xid, true);

        conn.close();
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
        int count = pool.size() + 1;
        for (int i = 0; i < count; i++) {
            XAConnection xaconn = pool.remove(0);
            XAResource xaRes = xaconn.getXAResource();
            Connection conn = xaconn.getConnection();

            Xid xid = new XADataSourceTest.CustomXid(xidCounter.incrementAndGet());
            xaRes.start(xid, XAResource.TMNOFLAGS);
            conn.createStatement().executeQuery("SELECT * FROM testxa1");
            xaRes.end(xid, XAResource.TMSUCCESS);
            xaRes.commit(xid, true);

            conn.close();
            pool.add(pool.size(), xaconn);
        }
    }

    public void testPoolUsageThreaded() throws Exception {
        int poolsize = pool.size();
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(poolsize);

        for (int i = 0; i < poolsize; i++) {
            new Thread(new OnePhaseThread(startLatch, endLatch)).start();
        }

        startLatch.countDown();
        endLatch.await();
    }
    
    
    public void testPoolUsageLocalTXInterleaving() throws Exception {
        int poolsize = pool.size();
        
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(poolsize);

        for (int i = 0; i < poolsize; i++) {
            new Thread(new LocalTXInterleavingThread(startLatch, endLatch)).start();
        }

        startLatch.countDown();
        endLatch.await();
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
                XAConnection xaconn = pool.remove(0);
                XAResource xaRes = xaconn.getXAResource();
                Connection conn = xaconn.getConnection();

                Xid xid = new XADataSourceTest.CustomXid(xidCounter.incrementAndGet());
                xaRes.start(xid, XAResource.TMNOFLAGS);
                conn.createStatement().executeQuery("SELECT * FROM testxa1");
                xaRes.end(xid, XAResource.TMSUCCESS);
                
                xaRes.commit(xid, true);

                conn.close();
                pool.add(xaconn);
            } catch (Throwable t) {
                t.printStackTrace();
                fail(t.getMessage());
            } finally {
                endLatch.countDown();
            }
        }
    }
    
    
    class LocalTXInterleavingThread implements Runnable {
        private CountDownLatch startLatch;
        private CountDownLatch endLatch;
        
        LocalTXInterleavingThread(final CountDownLatch startLatch, final CountDownLatch endLatch) {
            this.startLatch = startLatch;
            this.endLatch = endLatch;
        }
        
        public void run() {
            try {
                startLatch.await();
                
                XAConnection xaconn = pool.remove(0);
                XAResource xaRes = xaconn.getXAResource();
                Connection conn = xaconn.getConnection();
                
                conn.setAutoCommit(false);
                conn.createStatement().execute("BEGIN");

                int localTxPID = ((PGConnection)conn).getBackendPID();

                Xid xid = new XADataSourceTest.CustomXid(xidCounter.incrementAndGet());
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
                
                conn.close();
                pool.add(xaconn);
            } catch (Throwable t) {
                t.printStackTrace();
                fail(t.getMessage());
            } finally {
                endLatch.countDown();
            }
        }
    }
    
    
}
