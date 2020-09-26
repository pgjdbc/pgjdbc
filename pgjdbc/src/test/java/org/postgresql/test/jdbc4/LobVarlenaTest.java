/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

// import static org.junit.Assert.assertEquals;
// import static org.junit.Assert.assertFalse;
// import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PgBlobBytea;
import org.postgresql.jdbc.PgClobText;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
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
    props.setProperty(PGProperty.LOB_VARLENA.getName(),"true");
    conn = TestUtil.openDB(props);
    TestUtil.createTable(conn, "testlobvarlena",
                         "textcol text, byteacol bytea");
    TestUtil.execute("insert into testlobvarlena values("
                     + "repeat('ksjkdjkdshtrb\\000ujy2t8mnnksdf',500),"
                     + "decode(repeat('ksjkds\\000trbut8mnnksdf',600),"
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
    assertTrue(((PgConnection)conn).getLobVarlena());
    Clob clob = conn.createClob();
    assertTrue(clob instanceof PgClobText);
    assertTrue(clob.length() == 0);
    clob.setString(1,"foobarbaz");
    assertTrue(clob.length() == 9);
    assertTrue(clob.toString().equals("foobarbaz"));
    String s = clob.getSubString(4,3);
    assertTrue(s.equals("bar"));
    String s2 = clob.getSubString(7,99);
    assertTrue(s2.equals("baz"));
    long pos = clob.position("bar",1);
    assertTrue(pos == 4);
    pos = clob.position("bar",4);
    assertTrue(pos == 4);
    pos = clob.position("bar",7);
    assertTrue(pos == -1);
    pos = clob.position("blurfl",4);
    assertTrue(pos == -1);
    Clob srch = new PgClobText("bar");
    pos = clob.position(srch,1);
    assertTrue(pos == 4);
    pos = clob.position(srch,4);
    assertTrue(pos == 4);
    pos = clob.position(srch,7);
    assertTrue(pos == -1);
    srch.setString(1,"blurfl");
    pos = clob.position(srch,4);
    assertTrue(pos == -1);
    clob.truncate(3);
    assertTrue(clob.length() == 3);
    String s3 = clob.toString();
    assertTrue(s3.equals("foo"));
  }

  @Test
  public void testResultSetClob() throws SQLException {
    // test we can get a clob from a text field in a resultset
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "textcol"));
    try {
      assertTrue(((PgConnection)conn).getLobVarlena());
      assertTrue(rs.next());
      Clob clob = rs.getClob(1);
      assertTrue(clob instanceof PgClobText);
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
  public void testClobStreams() throws SQLException, IOException {
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
      assertTrue(cs.equals(w.toString()));
      r = clob.getCharacterStream(100,20);
      w = new StringWriter(300);
      while ((i = r.read()) != -1) {
        w.write(i);
      }
      w.close();
      String cs2 = clob.getSubString(100,20);
      String ws = w.toString();
      assertTrue(cs2.equals(ws));
      char[] buffer  = new char[(int)clob.length()];
      InputStream is = clob.getAsciiStream();
      int index = 0;
      int c = is.read();
      while (c > 0) {
        buffer[index++] = (char) c;
        c = is.read();
      }
      String as = new String(buffer);
      assertTrue(as.equals(cs));
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
    assertTrue(((PgConnection)conn).getLobVarlena());
    Blob blob = conn.createBlob();
    assertTrue(blob instanceof PgBlobBytea);
    assertTrue(blob.length() == 0);
    blob.setBytes(1,foobarbaz);
    assertTrue(blob.length() == 9);
    assertTrue(Arrays.equals(blob.getBytes(1,999),foobarbaz));
    byte[] s = blob.getBytes(4,3);
    assertTrue(Arrays.equals(s,bar));
    byte[] s2 = blob.getBytes(7,99);
    assertTrue(s2.length == 3);
    assertTrue(Arrays.equals(s2,baz));
    long pos = blob.position(bar,1);
    assertTrue(pos == 4);
    pos = blob.position(bar,4);
    assertTrue(pos == 4);
    pos = blob.position(bar,7);
    assertTrue(pos == -1);
    pos = blob.position(blurfl,4);
    assertTrue(pos == -1);
    Blob srch = new PgBlobBytea(bar);
    pos = blob.position(srch,1);
    assertTrue(pos == 4);
    pos = blob.position(srch,4);
    assertTrue(pos == 4);
    pos = blob.position(srch,7);
    assertTrue(pos == -1);
    srch.setBytes(1,blurfl);
    pos = blob.position(srch,4);
    assertTrue(pos == -1);
    blob.truncate(3);
    assertTrue(blob.length() == 3);
    assertTrue(Arrays.equals(blob.getBytes(1,999),foo));
  }

  @Test
  public void testResultSetBlob() throws SQLException {
    // test we can get a blob from a bytea field in a resultset
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery(TestUtil.selectSQL("testlobvarlena", "byteacol"));
    try {
      assertTrue(((PgConnection)conn).getLobVarlena());
      assertTrue(rs.next());
      Blob blob = rs.getBlob(1);
      assertTrue(blob instanceof PgBlobBytea);
    } finally {
      rs.close();
    }
  }

  @Test
  public void testBlobStatementParams() throws SQLException {
    // test we can set a Blob as a parameter
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
  public void testBlobStreams() throws SQLException, IOException {
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
      assertTrue(index == b.length);
      assertTrue(Arrays.equals(b,buffer));
    } finally {
      rs.close();
    }
  }
}
