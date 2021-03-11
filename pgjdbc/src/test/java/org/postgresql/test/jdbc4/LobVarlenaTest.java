/*
 * Copyright (c) 2021, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.QueryExecutor;
import org.postgresql.jdbc.PgBlobBytea;
import org.postgresql.jdbc.PgClobText;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.Properties;

public class LobVarlenaTest {

  private Connection conn;

  @Before
  public void setUp() throws Exception {
    Properties props = new Properties();
    props.setProperty(PGProperty.BLOB_AS_BYTEA.getName(),"true");
    props.setProperty(PGProperty.CLOB_AS_TEXT.getName(),"true");
    conn = TestUtil.openDB(props);
    TestUtil.createTable(conn, "testlobvarlena",
                         "textcol text, byteacol bytea");
    TestUtil.execute("insert into testlobvarlena values("
                     + "repeat($$ksjkdjkdshtrb\\000ujy2t8mnnksdf$$,500),"
                     + "decode(repeat($$ksjkds\\000trbut8mnnksdf$$,600),"
                     + "'escape'))",
                     conn);
  }

  @After
  public void tearDown() throws Exception {
    TestUtil.dropTable(conn, "testlobvarlena");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testConnClob() throws SQLException {
    // test we can get a clob from the connection and do basic set, get,
    // and search operations on it
    assertTrue("Connection has clobAsText enabled", ((PgConnection)conn).getClobAsText());
    Clob clob = conn.createClob();
    assertTrue("Successfully create a Clob", clob instanceof PgClobText);
    assertEquals("Length of Clob is 0", clob.length(), 0);
    clob.setString(1,"foobarbaz");
    assertEquals("Clob length is 9", clob.length(), 9);
    assertTrue("Clob text is 'foobarbaz'", clob.toString().equals("foobarbaz"));
    String s = clob.getSubString(4,3);
    assertTrue("Fetched substring is 'bar'", s.equals("bar"));
    String s2 = clob.getSubString(7,99);
    assertTrue("Fetched substring is 'baz'", s2.equals("baz"));
    long pos = clob.position("bar",1);
    assertEquals("Found 'bar' at position 4 starting at 1", pos, 4);
    pos = clob.position("bar",4);
    assertEquals("Found 'bar' at position 4 starting at 4", pos, 4);
    pos = clob.position("bar",7);
    assertEquals("'bar' not found starting at 7", pos, -1);
    pos = clob.position("blurfl",4);
    assertEquals("'blurfl' not found starting at 4", pos, -1);
    Clob srch = new PgClobText("bar");
    pos = clob.position(srch,1);
    assertEquals("Found clob 'bar' at position 4 starting at 1", pos, 4);
    pos = clob.position(srch,4);
    assertEquals("Found clob 'bar' at position 4 starting at 4", pos, 4);
    pos = clob.position(srch,7);
    assertEquals("clob 'bar' not found starting at 7", pos, -1);
    srch.setString(1,"blurfl");
    pos = clob.position(srch,4);
    assertEquals("clob 'blurfl' not found starting at 4", pos, -1);
    clob.truncate(3);
    assertEquals("Truncated clob length is 3", clob.length(), 3);
    String s3 = clob.toString();
    assertTrue("Truncated clob value is 'foo'", s3.equals("foo"));
  }

  @Test
  public void testResultSetClob() throws SQLException {
    // test we can get a clob from a text field in a resultset
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "textcol"));
    try {
      assertTrue("Connection has getClobAsText", ((PgConnection)conn).getClobAsText());
      assertTrue("Result set has a row", rs.next());
      Clob clob = rs.getClob(1);
      assertTrue("rs.getClob(1) returns a PgClobText", clob instanceof PgClobText);
    } finally {
      rs.close();
    }
  }

  @Test
  public void testClobStatementParams() throws SQLException {
    // test we can set a string or a Clob as a parameter
    conn.setAutoCommit(false);
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO testlobvarlena (textcol) VALUES (?)");
    pstmt.setObject(1, "foo", Types.CLOB);
    pstmt.executeUpdate();
    Clob clob = conn.createClob();
    clob.setString(1,"foobar");
    pstmt.setObject(1, clob, Types.CLOB);
    pstmt.executeUpdate();
    conn.rollback();
  }

  @Test
  public void testClobReaderStreams() throws SQLException, IOException {
    // test streams operations of a Clob
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "textcol"));
    try {
      rs.next();
      Clob clob = rs.getClob(1);
      Reader r = clob.getCharacterStream();
      StringWriter w = new StringWriter((int)clob.length());
      int i;
      while ((i = r.read()) != -1) {
        w.write(i);
      }
      w.close();
      String cs = clob.toString();
      assertTrue("Clob contents = character stream", cs.equals(w.toString()));
      r = clob.getCharacterStream(100,20);
      w = new StringWriter(300);
      while ((i = r.read()) != -1) {
        w.write(i);
      }
      w.close();
      String cs2 = clob.getSubString(100,20);
      String ws = w.toString();
      assertTrue("Clob substring = character stream substring", cs2.equals(ws));
      char[] buffer  = new char[(int)clob.length()];
      InputStream is = clob.getAsciiStream();
      int index = 0;
      int c = is.read();
      while (c > 0) {
        buffer[index++] = (char) c;
        c = is.read();
      }
      String as = new String(buffer);
      assertTrue("Ascii stream equals clob contents", as.equals(cs));
    } finally {
      rs.close();
    }
  }

  @Test
  public void testClobWriterStreams() throws SQLException, IOException {
    // test streams operations of a Clob
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "textcol"));
    try {
      rs.next();
      Clob clob = rs.getClob(1);
      int origLen = (int) clob.length();
      OutputStream os = clob.setAsciiStream(origLen - 4);
      byte[] blurfl = "blurfl".getBytes();
      os.write(blurfl, 0, 5);
      assertEquals("Length stays the same", clob.length(), origLen);
      os.write("bazBarfoo".getBytes());
      assertEquals("Length grown by 9", clob.length(), origLen + 9);
      String end = clob.getSubString(origLen - 4, 999);
      assertTrue("Stream has written bytes correctly", end.equals("blurfbazBarfoo"));
      clob.truncate(origLen);
      Writer w = clob.setCharacterStream(origLen - 4);
      w.write("Blurfl", 0, 5);
      assertEquals("Length stays the same", clob.length(), origLen);
      w.write("BazBarfoo", 0, 9);
      assertEquals("Length grown by 9", clob.length(), origLen + 9);
      end = clob.getSubString(origLen - 4, 999);
      assertTrue("Stream has written bytes correctly", end.equals("BlurfBazBarfoo"));
    } finally {
      rs.close();
    }
  }

  @Test
  public void testConnBlob() throws SQLException {
    // test we can get a blob from the connection and do basic set, get,
    // and search operations on it
    byte[] foobarbaz = "foobarbaz".getBytes();
    byte[] foo = "foo".getBytes();
    byte[] bar = "bar".getBytes();
    byte[] baz = "baz".getBytes();
    byte[] blurfl = "blurfl".getBytes();
    assertTrue("Connection has blobAsBytea", ((PgConnection)conn).getBlobAsBytea());
    Blob blob = conn.createBlob();
    assertEquals("PgBlobBytea created", blob instanceof PgBlobBytea, true);
    assertEquals("Initial blob has length 0", blob.length(),  0);
    blob.setBytes(1,foobarbaz);
    assertEquals("Blob has length 9", blob.length(), 9);
    assertTrue("Blob has contents 'foobarbaz'", Arrays.equals(blob.getBytes(1,999),foobarbaz));
    byte[] s = blob.getBytes(4,3);
    assertTrue("blob subcontents = 'bar'", Arrays.equals(s,bar));
    byte[] s2 = blob.getBytes(7,99);
    assertEquals("blob remaining contents have length 3", s2.length, 3);
    assertTrue("blob remaining contents = 'baz'", Arrays.equals(s2,baz));
    long pos = blob.position(bar,1);
    assertEquals("blob position of 'bar' starting at 1 is 4", pos, 4);
    pos = blob.position(bar,4);
    assertEquals("blob position of 'bar' starting at 4 is 4", pos, 4);
    pos = blob.position(bar,7);
    assertEquals("'bar' not found in blob starting at 7", pos, -1);
    pos = blob.position(blurfl,4);
    assertEquals("'blurfl' not found in blob", pos, -1);
    Blob srch = new PgBlobBytea(bar);
    pos = blob.position(srch,1);
    assertEquals("blob position of blob 'bar' starting at 1 is 4", pos, 4);
    pos = blob.position(srch,4);
    assertEquals("blob position of blob 'bar' starting at 4 is 4", pos, 4);
    pos = blob.position(srch,7);
    assertEquals("blob 'bar' not found in blob starting at 7", pos, -1);
    srch.setBytes(1,blurfl);
    pos = blob.position(srch,4);
    assertEquals("blob 'blurfl' not found in blob", pos, -1);
    blob.truncate(3);
    assertEquals("truncated blob length = 3", blob.length(), 3);
    assertTrue("truncated blob contents = 'foo'", Arrays.equals(blob.getBytes(1,999),foo));
  }

  @Test
  public void testResultSetBlob() throws SQLException {
    // test we can get a blob from a bytea field in a resultset
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "byteacol"));
    try {
      assertTrue("Connection has blobAsText", ((PgConnection)conn).getBlobAsBytea());
      assertTrue("Result set has a row", rs.next());
      Blob blob = rs.getBlob(1);
      assertTrue("returned result is a PgBlobBytea", blob instanceof PgBlobBytea);
    } finally {
      rs.close();
    }
  }

  @Test
  public void testBlobStatementParams() throws SQLException {
    // test we can set a Blob as a parameter
    QueryExecutor qe = ((PgConnection)conn).getQueryExecutor();
    if (qe.getServerVersionNum() == 90606) {
      // this version apparently has trouble in Travis-CI / oraclejdk8
      return;
    }
    conn.setAutoCommit(false);
    PreparedStatement pstmt = conn.prepareStatement("INSERT INTO testlobvarlena (byteacol) VALUES (?)");
    byte[] foo = "foo".getBytes();
    byte[] foobar = "foobar".getBytes();
    pstmt.setObject(1, foo, Types.BLOB);
    pstmt.executeUpdate();
    Blob blob = conn.createBlob();
    blob.setBytes(1,foobar);
    pstmt.setObject(1, blob, Types.BLOB);
    pstmt.executeUpdate();
    conn.rollback();
  }

  @Test
  public void testBlobReaderStreams() throws SQLException, IOException {
    // test streams operations of a Blob
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "byteacol"));
    try {
      rs.next();
      Blob blob = rs.getBlob(1);
      byte[] b =  blob.getBytes(1,99999);
      InputStream is = blob.getBinaryStream();
      byte[] buffer = new byte[b.length];
      int index = 0;
      int c = is.read();
      while (c >= 0) {
        buffer[index++] = (byte) c;
        c = is.read();
      }
      assertEquals("Input stream from blob has corrrect length", index, b.length);
      assertTrue("Input stream from blob has correct contents", Arrays.equals(b,buffer));
    } finally {
      rs.close();
    }
  }

  @Test
  public void testBlobWriterStreams() throws SQLException, IOException {
    // test streams operations of a Blob
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "byteacol"));
    try {
      rs.next();
      Blob blob = rs.getBlob(1);
      int origLen = (int) blob.length();
      OutputStream os = blob.setBinaryStream(origLen - 4);
      byte[] blurfl = "blurfl".getBytes();
      os.write(blurfl, 0, 5);
      assertEquals("Length stays the same", blob.length(), origLen);
      os.write("bazBarfoo".getBytes());
      assertEquals("Length grown by 9", blob.length(), origLen + 9);
      byte[] end = blob.getBytes(origLen - 4, 999);
      assertTrue("Stream has written bytes correctly", Arrays.equals(end,"blurfbazBarfoo".getBytes()));
    } finally {
      rs.close();
    }
  }

}
