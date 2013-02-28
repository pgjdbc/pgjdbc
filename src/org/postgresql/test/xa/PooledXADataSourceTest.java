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
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * @author agray@polarislabs.com
 */
public class PooledXADataSourceTest extends TestCase {

    private PGXADataSource ds;
    private List<XAConnection> pool;
    private int poolsize = 3;

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

        for(XAConnection conn : pool) {
            conn.close();
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
}
