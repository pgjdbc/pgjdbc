package org.postgresql.test.jdbc4;

import java.io.ByteArrayInputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import junit.framework.TestCase;

import org.junit.Assert;
import org.postgresql.test.TestUtil;

/**
 * This test-case is only for JDBC4 blob methods.
 * Take a look at {@link org.postgresql.test.jdbc2.BlobTest} for base tests concerning blobs
 */
public class BlobTest extends TestCase
{

    private Connection _conn;

    protected void setUp() throws Exception
    {
        _conn = TestUtil.openDB();
        TestUtil.createTable(_conn, "testblob", "id name,lo oid");
        _conn.setAutoCommit(false);
    }

    protected void tearDown() throws Exception
    {
        _conn.setAutoCommit(true);
        TestUtil.dropTable(_conn, "testblob");
        TestUtil.closeDB(_conn);
    }

    public void testSetBlobWithStream() throws Exception
    {
        byte[] data = new String("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Pellentesque bibendum dapibus varius.").getBytes("UTF-8");
        PreparedStatement insertPS = _conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?"));
        try
        {
            insertPS.setBlob(1, new ByteArrayInputStream(data));
            insertPS.executeUpdate();
        }
        finally
        {
            insertPS.close();
        }

        Statement selectStmt = _conn.createStatement();
        try
        {
            ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"));
            assertTrue(rs.next());

            Blob actualBlob = rs.getBlob(1);
            byte[] actualBytes = actualBlob.getBytes(1, (int) actualBlob.length());

            Assert.assertArrayEquals(data, actualBytes);
        }
        finally
        {
            selectStmt.close();
        }
    }

    public void testSetBlobWithStreamAndLength() throws Exception
    {
        byte[] fullData = new String("Lorem ipsum dolor sit amet, consectetur adipiscing elit. Suspendisse placerat tristique tellus, id tempus lectus.").getBytes("UTF-8");
        byte[] data =     new String("Lorem ipsum dolor sit amet, consectetur adipiscing elit.").getBytes("UTF-8");
        PreparedStatement insertPS = _conn.prepareStatement(TestUtil.insertSQL("testblob", "lo", "?"));
        try
        {
            insertPS.setBlob(1, new ByteArrayInputStream(fullData), data.length);
            insertPS.executeUpdate();
        }
        finally
        {
            insertPS.close();
        }

        Statement selectStmt = _conn.createStatement();
        try
        {
            ResultSet rs = selectStmt.executeQuery(TestUtil.selectSQL("testblob", "lo"));
            assertTrue(rs.next());

            Blob actualBlob = rs.getBlob(1);
            byte[] actualBytes = actualBlob.getBytes(1, (int) actualBlob.length());

            Assert.assertArrayEquals(data, actualBytes);
        }
        finally
        {
            selectStmt.close();
        }
    }
}
