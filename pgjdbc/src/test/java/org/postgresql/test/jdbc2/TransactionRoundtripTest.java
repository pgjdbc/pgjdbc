/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.util.CountingSocketFactory;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Tests the number of network operations required to start and use a transaction.
 *
 * <p>The class is isolated because one test raises the level of the driver's
 * logger, which every connection in the JVM shares.
 */
@Isolated
@ParameterizedClass
@MethodSource("data")
public class TransactionRoundtripTest extends BaseTest4 {
  private static final Pattern READY_FOR_QUERY =
      Pattern.compile("<=BE ReadyForQuery");

  private CountingSocketFactory.Counters socketCounters = CountingSocketFactory.register();
  private final AutoSave autosave;
  private final boolean cleanupSavepoints;

  public TransactionRoundtripTest(PreferQueryMode preferQueryMode, AutoSave autosave,
      boolean cleanupSavepoints) {
    setPreferQueryMode(preferQueryMode);
    this.autosave = autosave;
    this.cleanupSavepoints = cleanupSavepoints;
  }

  public static Iterable<Object[]> data() {
    return Arrays.asList(new Object[][]{
        {PreferQueryMode.EXTENDED, AutoSave.NEVER, false},
        {PreferQueryMode.EXTENDED, AutoSave.ALWAYS, false},
        {PreferQueryMode.EXTENDED, AutoSave.ALWAYS, true},
        {PreferQueryMode.SIMPLE, AutoSave.NEVER, false},
        {PreferQueryMode.SIMPLE, AutoSave.ALWAYS, false},
        {PreferQueryMode.SIMPLE, AutoSave.ALWAYS, true}
    });
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.AUTOSAVE.set(props, autosave.value());
    PGProperty.CLEANUP_SAVEPOINTS.set(props, cleanupSavepoints);
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, socketCounters.key());
  }

  @Override
  protected void tearDown() throws SQLException {
    try {
      super.tearDown();
    } finally {
      CountingSocketFactory.unregister(socketCounters);
    }
  }

  @Test
  void beginAndQueryAreFlushedTogether() throws SQLException {
    con.setAutoCommit(false);
    long flushesBefore = socketCounters.flushes.get();
    long roundtripsBefore = socketCounters.roundtrips.get();

    try (Statement statement = con.createStatement()) {
      statement.executeQuery("SELECT 1").close();
    }

    int expectedFlushes = cleanupSavepoints ? 2 : 1;
    assertEquals(expectedFlushes, socketCounters.flushes.get() - flushesBefore,
        "BEGIN and the query should share one flush; savepoint cleanup needs another");
    assertEquals(1, socketCounters.roundtrips.get() - roundtripsBefore,
        "BEGIN and the query should complete in one roundtrip");
  }

  /**
   * Counts the ReadyForQuery messages for BEGIN and the first query. The
   * server sends one for each simple query, and one at the Sync that ends a
   * run of extended messages. In extended mode with autosave=never, BEGIN
   * must share the query's Sync: a simple BEGIN makes the server send two
   * small responses, and the second can wait up to 40 ms for a delayed ACK.
   */
  @Test
  void beginSharesTheReadyForQueryOfTheQuery() throws SQLException {
    con.setAutoCommit(false);
    Logger driverLogger = Logger.getLogger("org.postgresql");
    Level previousLevel = driverLogger.getLevel();
    TestLogHandler logHandler = new TestLogHandler();
    driverLogger.setLevel(Level.ALL);
    driverLogger.addHandler(logHandler);
    try (Statement statement = con.createStatement()) {
      statement.executeQuery("SELECT 1").close();
    } finally {
      driverLogger.removeHandler(logHandler);
      driverLogger.setLevel(previousLevel);
    }

    // One for the query: its own when it is simple, or the one at its Sync.
    int expected = 1;
    if (preferQueryMode == PreferQueryMode.SIMPLE
        || autosave != AutoSave.NEVER) {
      // BEGIN is a simple query.
      expected++;
    }
    if (autosave != AutoSave.NEVER) {
      // SAVEPOINT is a simple query.
      expected++;
    }
    // cleanupSavepoints adds a RELEASE SAVEPOINT, but the driver reads its
    // response with the next query, so it does not count here.
    int readyForQuery =
        logHandler.getRecordsMatching(READY_FOR_QUERY).size();
    assertEquals(expected, readyForQuery,
        "ReadyForQuery messages for BEGIN and the first query");
  }
}
