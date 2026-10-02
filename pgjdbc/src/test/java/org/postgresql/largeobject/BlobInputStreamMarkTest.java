/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.largeobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGConnection;
import org.postgresql.test.TestUtil;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

@Isolated("Captures java.util.logging output, so it must not run concurrently with other tests")
class BlobInputStreamMarkTest {
  private Connection con;
  private LargeObjectManager lom;
  private long loid;

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();
    con.setAutoCommit(false);
    lom = con.unwrap(PGConnection.class).getLargeObjectAPI();
    loid = lom.createLO();
    byte[] content = new byte[64];
    for (int i = 0; i < content.length; i++) {
      content[i] = (byte) i;
    }
    try (LargeObject blob = lom.open(loid, LargeObjectManager.WRITE)) {
      blob.write(content);
    }
  }

  @AfterEach
  void tearDown() throws SQLException {
    TestUtil.closeDB(con);
  }

  /**
   * Marking a closed stream has no effect, which is what {@link InputStream#mark(int)} promises
   * and what {@link BlobInputStream#mark(int)} repeats. A promised no-op is not a failure, so it
   * must not reach the log: it used to arrive as a SEVERE record carrying a stack trace, which is
   * what an application sees when it closes a buffered stream that marks as it goes.
   */
  @Test
  void markOnAClosedStreamIsSilent() throws Exception {
    InputStream bis = lom.open(loid, LargeObjectManager.READ).getInputStream();
    bis.close();

    Logger logger = Logger.getLogger(BlobInputStream.class.getName());
    List<LogRecord> records = new ArrayList<>();
    Handler collector = new Handler() {
      @Override
      public void publish(LogRecord record) {
        records.add(record);
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    logger.addHandler(collector);
    try {
      bis.mark(10);
    } finally {
      logger.removeHandler(collector);
    }

    assertTrue(records.isEmpty(), () -> "log records from marking a closed stream: " + records);
  }

  /**
   * Marking an open stream still works, so the closed-stream shortcut has not swallowed the
   * ordinary path.
   */
  @Test
  void markAndResetOnAnOpenStream() throws Exception {
    try (LargeObject blob = lom.open(loid, LargeObjectManager.READ)) {
      InputStream bis = blob.getInputStream();
      assertEquals(0, bis.read());
      bis.mark(10);
      assertEquals(1, bis.read());
      assertEquals(2, bis.read());
      bis.reset();
      assertEquals(1, bis.read());
    }
  }
}
