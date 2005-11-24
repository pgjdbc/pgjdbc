package org.postgresql.test.xa;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Random;

import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;


import org.postgresql.test.TestUtil;
import org.postgresql.xa.PGXADataSource;

import junit.framework.TestCase;

public class XADataSourceTest extends TestCase {

    private XADataSource _ds;

    private Connection _conn;

    private XAConnection xaconn;
    private XAResource xaRes;
    private Connection conn;

    public XADataSourceTest(String name) {
        super(name);

        _ds = new PGXADataSource();
        ((PGXADataSource)_ds).setServerName(TestUtil.getServer());
        ((PGXADataSource)_ds).setPortNumber(TestUtil.getPort());
        ((PGXADataSource)_ds).setUser(TestUtil.getUser());
        ((PGXADataSource)_ds).setPassword(TestUtil.getPassword());
        ((PGXADataSource)_ds).setDatabaseName(TestUtil.getDatabase());
        ((PGXADataSource)_ds).setPrepareThreshold(TestUtil.getPrepareThreshold());
    }

    protected void setUp() throws Exception {
        _conn = TestUtil.openDB();

        TestUtil.createTable(_conn, "testxa1", "foo int");

        clearAllPrepared();

        xaconn = _ds.getXAConnection();
        xaRes = xaconn.getXAResource();
        conn = xaconn.getConnection();
    }

    protected void tearDown() throws SQLException {
        xaconn.close();
        clearAllPrepared();

        TestUtil.dropTable(_conn, "testxa1");
        TestUtil.closeDB(_conn);

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

    /* We don't support transaction interleaving.
    public void testInterleaving1() throws Exception {
     Xid xid1 = new CustomXid(1);
     Xid xid2 = new CustomXid(2);
     
     xaRes.start(xid1, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = 'ccc'");
     xaRes.end(xid1, XAResource.TMSUCCESS);

     xaRes.start(xid2, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa2 SET foo = 'bbb'");

     xaRes.commit(xid1, true);

     xaRes.end(xid2, XAResource.TMSUCCESS);

     xaRes.commit(xid2, true);

    }
    public void testInterleaving2() throws Exception {
     Xid xid1 = new CustomXid(1);
     Xid xid2 = new CustomXid(2);
     Xid xid3 = new CustomXid(3);
     
     xaRes.start(xid1, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa1 SET foo = 'aa'");
     xaRes.end(xid1, XAResource.TMSUCCESS);
     
     xaRes.start(xid2, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa2 SET foo = 'bb'");
     xaRes.end(xid2, XAResource.TMSUCCESS);

     xaRes.start(xid3, XAResource.TMNOFLAGS);
     conn.createStatement().executeUpdate("UPDATE testxa3 SET foo = 'cc'");
     xaRes.end(xid3, XAResource.TMSUCCESS);

     xaRes.commit(xid1, true);
     xaRes.commit(xid2, true);
     xaRes.commit(xid3, true);
    }
    */
}
