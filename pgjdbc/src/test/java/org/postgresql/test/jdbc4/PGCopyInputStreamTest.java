package org.postgresql.test.jdbc4;

import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.test.TestUtil;

import junit.framework.TestCase;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PGCopyInputStreamTest extends TestCase {
  private Connection _conn;
  private PGCopyInputStream sut;

  public PGCopyInputStreamTest(String name) {
    super(name);
  }

  protected void setUp() throws Exception {
    _conn = TestUtil.openDB();
    TestUtil.createTable(_conn, "cpinstreamtest", "i int");
  }

  protected void tearDown() throws SQLException {
    silentlyCloseStream(sut);
    TestUtil.dropTable(_conn, "cpinstreamtest");
    TestUtil.closeDB(_conn);
  }

  public void testReadBytesCorrectlyHandlesEof() throws SQLException, IOException {
    insertSomeData();

    sut = new PGCopyInputStream((PGConnection) _conn,
        "COPY cpinstreamtest (i) TO STDOUT WITH (FORMAT CSV, HEADER false)");

    byte[] buf = new byte[100]; // large enough to read everything on the next step
    assertTrue(sut.read(buf) > 0);

    assertEquals(-1, sut.read(buf));
  }

  public void testReadBytesCorrectlyReadsDataInChunks() throws SQLException, IOException {
    insertSomeData();

    sut = new PGCopyInputStream((PGConnection) _conn,
        "COPY (select i from cpinstreamtest order by i asc) TO STDOUT WITH (FORMAT CSV, HEADER false)");

    byte[] buf = new byte[2]; // small enough to read in multiple chunks
    StringBuilder result = new StringBuilder(100);
    int chunks = 0;
    while (sut.read(buf) > 0) {
      result.append(new String(buf));
      ++chunks;
    }

    assertEquals(4, chunks);
    assertEquals("0\n1\n2\n3\n", result.toString());
  }

  public void testStreamCanBeClosedAfterReadUp() throws SQLException, IOException {
    insertSomeData();

    sut = new PGCopyInputStream((PGConnection) _conn,
        "COPY (select i from cpinstreamtest order by i asc) TO STDOUT WITH (FORMAT CSV, HEADER false)");

    byte[] buff = new byte[100];
    while (sut.read(buff) > 0) {
      ;
    }

    sut.close();
  }

  private void silentlyCloseStream(PGCopyInputStream sut) {
    if (sut != null) {
      try {
        if (sut.isActive()) {
          sut.close();
        }
      } catch (IOException e) {
        // intentionally ignore
      }
    }
  }

  private void insertSomeData() throws SQLException {
    PreparedStatement pstmt = _conn.prepareStatement("insert into cpinstreamtest (i) values (?)");
    for (int i = 0; i < 4; ++i) {
      pstmt.setInt(1, i);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
  }
}
