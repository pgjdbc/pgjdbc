/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * A Sync that the driver forces to drain pending responses (issue #194) is sent only while
 * responses of the current execution are pending, and a batch sends nothing after the error it
 * reports.
 *
 * <p>The driver estimates 250 bytes of response for each statement it has not described, and
 * forces a Sync before a statement that would take the estimate to 64000 bytes. The statement
 * counts in these tests are chosen against those two numbers. The tests read the driver's protocol
 * log, so they run in isolation, and they pin {@code preferQueryMode=extended} so that every
 * statement is sent through the extended protocol, with its Executes and Syncs.</p>
 */
@Isolated("Captures org.postgresql logs, so it needs to work in isolation from the other tests")
class ForcedSyncTest extends BaseTest4 {
  private static final Pattern FE_EXECUTE_OR_SYNC = Pattern.compile("FE=> (Execute|Sync)");

  private TestLogHandler logHandler;
  private Logger driverLogger;
  private Level previousDriverLogLevel;

  ForcedSyncTest() {
    setPreferQueryMode(PreferQueryMode.EXTENDED);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    driverLogger = LogManager.getLogManager().getLogger("org.postgresql");
    previousDriverLogLevel = driverLogger.getLevel();
    driverLogger.setLevel(Level.ALL);
    logHandler = new TestLogHandler();
    driverLogger.addHandler(logHandler);
  }

  @Override
  protected void tearDown() throws SQLException {
    if (driverLogger != null) {
      if (logHandler != null) {
        driverLogger.removeHandler(logHandler);
      }
      driverLogger.setLevel(previousDriverLogLevel);
    }
    super.tearDown();
    // A failed batch can leave con out of sync with the server, so drop the tables through a
    // connection of their own.
    try (Connection cleanup = TestUtil.openDB()) {
      TestUtil.dropTable(cleanup, "forced_sync_a");
    }
  }

  /**
   * Returns, for each Sync logged since the last {@code records.clear()}, the number of Executes
   * logged before it.
   */
  private List<Integer> executeCountsAtEachSync() {
    List<Integer> executeCounts = new ArrayList<>();
    int executes = 0;
    for (LogRecord record : logHandler.getRecordsMatching(FE_EXECUTE_OR_SYNC)) {
      if (record.getMessage().contains("Execute")) {
        executes++;
      } else {
        executeCounts.add(executes);
      }
    }
    return executeCounts;
  }

  private static String contents(Connection con, String table) throws SQLException {
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT count(*), max(id) FROM " + table)) {
      assertTrue(rs.next(), "count(*) returns a row");
      return "count=" + rs.getLong(1) + ", max=" + rs.getObject(2);
    }
  }

  /**
   * Each entry inserts three rows, and the forced Sync falls before entry 86, the first that
   * would take the estimate to 64000 bytes. Entry 5 repeats a key, and that Sync reports the
   * failure. The driver sends nothing of entry 86 after it: a statement sent without a Sync after
   * it would run as part of the next query on the connection. Entries 1 to 85 ran in one implicit
   * transaction that the failure rolls back.
   */
  @Test
  void batchStoppedByAnErrorAtAForcedSyncSendsNoFurtherStatement() throws SQLException {
    TestUtil.createTable(con, "forced_sync_a", "id int PRIMARY KEY");
    String insert = "INSERT INTO forced_sync_a VALUES (?) RETURNING id";
    try (PreparedStatement ps = con.prepareStatement(insert + "; " + insert + "; " + insert)) {
      for (int entry = 1; entry <= 100; entry++) {
        ps.setInt(1, entry == 5 ? 1 : 3 * entry - 2);
        ps.setInt(2, 3 * entry - 1);
        ps.setInt(3, 3 * entry);
        ps.addBatch();
      }
      assertThrows(BatchUpdateException.class, ps::executeBatch);
    }

    assertEquals("count=0, max=null", contents(con, "forced_sync_a"), "forced_sync_a");
  }

  /**
   * Each entry's {@code text} result column has no bounded width, so the estimate for one entry
   * alone fills the buffer. A Sync drains only responses that are already pending, so the driver
   * forces one before each entry but the first. The first run describes the statement, and the
   * second, which is the one measured, sends the three entries with nothing else in between.
   */
  @Test
  void firstEntryIsSentWithoutAForcedSyncEvenWhenItFillsTheBuffer() throws SQLException {
    TestUtil.execute(con, "CREATE TEMP TABLE forced_sync (data text)");
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO forced_sync VALUES ('x') RETURNING data")) {
      for (int run = 1; run <= 2; run++) {
        for (int entry = 1; entry <= 3; entry++) {
          ps.addBatch();
        }
        logHandler.records.clear();
        ps.executeBatch();
      }
    }

    assertEquals(Arrays.asList(1, 2, 3), executeCountsAtEachSync(),
        "Execute counts at each Sync for 3 entries");
  }

  /**
   * Every entry of the batch is described, and its {@code text} result column has no bounded
   * width, so the estimate for one entry alone fills the buffer and a Sync is forced before each
   * entry after the first. The duplicate key fails the second entry, the Sync before the third
   * reports it, and the batch stops without sending the third. Nothing of the next query's
   * execution is pending when it starts, so the only Sync it sends is the one after its Execute.
   */
  @Test
  void queryAfterABatchStoppedByAnErrorForcesNoSync() throws SQLException {
    TestUtil.execute(con, "CREATE TEMP TABLE forced_sync (id int PRIMARY KEY, data text)");
    try (PreparedStatement ps = con.prepareStatement(
        "INSERT INTO forced_sync VALUES (?, 'x') RETURNING data")) {
      for (int id : new int[]{1, 1, 2}) {
        ps.setInt(1, id);
        ps.addBatch();
      }
      assertThrows(BatchUpdateException.class, ps::executeBatch);
    }

    logHandler.records.clear();
    try (Statement stmt = con.createStatement();
         ResultSet rs = stmt.executeQuery("SELECT 1")) {
      assertTrue(rs.next(), "SELECT 1 returns a row");
    }
    assertEquals(Collections.singletonList(1), executeCountsAtEachSync(),
        "Execute counts at each Sync for SELECT 1");
  }
}
