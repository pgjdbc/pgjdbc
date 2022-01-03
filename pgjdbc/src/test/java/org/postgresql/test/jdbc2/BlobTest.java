/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.postgresql.core.ServerVersion;
import org.postgresql.largeobject.LargeObject;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;

/**
 * Some simple tests based on problems reported by users. Hopefully these will help prevent previous
 * problems from re-occurring ;-)
 */
public class BlobTest {
  private static final String TEST_FILE =  "/test-file.xml";

  private static final int LOOP = 0; // LargeObject API using loop
  private static final int NATIVE_STREAM = 1; // LargeObject API using OutputStream

  private Connection con;

  /*
    Only do this once
  */
  @BeforeClass
  public static void createLargeBlob() throws Exception {
    try ( Connection con = TestUtil.openDB() ) {
      TestUtil.createTable(con, "testblob", "id name,lo oid");
      con.setAutoCommit(false);
      LargeObjectManager lom = ((org.postgresql.PGConnection) con).getLargeObjectAPI();
      long oid = lom.createLO(LargeObjectManager.READWRITE);
      LargeObject blob = lom.open(oid);

      byte[] buf = new byte[256];
      for (int i = 0; i < buf.length; i++) {
        buf[i] = (byte) i;
      }
      // I want to create a large object
      int i = 1024 / buf.length;
      for (int j = i; j > 0; j--) {
        blob.write(buf, 0, buf.length);
      }
      assertEquals(1024, blob.size());
      blob.close();
      try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(id, lo) VALUES(?,?)")) {
        pstmt.setString(1, "l1");
        pstmt.setLong(2, oid);
        pstmt.executeUpdate();
      }
      con.commit();
    }
  }

  @AfterClass
  public static void cleanup() throws Exception {
    try ( Connection con = TestUtil.openDB() ) {
      try (Statement stmt = con.createStatement()) {
        stmt.execute("SELECT lo_unlink(lo) FROM testblob where id = 'l1'");
      } finally {
        TestUtil.dropTable(con, "testblob");
      }
    }
  }

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
  }

  @After
  public void tearDown() throws Exception {
    con.setAutoCommit(true);
    try ( Statement stmt = con.createStatement() ) {
      stmt.execute("SELECT lo_unlink(lo) FROM testblob where id != 'l1'");
      stmt.execute("delete from testblob where id != 'l1'");
    } finally {
      TestUtil.closeDB(con);
    }
  }

  @Test
  public void testSetNull() throws Exception {
    try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(lo) VALUES (?)")) {

      pstmt.setBlob(1, (Blob) null);
      pstmt.executeUpdate();

      pstmt.setNull(1, Types.BLOB);
      pstmt.executeUpdate();

      pstmt.setObject(1, null, Types.BLOB);
      pstmt.executeUpdate();

      pstmt.setClob(1, (Clob) null);
      pstmt.executeUpdate();

      pstmt.setNull(1, Types.CLOB);
      pstmt.executeUpdate();

      pstmt.setObject(1, null, Types.CLOB);
      pstmt.executeUpdate();
    }
  }

  @Test
  public void testSet() throws SQLException {
    try ( Statement stmt = con.createStatement() ) {
      stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', lo_creat(-1))");
      ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '1'");
      assertTrue(rs.next());

      PreparedStatement pstmt = con.prepareStatement("INSERT INTO testblob(id, lo) VALUES(?,?)");

      Blob blob = rs.getBlob(1);
      pstmt.setString(1, "setObjectTypeBlob");
      pstmt.setObject(2, blob, Types.BLOB);
      assertEquals(1, pstmt.executeUpdate());

      blob = rs.getBlob(1);
      pstmt.setString(1, "setObjectBlob");
      pstmt.setObject(2, blob);
      assertEquals(1, pstmt.executeUpdate());

      blob = rs.getBlob(1);
      pstmt.setString(1, "setBlob");
      pstmt.setBlob(2, blob);
      assertEquals(1, pstmt.executeUpdate());

      Clob clob = rs.getClob(1);
      pstmt.setString(1, "setObjectTypeClob");
      pstmt.setObject(2, clob, Types.CLOB);
      assertEquals(1, pstmt.executeUpdate());

      clob = rs.getClob(1);
      pstmt.setString(1, "setObjectClob");
      pstmt.setObject(2, clob);
      assertEquals(1, pstmt.executeUpdate());

      clob = rs.getClob(1);
      pstmt.setString(1, "setClob");
      pstmt.setClob(2, clob);
      assertEquals(1, pstmt.executeUpdate());
    }
  }

  /*
   * Tests one method of uploading a blob to the database
   */
  @Test
  public void testUploadBlob_LOOP() throws Exception {
    assertTrue(uploadFile(TEST_FILE, LOOP) > 0);

    // Now compare the blob & the file. Note this actually tests the
    // InputStream implementation!
    assertTrue(compareBlobsLOAPI(TEST_FILE));
    assertTrue(compareBlobs(TEST_FILE));
    assertTrue(compareClobs(TEST_FILE));
  }

  /*
   * Tests one method of uploading a blob to the database
   */
  @Test
  public void testUploadBlob_NATIVE() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    // Now compare the blob & the file. Note this actually tests the
    // InputStream implementation!
    assertTrue(compareBlobs(TEST_FILE));
  }

  @Test
  public void testMarkResetStream() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try ( Statement stmt = con.createStatement() ) {
      try ( ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'") ) {
        assertTrue(rs.next());

        LargeObjectManager lom = ((org.postgresql.PGConnection) con).getLargeObjectAPI();

        long oid = rs.getLong(1);
        LargeObject blob = lom.open(oid);
        InputStream bis = blob.getInputStream();

        assertEquals('<', bis.read());
        bis.mark(4);
        assertEquals('?', bis.read());
        assertEquals('x', bis.read());
        assertEquals('m', bis.read());
        assertEquals('l', bis.read());
        bis.reset();
        assertEquals('?', bis.read());
      }
    }
  }

  @Test
  public void testGetBytesOffset() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try ( Statement stmt = con.createStatement() ) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {

        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        byte[] data = lob.getBytes(2, 4);
        assertEquals(data.length, 4);
        assertEquals(data[0], '?');
        assertEquals(data[1], 'x');
        assertEquals(data[2], 'm');
        assertEquals(data[3], 'l');
      }
    }
  }

  @Test
  public void testMultipleStreams() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try ( Statement stmt = con.createStatement() ) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {
        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        byte[] data = new byte[2];

        InputStream is = lob.getBinaryStream();
        assertEquals(data.length, is.read(data));
        assertEquals(data[0], '<');
        assertEquals(data[1], '?');
        is.close();

        is = lob.getBinaryStream();
        assertEquals(data.length, is.read(data));
        assertEquals(data[0], '<');
        assertEquals(data[1], '?');
        is.close();
      }
    }
  }

  @Test
  public void testParallelStreams() throws Exception {
    assertTrue(uploadFile(TEST_FILE, NATIVE_STREAM) > 0);

    try ( Statement stmt = con.createStatement() ) {
      try (ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id = '/test-file.xml'")) {
        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        InputStream is1 = lob.getBinaryStream();
        InputStream is2 = lob.getBinaryStream();

        while (true) {
          int i1 = is1.read();
          int i2 = is2.read();
          assertEquals(i1, i2);
          if (i1 == -1) {
            break;
          }
        }

        is1.close();
        is2.close();
      }
    }
  }

  @Test
  public void testLargeLargeObject() throws Exception {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_3)) {
      return;
    }

    try ( Statement stmt = con.createStatement() ) {
      stmt.execute("INSERT INTO testblob(id,lo) VALUES ('1', lo_creat(-1))");
      try ( ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id ='1'") ) {
        assertTrue(rs.next());

        Blob lob = rs.getBlob(1);
        long length = ((long) Integer.MAX_VALUE) + 1024;
        lob.truncate(length);
        assertEquals(length, lob.length());
      }
    }
  }

  @Test
  public void testLargeObjectRead() throws Exception {
    con.setAutoCommit(false);
    LargeObjectManager lom = ((org.postgresql.PGConnection) con).getLargeObjectAPI();
    try ( Statement stmt = con.createStatement() ) {
      try ( ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id='l1'") ) {
        assertTrue(rs.next());

        long oid = rs.getLong(1);
        InputStream lois = lom.open(oid).getInputStream();
        // read half of the data with read
        for ( int j = 0; j < 512; j++ ) {
          lois.read();
        }
        byte []buf2 = new byte[512];
        lois.read(buf2, 0, 512);
        lois.close();

      }
    }
    con.commit();
  }

  @Test
  public void testLargeObjectRead1() throws Exception {
    con.setAutoCommit(false);
    LargeObjectManager lom = ((org.postgresql.PGConnection) con).getLargeObjectAPI();
    try ( Statement stmt = con.createStatement() ) {
      try ( ResultSet rs = stmt.executeQuery("SELECT lo FROM testblob where id='l1'") ) {
        assertTrue(rs.next());

        long oid = rs.getLong(1);
        InputStream lois = lom.open(oid).getInputStream(512, 1024);
        // read one byte
        assertEquals(0, lois.read());
        byte []buf2 = new byte[1024];
        int bytesRead = lois.read(buf2, 0, buf2.length);
        assertEquals( 1023, bytesRead);
        assertEquals(1, buf2[0]);
        lois.close();

      }
    }
    con.commit();
  }

  /*
   * Helper - uploads a file into a blob using old style methods. We use this because it always
   * works, and we can use it as a base to test the new methods.
   */
  private long uploadFile(String file, int method) throws Exception {
    LargeObjectManager lom = ((org.postgresql.PGConnection) con).getLargeObjectAPI();

    InputStream fis = getClass().getResourceAsStream(file);

    long oid = lom.createLO(LargeObjectManager.READWRITE);
    LargeObject blob = lom.open(oid);

    int s;
    int t;
    byte[] buf;
    OutputStream os;

    switch (method) {
      case LOOP:
        buf = new byte[2048];
        t = 0;
        while ((s = fis.read(buf, 0, buf.length)) > 0) {
          t += s;
          blob.write(buf, 0, s);
        }
        break;

      case NATIVE_STREAM:
        os = blob.getOutputStream();
        s = fis.read();
        while (s > -1) {
          os.write(s);
          s = fis.read();
        }
        os.close();
        break;

      default:
        fail("Unknown method in uploadFile");
    }

    blob.close();
    fis.close();

    // Insert into the table
    Statement st = con.createStatement();
    st.executeUpdate(TestUtil.insertSQL("testblob", "id,lo", "'" + file + "'," + oid));
    con.commit();
    st.close();

    return oid;
  }

  /*
   * Helper - compares the blobs in a table with a local file. Note this uses the postgresql
   * specific Large Object API
   */
  private boolean compareBlobsLOAPI(String id) throws Exception {
    boolean result = true;

    LargeObjectManager lom = ((org.postgresql.PGConnection) con).getLargeObjectAPI();

    try ( Statement st = con.createStatement() ) {
      try ( ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo", "id = '" + id + "'")) ) {
        assertNotNull(rs);

        while (rs.next()) {
          String file = rs.getString(1);
          long oid = rs.getLong(2);

          InputStream fis = getClass().getResourceAsStream(file);
          LargeObject blob = lom.open(oid);
          InputStream bis = blob.getInputStream();

          int f = fis.read();
          int b = bis.read();
          int c = 0;
          while (f >= 0 && b >= 0 & result) {
            result = (f == b);
            f = fis.read();
            b = bis.read();
            c++;
          }
          result = result && f == -1 && b == -1;

          if (!result) {
            fail("Large Object API Blob compare failed at " + c + " of " + blob.size());
          }

          blob.close();
          fis.close();
        }
      }
    }
    return result;
  }

  /*
   * Helper - compares the blobs in a table with a local file. This uses the jdbc java.sql.Blob api
   */
  private boolean compareBlobs(String id) throws Exception {
    boolean result = true;

    try ( Statement st = con.createStatement() ) {
      try (ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo", "id = '" + id + "'"))) {
        assertNotNull(rs);

        while (rs.next()) {
          String file = rs.getString(1);
          Blob blob = rs.getBlob(2);

          InputStream fis = getClass().getResourceAsStream(file);
          InputStream bis = blob.getBinaryStream();

          int f = fis.read();
          int b = bis.read();
          int c = 0;
          while (f >= 0 && b >= 0 & result) {
            result = (f == b);
            f = fis.read();
            b = bis.read();
            c++;
          }
          result = result && f == -1 && b == -1;

          if (!result) {
            fail("JDBC API Blob compare failed at " + c + " of " + blob.length());
          }

          bis.close();
          fis.close();
        }
      }
    }
    return result;
  }

  /*
   * Helper - compares the clobs in a table with a local file.
   */
  private boolean compareClobs(String id) throws Exception {
    boolean result = true;

    try ( Statement st = con.createStatement() ) {
      try (ResultSet rs = st.executeQuery(TestUtil.selectSQL("testblob", "id,lo", "id = '" + id + "'"))) {
        assertNotNull(rs);

        while (rs.next()) {
          String file = rs.getString(1);
          Clob clob = rs.getClob(2);

          InputStream fis = getClass().getResourceAsStream(file);
          InputStream bis = clob.getAsciiStream();

          int f = fis.read();
          int b = bis.read();
          int c = 0;
          while (f >= 0 && b >= 0 & result) {
            result = (f == b);
            f = fis.read();
            b = bis.read();
            c++;
          }
          result = result && f == -1 && b == -1;

          if (!result) {
            fail("Clob compare failed at " + c + " of " + clob.length());
          }

          bis.close();
          fis.close();
        }
      }
    }

    return result;
  }
}
