/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class PGCopyInputStreamTest {
  private Connection conn;
  private PGCopyInputStream sut;
  private String copyParams;

  @Before
  public void setUp() throws Exception {
    conn = TestUtil.openDB();
    TestUtil.createTable(conn, "cpinstreamtest", "i int");
    copyParams = "(FORMAT CSV, HEADER false)";
  }

  @After
  public void tearDown() throws SQLException {
    silentlyCloseStream(sut);
    TestUtil.dropTable(conn, "cpinstreamtest");
    TestUtil.closeDB(conn);
  }

  @Test
  public void testReadBytesCorrectlyHandlesEof() throws SQLException, IOException {
    insertSomeData();

    sut = new PGCopyInputStream((PGConnection) conn,
        "COPY cpinstreamtest (i) TO STDOUT WITH " + copyParams);

    byte[] buf = new byte[100]; // large enough to read everything on the next step
    assertTrue(sut.read(buf) > 0);

    assertEquals(-1, sut.read(buf));
  }

  @Test
  public void testReadBytesCorrectlyReadsDataInChunks() throws SQLException, IOException {
    insertSomeData();

    sut = new PGCopyInputStream((PGConnection) conn,
        "COPY (select i from cpinstreamtest order by i asc) TO STDOUT WITH " + copyParams);

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

  @Test
  public void testStreamCanBeClosedAfterReadUp() throws SQLException, IOException {
    insertSomeData();

    sut = new PGCopyInputStream((PGConnection) conn,
        "COPY (select i from cpinstreamtest order by i asc) TO STDOUT WITH " + copyParams);

    byte[] buff = new byte[100];
    while (sut.read(buff) > 0) {
      // do nothing
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
    PreparedStatement pstmt = conn.prepareStatement("insert into cpinstreamtest (i) values (?)");
    for (int i = 0; i < 4; ++i) {
      pstmt.setInt(1, i);
      pstmt.addBatch();
    }
    pstmt.executeBatch();
  }
}
