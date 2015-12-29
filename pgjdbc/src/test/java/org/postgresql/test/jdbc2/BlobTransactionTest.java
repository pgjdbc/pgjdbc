/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.test.jdbc2;

import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import javax.sql.rowset.serial.SerialBlob;

/**
 * Test that oid/lob are accessible in concurrent connection, in presence of the lo_manage trigger
 * Require the lo module accessible in $libdir
 */
public class BlobTransactionTest extends TestCase {

  private Connection con;
  private Connection con2;

  public BlobTransactionTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
    con2 = TestUtil.openDB();
    con2.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);

    TestUtil.createTable(con, "testblob", "id name,lo oid");

    String sql;

    Statement st;
/*
 * this would have to be executed using the postgres user in order to get access to a C function
 *
 */
    Connection privilegedCon = TestUtil.openPrivilegedDB();
    st = privilegedCon.createStatement();
    try {
      sql =
          "CREATE OR REPLACE FUNCTION lo_manage() RETURNS pg_catalog.trigger AS '$libdir/lo' LANGUAGE C";
      st.executeUpdate(sql);
    } finally {
      st.close();
    }

    st = privilegedCon.createStatement();
    try {
      sql =
          "CREATE TRIGGER testblob_lomanage BEFORE UPDATE OR DELETE ON testblob FOR EACH ROW EXECUTE PROCEDURE lo_manage(lo)";
      st.executeUpdate(sql);
    } finally {
      st.close();
    }

    con.setAutoCommit(false);
    con2.setAutoCommit(false);
  }

  protected void tearDown() throws Exception {
    TestUtil.closeDB(con2);

    con.setAutoCommit(true);
    try {
      Statement stmt = con.createStatement();
      try {
        stmt.execute("SELECT lo_unlink(lo) FROM testblob");
      } finally {
        try {
          stmt.close();
        } catch (Exception e) {
        }
      }
    } finally {
      TestUtil.dropTable(con, "testblob");
      TestUtil.closeDB(con);
    }
  }

  private byte[] randomData() {
    byte[] data = new byte[64 * 1024 * 8];
    for (int i = 0; i < data.length; ++i) {
      data[i] = (byte) (Math.random() * 256);
    }
    return data;
  }

  private byte[] readInputStream(InputStream is) throws IOException {
    byte[] result = new byte[1024];
    int readPos = 0;
    int d;
    while ((d = is.read()) != -1) {
      if (readPos == result.length) {
        result = Arrays.copyOf(result, result.length * 2);
      }
      result[readPos++] = (byte) d;
    }

    return Arrays.copyOf(result, readPos);
  }

  public void testConcurrentReplace() throws SQLException, IOException {
//        Statement stmt = con.createStatement();
//        stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', lo_creat(-1))");
//        ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob");
//        assertTrue(rs.next());

    PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(id, lo) VALUES(?,?)");

    byte[] initialData = randomData();

    pstmt.setString(1, "testConcurrentReplace");
    pstmt.setObject(2, new SerialBlob(initialData), Types.BLOB);
    assertEquals(1, pstmt.executeUpdate());

    con.commit();

    con2.rollback();

    // con2 access the blob
    PreparedStatement pstmt2 = con2.prepareStatement("SELECT lo FROM testblob WHERE id=?");
    pstmt2.setString(1, "testConcurrentReplace");
    ResultSet rs2 = pstmt2.executeQuery();
    assertTrue(rs2.next());


    // con replace the blob
    byte[] newData = randomData();
    pstmt = con.prepareStatement("UPDATE testblob SET lo=? where id=?");
    pstmt.setObject(1, new SerialBlob(newData), Types.BLOB);
    pstmt.setString(2, "testConcurrentReplace");
    assertEquals(1, pstmt.executeUpdate());

    // con2 read the blob content
    Blob initContentBlob = rs2.getBlob(1);
    byte[] initialContentReRead = readInputStream(initContentBlob.getBinaryStream());
    assertEquals(initialContentReRead.length, initialData.length);
    for (int i = 0; i < initialContentReRead.length; ++i) {
      assertEquals(initialContentReRead[i], initialData[i]);
    }


    con2.rollback();
    pstmt2 = con2.prepareStatement("SELECT lo FROM testblob WHERE id=?");
    pstmt2.setString(1, "testConcurrentReplace");
    rs2 = pstmt2.executeQuery();
    assertTrue(rs2.next());

    // con commit
    con.commit();

    initContentBlob = rs2.getBlob(1);
    initialContentReRead = readInputStream(initContentBlob.getBinaryStream());
    assertEquals(initialContentReRead.length, initialData.length);
    for (int i = 0; i < initialContentReRead.length; ++i) {
      assertEquals(initialContentReRead[i], initialData[i]);
    }

    con2.commit();
  }
}
