/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.Assert.assertEquals;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.BufferGenerator;
import org.postgresql.test.util.StrangeInputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Random;

/**
 * @author amozhenin on 30.09.2015.
 */
public class CopyLargeFileTest {

  private static final int FEED_COUNT = 10;

  private Connection con;
  private CopyManager copyAPI;

  @Before
  public void setUp() throws Exception {
    con = TestUtil.openDB();

    TestUtil.createTable(con, "pgjdbc_issue366_test_glossary",
        "id SERIAL, text_id VARCHAR(1000) NOT NULL UNIQUE, name VARCHAR(10) NOT NULL UNIQUE");
    TestUtil.createTable(con, "pgjdbc_issue366_test_data",
        "id SERIAL,\n"
            + "data_text_id VARCHAR(1000) NOT NULL /*UNIQUE <-- it slows down inserts due to additional index */,\n"
            + "glossary_text_id VARCHAR(1000) NOT NULL /* REFERENCES pgjdbc_issue366_test_glossary(text_id) */,\n"
            + "value DOUBLE PRECISION NOT NULL");

    feedTable();
    BufferGenerator.main(new String[]{});
    copyAPI = ((PGConnection) con).getCopyAPI();
  }

  private void feedTable() throws Exception {
    PreparedStatement stmt = con.prepareStatement(
        TestUtil.insertSQL("pgjdbc_issue366_test_glossary", "text_id, name", "?, ?"));
    for (int i = 0; i < 26; i++) {
      char ch = (char) ('A' + i); // black magic
      insertData(stmt, "VERY_LONG_STRING_TO_REPRODUCE_ISSUE_366_" + ch + ch + ch,
          "" + ch + ch + ch);
    }
  }

  private void insertData(PreparedStatement stmt, String textId, String name) throws SQLException {
    stmt.setString(1, textId);
    stmt.setString(2, name);
    stmt.executeUpdate();
  }

  @After
  public void tearDown() throws Exception {
    try {
      TestUtil.dropTable(con, "pgjdbc_issue366_test_data");
      TestUtil.dropTable(con, "pgjdbc_issue366_test_glossary");
      new File("target/buffer.txt").delete();
    } finally {
      con.close();
    }
  }

  @Test
  public void testFeedTableSeveralTimesTest() throws Throwable {
    for (int i = 1; i <= FEED_COUNT; i++) {
      feedTableAndCheckTableFeedIsOk(con);
      cleanupTable(con);
    }
  }

  private void feedTableAndCheckTableFeedIsOk(Connection conn) throws Throwable {
    Long seed = Long.getLong("StrangeInputStream.seed");
    if (seed == null) {
      seed = new Random().nextLong();
    }
    InputStream in = null;
    try {
      in = new StrangeInputStream(new FileInputStream("target/buffer.txt"), seed);
      long size = copyAPI.copyIn(
          "COPY pgjdbc_issue366_test_data(data_text_id, glossary_text_id, value) FROM STDIN", in);
      assertEquals(BufferGenerator.ROW_COUNT, size);
    } catch (Throwable t) {
      String message = "Using seed = " + seed + " for StrangeInputStream. Set -DStrangeInputStream.seed="
          + seed + " to reproduce the test";
      t.addSuppressed(new Throwable(message) {
        @Override
        public synchronized Throwable fillInStackTrace() {
          return this;
        }
      });
    } finally {
      if (in != null) {
        in.close();
      }
    }
  }

  private void cleanupTable(Connection conn) throws Exception {
    CallableStatement stmt = null;
    try {
      stmt = conn.prepareCall("TRUNCATE pgjdbc_issue366_test_data;");
      stmt.execute();
    } finally {
      if (stmt != null) {
        stmt.close();
      }
    }

  }
}
