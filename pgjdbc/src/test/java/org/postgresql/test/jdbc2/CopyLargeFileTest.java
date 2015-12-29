package org.postgresql.test.jdbc2;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.BufferGenerator;
import org.postgresql.test.util.StrangeInputStream;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Created by amozhenin on 30.09.2015.
 */
public class CopyLargeFileTest extends TestCase {

  private static final int FEED_COUNT = 10;

  private Connection con;
  private CopyManager copyAPI;


  protected void setUp() throws Exception {

    super.setUp();

    con = TestUtil.openDB();

    TestUtil.createTable(con, "pgjdbc_issue366_test_glossary",
        "id SERIAL, text_id VARCHAR(1000) NOT NULL UNIQUE, name VARCHAR(10) NOT NULL UNIQUE");
    TestUtil.createTable(con, "pgjdbc_issue366_test_data", "id SERIAL,\n"
        + "                                       data_text_id VARCHAR(1000) NOT NULL /*UNIQUE <-- it slows down inserts due to additional index */,\n"
        + "                                       glossary_text_id VARCHAR(1000) NOT NULL /* REFERENCES pgjdbc_issue366_test_glossary(text_id) */,\n"
        + "                                       value DOUBLE PRECISION NOT NULL");

    feedTable();
    BufferGenerator.main(new String[]{});
    copyAPI = ((PGConnection) con).getCopyAPI();
  }

  private void feedTable() throws Exception {
    PreparedStatement stmt = con.prepareStatement(
        TestUtil.insertSQL("pgjdbc_issue366_test_glossary", "text_id, name", "?, ?"));
    char ch = ' ';
    for (int i = 0; i < 26; i++) {
      ch = (char) ((int) 'A' + i); //black magic
      insertData(stmt, "VERY_LONG_STRING_TO_REPRODUCE_ISSUE_366_" + ch + ch + ch,
          "" + ch + ch + ch);
    }
  }

  private void insertData(PreparedStatement stmt, String textId, String name) throws SQLException {
    stmt.setString(1, textId);
    stmt.setString(2, name);
    stmt.executeUpdate();
  }

  protected void tearDown() throws Exception {
    super.tearDown();
    try {
      TestUtil.dropTable(con, "pgjdbc_issue366_test_data");
      TestUtil.dropTable(con, "pgjdbc_issue366_test_glossary");
      new File("target/buffer.txt").delete();
    } finally {
      con.close();
    }
  }

  public void testFeedTableSeveralTimesTest() throws Exception {
    for (int i = 1; i <= FEED_COUNT; i++) {
      feedTableAndCheckTableFeedIsOk(con);
      cleanupTable(con);
    }
  }

  private void feedTableAndCheckTableFeedIsOk(Connection conn) throws Exception {
    InputStream in = null;
    try {
      in = new StrangeInputStream(new FileInputStream("target/buffer.txt"));
      long size = copyAPI.copyIn(
          "COPY pgjdbc_issue366_test_data(data_text_id, glossary_text_id, value) FROM STDIN", in);
      assertEquals(BufferGenerator.ROW_COUNT, size);
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
