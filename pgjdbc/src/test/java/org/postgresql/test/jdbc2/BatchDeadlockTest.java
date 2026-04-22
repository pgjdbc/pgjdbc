/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.CountingSocketFactory;
import org.postgresql.util.TestLogHandler;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Tests for client/server deadlock prevention during batch execution.
 *
 * <p>See <a href="https://github.com/pgjdbc/pgjdbc/issues/194">issue #194</a>.
 *
 * <h2>Background</h2>
 *
 * <p>When pipelining a large batch, the driver sends queries while the server sends back results.
 * If the driver does not periodically flush and read results, both sides can block on their
 * respective TCP send buffers, causing a deadlock.
 *
 * <p>The driver avoids this via {@code flushIfDeadlockRisk()} in {@code QueryExecutorImpl}, which
 * estimates the amount of buffered response data and forces a Sync+flush when it nears the TCP
 * buffer limit. To estimate accurately, it needs the query to be described first (via a Parse +
 * Describe round-trip), so it can call {@code getMaxResultRowSize()} on the query's result fields.
 */
@ParameterizedClass
@MethodSource("data")
@Isolated("Captures org.postgresql logs, so it needs to work in isolation from the other tests")
public class BatchDeadlockTest extends BaseTest4 {
  public enum ReturningInQuery {
    ID_DATA("id", "data"),
    STAR("*"),
    ID("id"),
    // Note: Statement.RETURN_GENERATED_KEYS causes pgjdbc to add `RETURNING *` which is bad for performance
    NO();

    final String[] columns;

    ReturningInQuery(String... columns) {
      this.columns = columns;
    }

    /**
     * Whether the RETURNING list brings back the large {@code data} varchar. If false, the
     * per-row response is a handful of bytes and the whole batch fits in the receive buffer
     * with a single flush.
     */
    boolean returnsLargeData() {
      return this == ID_DATA || this == STAR || this == NO;
    }

  }

  /**
   * Number of rows in the batch. Combined with {@link #PAYLOAD_SIZE}, the total RETURNING data
   * must exceed the TCP buffer capacity (~128KB on most systems) to trigger the deadlock without
   * the fix. 500 rows × 30KB = 15MB, well above any realistic buffer size.
   */
  private static final int BATCH_SIZE = 500;

  /**
   * Size of the text payload per row in characters.
   * Query executor tries to keep receiving buffer under 64000 bytes,
   * so if we have payloads greater than 32K, then it would effectively be a "one-by-one" processing.
   */
  private static final int PAYLOAD_SIZE = 30_000;

  private final String payload = String.join("", Collections.nCopies(PAYLOAD_SIZE, "X"));

  private static final Pattern FE_DESCRIBE_STATEMENT = Pattern.compile("FE=> Describe\\(statement");
  private static final Pattern FE_DESCRIBE_PORTAL = Pattern.compile("FE=> Describe\\(portal");
  private static final Pattern FE_EXECUTE = Pattern.compile("FE=> Execute");
  private static final Pattern FE_SYNC = Pattern.compile("FE=> Sync");

  private final ReturningInQuery returningInQuery;

  private TestLogHandler logHandler;
  private Logger driverLogger;
  private Level previousDriverLogLevel;
  private CountingSocketFactory.Counters socketCounters;

  public BatchDeadlockTest(ReturningInQuery returningInQuery, BinaryMode binaryMode) {
    this.returningInQuery = returningInQuery;
    setBinaryMode(binaryMode);
  }

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    if (socketCounters == null) {
      socketCounters = CountingSocketFactory.register();
    }
    PGProperty.SOCKET_FACTORY.set(props, CountingSocketFactory.class.getName());
    PGProperty.SOCKET_FACTORY_ARG.set(props, socketCounters.key());
  }

  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (ReturningInQuery returningInQuery : ReturningInQuery.values()) {
      for (BinaryMode binaryMode : BinaryMode.values()) {
        ids.add(new Object[]{returningInQuery, binaryMode});
      }
    }
    return ids;
  }

  @BeforeAll
  static void createTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.createTable(con, "batch_deadlock_test", "id SERIAL PRIMARY KEY, data varchar(" + PAYLOAD_SIZE + ")");
    }
  }

  @AfterAll
  static void dropTables() throws Exception {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropTable(con, "batch_deadlock_test");
    }
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    TestUtil.execute(con, "TRUNCATE batch_deadlock_test");
    con.setAutoCommit(false);
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
    try {
      super.tearDown();
    } finally {
      if (socketCounters != null) {
        CountingSocketFactory.unregister(socketCounters);
        socketCounters = null;
      }
    }
  }

  /**
   * Verifies that a large batch using {@code prepareStatement(sql, String[])} with a large
   * {@code TEXT} column completes without deadlock.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void largePreparedBatchWithGeneratedKeysDoesNotDeadlock() throws Exception {
    assumeNotSimpleQueryMode();
    long roundtripsBefore = socketCounters.roundtrips.get();
    int describeStatementBefore = logHandler.getRecordsMatching(FE_DESCRIBE_STATEMENT).size();
    int describePortalBefore = logHandler.getRecordsMatching(FE_DESCRIBE_PORTAL).size();
    int executeBefore = logHandler.getRecordsMatching(FE_EXECUTE).size();
    int syncBefore = logHandler.getRecordsMatching(FE_SYNC).size();
    try (PreparedStatement ps = preparedStatement()) {
      for (int i = 0; i < BATCH_SIZE; i++) {
        ps.setString(1, payload);
        ps.addBatch();
      }
      ps.executeBatch();
    }
    long roundtrips = socketCounters.roundtrips.get() - roundtripsBefore;
    int describeStatement =
        logHandler.getRecordsMatching(FE_DESCRIBE_STATEMENT).size() - describeStatementBefore;
    int describePortal =
        logHandler.getRecordsMatching(FE_DESCRIBE_PORTAL).size() - describePortalBefore;
    int executes = logHandler.getRecordsMatching(FE_EXECUTE).size() - executeBefore;
    int syncs = logHandler.getRecordsMatching(FE_SYNC).size() - syncBefore;

    String metrics = String.format(
        "BATCH_SIZE=%d, roundtrips=%d, Describe(statement)=%d, Describe(portal)=%d, "
            + "Execute=%d, Sync=%d",
        BATCH_SIZE, roundtrips, describeStatement, describePortal, executes, syncs);

    // Pre-describe should happen about once (for the statement), not per row.
    assertTrue(describeStatement <= 3,
        () -> "Describe(statement) should fire ~once (pre-describe), got " + metrics);
    // Describe(portal) fires per Bind when the query returns rows (RETURNING / generated keys),
    // so ~BATCH_SIZE is expected. Catch regressions that send extras (2x per row etc.).
    assertTrue(describePortal <= BATCH_SIZE + 5,
        () -> "Describe(portal) should be ~BATCH_SIZE, got " + metrics);
    assertTrue(executes <= BATCH_SIZE + 2,
        () -> "Execute count should be ~BATCH_SIZE, got " + metrics);
    // Sync / roundtrip upper bounds depend on whether the RETURNING clause brings back the
    // large varchar. With large data, many forced flushes happen; without, the whole batch
    // fits in the receive buffer and only the terminating Sync fires.
    if (returningInQuery.returnsLargeData()) {
      assertTrue(syncs < BATCH_SIZE, () -> "Sync should not fire per row, got " + metrics);
      assertTrue(roundtrips < BATCH_SIZE, () -> "batch should pipeline, got " + metrics);
    } else {
      int expectedRoundtrips = BATCH_SIZE * 250 /* bytes per row */ * 2 / 64000;
      assertTrue(syncs <= expectedRoundtrips,
          () -> "small RETURNING fits in receive buffer — expected a few terminating Syncs, "
              + "got " + metrics);
      assertTrue(roundtrips <= expectedRoundtrips,
          () -> "small RETURNING fits in receive buffer — expected a few write→read cycles, "
              + "got " + metrics);
    }
  }

  private PreparedStatement preparedStatement() throws SQLException {
    switch (returningInQuery) {
      case STAR:
        return con.prepareStatement(
            "INSERT INTO batch_deadlock_test(data) VALUES (?) RETURNING *");
      case NO:
        return con.prepareStatement(
            "INSERT INTO batch_deadlock_test(data) VALUES (?)",
            Statement.RETURN_GENERATED_KEYS);
      default:
        return con.prepareStatement(
            "INSERT INTO batch_deadlock_test(data) VALUES (?)",
            returningInQuery.columns);
    }
  }

  /**
   * Verifies that a large batch using {@code createStatement(sql, String[])} with a large
   * {@code TEXT} column completes without deadlock.
   */
  @Test
  @Disabled("Deadlock with simple (non-prepared) addBatch(sql) is a known issue")
  @Timeout(value = 10, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void largeSimpleBatchWithGeneratedKeysDoesNotDeadlock() throws Exception {
    String sql;
    switch (returningInQuery) {
      case STAR:
        sql = "INSERT INTO batch_deadlock_test(data) VALUES ('" + payload + "') RETURNING *";
        break;
      case NO:
        sql = "INSERT INTO batch_deadlock_test(data) VALUES ('" + payload + "')";
        break;
      default:
        sql =
            "INSERT INTO batch_deadlock_test(data) VALUES ('" + payload + "') RETURNING " + String.join(",", returningInQuery.columns);
        break;
    }
    try (Statement ps = con.createStatement()) {
      for (int i = 0; i < BATCH_SIZE; i++) {
        ps.addBatch(sql);
      }
      ps.executeBatch();
    }
  }
}
