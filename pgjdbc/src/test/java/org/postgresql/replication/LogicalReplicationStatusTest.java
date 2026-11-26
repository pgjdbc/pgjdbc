/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.IsEqual.equalTo;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.annotations.tags.Replication;
import org.postgresql.test.util.MessageStallProxyServer;
import org.postgresql.test.util.TimeoutRecordingSocketFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

@Replication
@EnabledForServerVersionRange(gte = "9.4")
class LogicalReplicationStatusTest {
  private static final int SOCKET_TIMEOUT_SECONDS = 60;
  private static final int FACTORY_SO_TIMEOUT_MS = 30_000;
  private static final int STATUS_INTERVAL_MS = 100;
  private static final String SLOT_NAME = "pgjdbc_logical_replication_slot";

  private Connection replicationConnection;
  private Connection sqlConnection;
  private Connection secondSqlConnection;

  @BeforeEach
  void setUp() throws Exception {
    //statistic available only for privileged user
    sqlConnection = TestUtil.openPrivilegedDB();
    secondSqlConnection = TestUtil.openPrivilegedDB(props -> {
      TestUtil.setTestUrlProperty(props, PGProperty.PG_DBNAME, "test_2");
    });
    //DriverManager.setLogWriter(new PrintWriter(System.out));
    replicationConnection = TestUtil.openReplicationConnection();
    TestUtil.createTable(sqlConnection, "test_logic_table",
        "pk serial primary key, name varchar(100)");
    TestUtil.createTable(secondSqlConnection, "test_logic_table",
        "pk serial primary key, name varchar(100)");

    TestUtil.recreateLogicalReplicationSlot(sqlConnection, SLOT_NAME, "test_decoding");
  }

  @AfterEach
  void tearDown() throws Exception {
    replicationConnection.close();
    TestUtil.dropTable(sqlConnection, "test_logic_table");
    TestUtil.dropTable(secondSqlConnection, "test_logic_table");
    TestUtil.dropReplicationSlot(sqlConnection, SLOT_NAME);
    secondSqlConnection.close();
    sqlConnection.close();
  }

  @Test
  void sentLocationEqualToLastReceiveLSN() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    final int countMessage = 3;

    List<String> received = receiveMessageWithoutBlock(stream, countMessage);
    LogSequenceNumber lastReceivedLSN = stream.getLastReceiveLSN();
    stream.forceUpdateStatus();

    LogSequenceNumber sentByServer = getLSNFromViewAtLeast(sentColumnName(), lastReceivedLSN);

    assertThat(
        "The server cannot send less than the stream received, so the sent LSN is at least the "
            + "last received LSN. It may be greater: with no decodable data pending, the walsender "
            + "advances the sent LSN with keepalive messages to the current end of WAL, and "
            + "unrelated activity (other databases, autovacuum) keeps that position moving, so "
            + "strict equality would be racy",
        sentByServer, greaterThanOrEqualTo(lastReceivedLSN)
    );
  }

  /**
   * Test fail on PG version 9.4.5 because postgresql have bug.
   */
  @Test
  @EnabledForServerVersionRange(gte = "9.4.8")
  void receivedLSNDependentOnProcessMessage() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 1);
    LogSequenceNumber firstLSN = stream.getLastReceiveLSN();

    receiveMessageWithoutBlock(stream, 1);
    LogSequenceNumber secondLSN = stream.getLastReceiveLSN();

    assertThat("After receive each new message current LSN updates in stream",
        firstLSN, not(equalTo(secondLSN))
    );
  }

  @Test
  void lastReceiveLSNCorrectOnView() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 2);
    LogSequenceNumber lastReceivedLSN = stream.getLastReceiveLSN();
    stream.forceUpdateStatus();

    assertThat(
        "Replication stream by execute forceUpdateStatus should send to view actual received position "
            + "that allow monitoring lag",
        lastReceivedLSN, equalTo(getLSNFromView(writeColumnName(), lastReceivedLSN))
    );
  }

  @Test
  void writeLocationCanBeLessThanSendLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 2);
    LogSequenceNumber lastReceivedLSN = stream.getLastReceiveLSN();
    stream.forceUpdateStatus();

    LogSequenceNumber writeLocation = getLSNFromView(writeColumnName(), lastReceivedLSN);
    LogSequenceNumber sentLocation = getLSNFromView(sentColumnName());

    assertThat(
        "In view pg_stat_replication column write_location define which position consume client "
            + "but sent_location define which position was sent to client, so in current test we have 1 pending message, "
            + "so write and sent can't be equals",
        writeLocation, not(equalTo(sentLocation))
    );
  }

  @Test
  void flushLocationEqualToSetLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 1);

    LogSequenceNumber flushLSN = stream.getLastReceiveLSN();
    stream.setFlushedLSN(flushLSN);

    //consume another messages
    receiveMessageWithoutBlock(stream, 2);

    stream.forceUpdateStatus();

    LogSequenceNumber result = getLSNFromView(flushColumnName(), flushLSN);

    assertThat("Flush LSN use for define which wal can be recycled and it parameter should be "
            + "specify manually on replication stream, because only client "
            + "of replication stream now which wal not necessary. We wait that it status correct "
            + "send to backend and available via view, because if status will "
            + "not send it lead to problem when WALs never recycled",
        result, equalTo(flushLSN)
    );
  }

  @Test
  void flushLocationDoNotChangeDuringReceiveMessage() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 1);
    final LogSequenceNumber flushLSN = stream.getLastReceiveLSN();
    stream.setFlushedLSN(flushLSN);
    receiveMessageWithoutBlock(stream, 2);

    assertThat(
        "Flush LSN it parameter that specify manually on stream and they can not automatically "
            + "change during receive another messages, "
            + "because auto update can lead to problem when WAL recycled on postgres "
            + "because we send feedback that current position successfully flush, but in real they not flush yet",
        stream.getLastFlushedLSN(), equalTo(flushLSN)
    );
  }

  @Test
  void applyLocationEqualToSetLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 1);
    final LogSequenceNumber applyLSN = stream.getLastReceiveLSN();

    stream.setAppliedLSN(applyLSN);
    stream.setFlushedLSN(applyLSN);

    receiveMessageWithoutBlock(stream, 2);
    stream.forceUpdateStatus();

    LogSequenceNumber result = getLSNFromView(replayColumnName(), applyLSN);

    assertThat(
        "During receive message from replication stream all feedback parameter "
            + "that we set to stream should be sent to backend"
            + "because it allow monitoring replication status and also recycle old WALs",
        result, equalTo(applyLSN)
    );
  }

  /**
   * Test fail on PG version 9.4.5 because postgresql have bug.
   */
  @Test
  @EnabledForServerVersionRange(gte = "9.4.8")
  void applyLocationDoNotDependOnFlushLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 1);
    LogSequenceNumber appliedLSN = stream.getLastReceiveLSN();
    stream.setAppliedLSN(appliedLSN);
    stream.setFlushedLSN(appliedLSN);

    receiveMessageWithoutBlock(stream, 1);
    LogSequenceNumber flushedLSN = stream.getLastReceiveLSN();
    stream.setFlushedLSN(flushedLSN);

    receiveMessageWithoutBlock(stream, 1);
    stream.forceUpdateStatus();

    LogSequenceNumber flushed = getLSNFromView(flushColumnName(), flushedLSN);
    LogSequenceNumber applied = getLSNFromView(replayColumnName(), appliedLSN);

    assertThat(
        "Last applied LSN and last flushed LSN it two not depends parameters and they can be not equal between",
        applied, not(equalTo(flushed))
    );
  }

  @Test
  void applyLocationDoNotChangeDuringReceiveMessage() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 1);
    final LogSequenceNumber applyLSN = stream.getLastReceiveLSN();
    stream.setAppliedLSN(applyLSN);
    receiveMessageWithoutBlock(stream, 2);

    assertThat(
        "Apply LSN it parameter that specify manually on stream and they can not automatically "
            + "change during receive another messages, "
            + "because auto update can lead to problem when WAL recycled on postgres "
            + "because we send feedback that current position successfully flush, but in real they not flush yet",
        stream.getLastAppliedLSN(), equalTo(applyLSN)
    );
  }

  @Test
  void statusCanBeSentToBackendAsynchronously() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    final int intervalTime = 100;
    final TimeUnit timeFormat = TimeUnit.MILLISECONDS;

    LogSequenceNumber startLSN = getCurrentLSN();

    insertPreviousChanges(sqlConnection);

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .withStatusInterval(intervalTime, timeFormat)
            .start();

    receiveMessageWithoutBlock(stream, 3);

    LogSequenceNumber waitLSN = stream.getLastReceiveLSN();

    stream.setAppliedLSN(waitLSN);
    stream.setFlushedLSN(waitLSN);

    timeFormat.sleep(intervalTime + 1);

    //get pending message and trigger update status by timeout
    stream.readPending();

    LogSequenceNumber flushLSN = getLSNFromView(flushColumnName(), waitLSN);

    assertThat("Status can be sent to backend by some time interval, "
            + "by default it parameter equals to 10 second, but in current test we change it on few millisecond "
            + "and wait that set status on stream will be auto send to backend",
        flushLSN, equalTo(waitLSN)
    );
  }

  @Test
  void startHandshakeUsesSocketTimeoutNotStatusInterval() throws Exception {
    TimeoutRecordingSocketFactory.Recording recording = TimeoutRecordingSocketFactory.register();
    try {
      Connection conn = TestUtil.openReplicationConnection(props -> {
        PGProperty.SOCKET_TIMEOUT.set(props, SOCKET_TIMEOUT_SECONDS);
        PGProperty.SOCKET_FACTORY.set(props, TimeoutRecordingSocketFactory.class.getName());
        PGProperty.SOCKET_FACTORY_ARG.set(props, recording.key());
      });
      try {
        LogSequenceNumber startLSN = getCurrentLSN();
        insertPreviousChanges(sqlConnection);

        recording.reset();
        PGReplicationStream stream =
            ((PGConnection) conn)
                .getReplicationAPI()
                .replicationStream()
                .logical()
                .withSlotName(SLOT_NAME)
                .withStartPosition(startLSN)
                .withStatusInterval(1, TimeUnit.MILLISECONDS)
                .start();
        // The reads that follow run under the status interval, so take the reading before them
        int handshakeTimeout = recording.minSoTimeoutOnRead();
        stream.close();

        assertThat("The status interval is the wake-up period of the streaming reads, and shortening "
                + "the socket timeout to it before START_REPLICATION leaves the handshake with a "
                + "millisecond to complete in. A server that answers any slower fails start() with "
                + "\"Database connection failed when starting copy\".",
            handshakeTimeout, equalTo(SOCKET_TIMEOUT_SECONDS * 1000)
        );
      } finally {
        conn.close();
      }
    } finally {
      TimeoutRecordingSocketFactory.unregister(recording);
    }
  }

  @Test
  void streamDoesNotChangeTheConnectionSocketTimeout() throws Exception {
    Connection conn = TestUtil.openReplicationConnection(props -> {
      PGProperty.SOCKET_TIMEOUT.set(props, SOCKET_TIMEOUT_SECONDS);
    });
    try {
      LogSequenceNumber startLSN = getCurrentLSN();
      insertPreviousChanges(sqlConnection);

      PGReplicationStream stream = startStream(conn, startLSN);
      assertThat("The stream waits for a message to start with a wake-up of its own, so the "
              + "connection keeps the socket timeout it was opened with rather than the status "
              + "interval",
          conn.getNetworkTimeout(), equalTo(SOCKET_TIMEOUT_SECONDS * 1000)
      );

      stream.close();
      assertThat("The connection still has its own socket timeout once the stream is over",
          conn.getNetworkTimeout(), equalTo(SOCKET_TIMEOUT_SECONDS * 1000)
      );
    } finally {
      conn.close();
    }
  }

  @Test
  void secondStreamHandshakeUsesSocketTimeout() throws Exception {
    TimeoutRecordingSocketFactory.Recording recording = TimeoutRecordingSocketFactory.register();
    try {
      Connection conn = TestUtil.openReplicationConnection(props -> {
        PGProperty.SOCKET_TIMEOUT.set(props, SOCKET_TIMEOUT_SECONDS);
        PGProperty.SOCKET_FACTORY.set(props, TimeoutRecordingSocketFactory.class.getName());
        PGProperty.SOCKET_FACTORY_ARG.set(props, recording.key());
      });
      try {
        insertPreviousChanges(sqlConnection);
        startStream(conn, getCurrentLSN()).close();

        insertPreviousChanges(sqlConnection);
        recording.reset();
        PGReplicationStream second = startStream(conn, getCurrentLSN());
        int handshakeTimeout = recording.minSoTimeoutOnRead();
        second.close();

        assertThat("A stream that ended leaves the connection on its own socketTimeout, so the "
                + "next START_REPLICATION is not answered under one status interval",
            handshakeTimeout, equalTo(SOCKET_TIMEOUT_SECONDS * 1000)
        );
      } finally {
        conn.close();
      }
    } finally {
      TimeoutRecordingSocketFactory.unregister(recording);
    }
  }

  @Test
  void blockingReadSendsStatusWithoutSocketTimeout() throws Exception {
    Connection conn = TestUtil.openReplicationConnection(props -> {
      PGProperty.SOCKET_TIMEOUT.set(props, 0);
      // wal_sender_timeout=0 stops the server from asking for a status update on its own, so the
      // only thing that can report the flushed LSN is the stream's own wake-up
      PGProperty.OPTIONS.set(props, "-c synchronous_commit=on -c wal_sender_timeout=0");
    });
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      LogSequenceNumber startLSN = getCurrentLSN();
      insertPreviousChanges(sqlConnection);
      PGReplicationStream stream = startStream(conn, startLSN);
      drainUntilQuiet(stream);
      LogSequenceNumber waitLSN = stream.getLastReceiveLSN();
      stream.setAppliedLSN(waitLSN);
      stream.setFlushedLSN(waitLSN);

      // Watch the server while the read below is blocked, then release the read with an insert
      Future<LogSequenceNumber> flushed = executor.submit(() -> {
        LogSequenceNumber seen = getLSNFromView(flushColumnName(), waitLSN);
        insertPreviousChanges(sqlConnection);
        return seen;
      });
      stream.read();
      LogSequenceNumber flushLSN = flushed.get(10, TimeUnit.SECONDS);
      stream.close();

      assertThat("A blocking read wakes up once per status interval to report the flushed LSN, and "
              + "the socket read timeout is what wakes it. A connection opened without "
              + "socketTimeout does not ask the driver to report read timeouts, so the wake-up was "
              + "swallowed and retried, and the server learned the flushed LSN only once it asked "
              + "for a status update itself",
          flushLSN, equalTo(waitLSN)
      );
    } finally {
      executor.shutdownNow();
      conn.close();
    }
  }

  @Test
  void aStalledMessageDoesNotCostTheStreamItsBoundary() throws Exception {
    try (MessageStallProxyServer proxy =
             new MessageStallProxyServer(TestUtil.getServer(), TestUtil.getPort())) {
      ExecutorService executor = Executors.newSingleThreadExecutor();
      Connection conn = TestUtil.openReplicationConnection(props -> {
        TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
        TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
            String.valueOf(proxy.getServerPort()));
        // The proxy reads the v3 framing to find its way into a message, so it needs plain traffic
        PGProperty.SSL_MODE.set(props, "disable");
        PGProperty.GSS_ENC_MODE.set(props, "disable");
        PGProperty.SOCKET_TIMEOUT.set(props, SOCKET_TIMEOUT_SECONDS);
      });
      try {
        LogSequenceNumber startLSN = getCurrentLSN();
        insertPreviousChanges(sqlConnection);
        PGReplicationStream stream = startStream(conn, startLSN);
        drainUntilQuiet(stream);

        // Hold the next message open for several status intervals, so the stream's wake-up would
        // run out while the driver sits between the type byte and the rest of that message
        proxy.stallInsideNextMessage(STATUS_INTERVAL_MS * 5L);
        // The insert waits for the read below to block: a message that arrives before the driver
        // is in the read lands in the socket buffer, and the stall costs it nothing
        Future<?> insert = executor.submit(() -> {
          TimeUnit.MILLISECONDS.sleep(STATUS_INTERVAL_MS * 2L);
          try (Statement st = sqlConnection.createStatement()) {
            st.execute("insert into test_logic_table(name) values('after the stall')");
          }
          return null;
        });

        StringBuilder received = new StringBuilder();
        for (int i = 0; i < 3; i++) {
          ByteBuffer message = stream.read();
          if (message == null) {
            break;
          }
          received.append(toString(message));
        }
        insert.get(10, TimeUnit.SECONDS);
        stream.close();

        assertThat("The wake-up belongs between messages. Taken partway through one it drops the "
                + "bytes already read, and the next read takes the middle of that message for the "
                + "start of the next one",
            received.toString(),
            equalTo("BEGIN"
                + "table public.test_logic_table: INSERT: pk[integer]:2 "
                + "name[character varying]:'after the stall'"
                + "COMMIT")
        );
      } finally {
        executor.shutdownNow();
        conn.close();
      }
    }
  }

  @Test
  void theWakeUpLeavesATimeoutTheDriverWasNeverToldAbout() throws Exception {
    TimeoutRecordingSocketFactory.Recording recording = TimeoutRecordingSocketFactory.register();
    // A socket the driver did not time out itself: socketTimeout stays at its default, so
    // ConnectionFactoryImpl never calls setNetworkTimeout and only the socket knows
    recording.initialSoTimeout(FACTORY_SO_TIMEOUT_MS);
    try {
      Connection conn = TestUtil.openReplicationConnection(props -> {
        PGProperty.SOCKET_TIMEOUT.set(props, 0);
        PGProperty.SOCKET_FACTORY.set(props, TimeoutRecordingSocketFactory.class.getName());
        PGProperty.SOCKET_FACTORY_ARG.set(props, recording.key());
        // An SSL or GSS upgrade swaps the socket for one built from the properties, which would
        // put the timeout back to what the driver knows and hide what this test is after
        PGProperty.SSL_MODE.set(props, "disable");
        PGProperty.GSS_ENC_MODE.set(props, "disable");
      });
      ExecutorService executor = Executors.newSingleThreadExecutor();
      try {
        LogSequenceNumber startLSN = getCurrentLSN();
        insertPreviousChanges(sqlConnection);
        PGReplicationStream stream = startStream(conn, startLSN);
        drainUntilQuiet(stream);

        // Block long enough for the wake-up to fire and put the timeout back
        Future<?> insert = executor.submit(() -> {
          TimeUnit.MILLISECONDS.sleep(STATUS_INTERVAL_MS * 3L);
          try (Statement st = sqlConnection.createStatement()) {
            st.execute("insert into test_logic_table(name) values('after the wake-up')");
          }
          return null;
        });
        stream.read();
        insert.get(10, TimeUnit.SECONDS);
        stream.close();

        assertThat("The wake-up has to put back the timeout the socket had, not one it remembered "
                + "from a setNetworkTimeout that never happened",
            conn.getNetworkTimeout(), equalTo(FACTORY_SO_TIMEOUT_MS)
        );
      } finally {
        executor.shutdownNow();
        conn.close();
      }
    } finally {
      TimeoutRecordingSocketFactory.unregister(recording);
    }
  }

  /**
   * Reads until nothing has arrived for a while, so that a blocking read has to wait for new data
   * rather than return what the slot still held. A message count is not a usable stopping
   * condition: an empty transaction decodes to a BEGIN and a COMMIT of its own.
   */
  private static void drainUntilQuiet(PGReplicationStream stream) throws Exception {
    long quietFor = TimeUnit.MILLISECONDS.toNanos(300);
    long deadline = System.nanoTime() + quietFor;
    while (System.nanoTime() < deadline) {
      if (stream.readPending() == null) {
        TimeUnit.MILLISECONDS.sleep(5);
      } else {
        deadline = System.nanoTime() + quietFor;
      }
    }
  }

  private static PGReplicationStream startStream(Connection conn, LogSequenceNumber startLSN)
      throws SQLException {
    return ((PGConnection) conn)
        .getReplicationAPI()
        .replicationStream()
        .logical()
        .withSlotName(SLOT_NAME)
        .withStartPosition(startLSN)
        .withSlotOption("include-xids", false)
        .withSlotOption("skip-empty-xacts", true)
        .withStatusInterval(STATUS_INTERVAL_MS, TimeUnit.MILLISECONDS)
        .start();
  }

  private static void insertPreviousChanges(Connection sqlConnection) throws SQLException {
    try (Statement st = sqlConnection.createStatement()) {
      st.execute("insert into test_logic_table(name) values('previous changes')");
    }
  }

  @Test
  void keepAliveServerLSNCanBeUsedToAdvanceFlushLSN() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .withStatusInterval(1, TimeUnit.SECONDS)
            .start();

    // create replication changes and poll for messages
    insertPreviousChanges(sqlConnection);

    receiveMessageWithoutBlock(stream, 3);

    // client confirms flush of these changes. At this point we're in sync with server
    LogSequenceNumber confirmedClientFlushLSN = stream.getLastReceiveLSN();
    stream.setFlushedLSN(confirmedClientFlushLSN);
    stream.forceUpdateStatus();

    // now insert something into other DB (without replication) to generate WAL
    insertPreviousChanges(secondSqlConnection);

    long start = System.nanoTime();
    long maxWait = TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() - start < maxWait) {
      stream.readPending();
      if (stream.getLastReceiveLSN().compareTo(confirmedClientFlushLSN) > 0) {
        break;
      }
      TimeUnit.MILLISECONDS.sleep(100);
    }

    LogSequenceNumber lastFlushedLSN = stream.getLastFlushedLSN();
    LogSequenceNumber lastReceivedLSN = stream.getLastReceiveLSN();

    assertThat("Activity in other database will generate WAL but no XLogData "
            + " messages. Received LSN will begin to advance beyond of confirmed flushLSN",
        confirmedClientFlushLSN, not(equalTo(lastReceivedLSN))
    );

    assertThat("When all XLogData messages have been processed, we can confirm "
            + " flush of Server LSNs in the KeepAlive messages",
        lastFlushedLSN, equalTo(lastReceivedLSN)
    );
  }

  private String sentColumnName() throws Exception {
    return ((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "sent_lsn" : "sent_location";
  }

  private String writeColumnName() throws Exception {
    return ((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "write_lsn" : "write_location";
  }

  private String flushColumnName() throws Exception {
    return ((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "flush_lsn" : "flush_location";
  }

  private String replayColumnName() throws Exception {
    return ((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "replay_lsn" : "replay_location";
  }

  private static List<String> receiveMessageWithoutBlock(PGReplicationStream stream, int count)
      throws Exception {
    List<String> result = new ArrayList<>(3);
    for (int index = 0; index < count; index++) {
      ByteBuffer message;
      do {
        message = stream.readPending();

        if (message == null) {
          TimeUnit.MILLISECONDS.sleep(2);
        }
      } while (message == null);

      result.add(toString(message));
    }

    return result;
  }

  private static String toString(ByteBuffer buffer) {
    int offset = buffer.arrayOffset();
    byte[] source = buffer.array();
    int length = source.length - offset;

    return new String(source, offset, length);
  }

  /**
   * Reads an LSN column from pg_stat_replication, polling until {@code accept} is satisfied
   * (or a timeout expires).  Polling is necessary because {@code forceUpdateStatus()} only
   * flushes data to the TCP socket; the server needs a short time to process the standby
   * status update and reflect it in pg_stat_replication.
   *
   * @param accept predicate the column value must satisfy to stop polling early
   * @return the value that satisfied {@code accept}; on timeout, the last value seen, or null
   *         if the column never produced one
   */
  private LogSequenceNumber pollLSNFromView(String columnName,
      Predicate<LogSequenceNumber> accept) throws Exception {
    long start = System.nanoTime();
    long timeout = TimeUnit.SECONDS.toNanos(2);

    LogSequenceNumber last = null;
    while (System.nanoTime() - start < timeout) {
      LogSequenceNumber current = readLSNFromView(columnName);
      if (current != null) {
        last = current;
        if (accept.test(current)) {
          return current;
        }
      }
      TimeUnit.MILLISECONDS.sleep(10L);
    }
    return last;
  }

  private LogSequenceNumber readLSNFromView(String columnName) throws SQLException {
    try (
        PreparedStatement st = sqlConnection.prepareStatement(
            "select r.* from pg_stat_replication r"
                + " join pg_replication_slots s on r.pid = s.active_pid"
                + " where s.slot_name = ?")
    ) {
      st.setString(1, SLOT_NAME);
      try (ResultSet rs = st.executeQuery()) {
        String result = rs.next() ? rs.getString(columnName) : null;
        return result != null && !result.isEmpty() ? LogSequenceNumber.valueOf(result) : null;
      }
    }
  }

  /**
   * Polls an LSN column until it equals {@code expected}, or returns the last value seen on
   * timeout. Use when the column is expected to settle on an exact value the client set.
   *
   * @param expected if non-null, poll until the column equals this value; if null, return
   *                 the first non-null value seen (or null on timeout)
   */
  private LogSequenceNumber getLSNFromView(String columnName,
      LogSequenceNumber expected) throws Exception {
    return pollLSNFromView(columnName, lsn -> expected == null || lsn.equals(expected));
  }

  /**
   * Polls an LSN column until it reaches at least {@code atLeast}, or returns the last value
   * seen on timeout. Use for columns the server may advance past the client's position, such
   * as sent_lsn driven by keepalive messages.
   */
  private LogSequenceNumber getLSNFromViewAtLeast(String columnName,
      LogSequenceNumber atLeast) throws Exception {
    LogSequenceNumber last = pollLSNFromView(columnName, lsn -> lsn.compareTo(atLeast) >= 0);
    assertThat("pg_stat_replication has no row for slot " + SLOT_NAME + " within the poll timeout",
        last, notNullValue());
    return last;
  }

  private LogSequenceNumber getLSNFromView(String columnName) throws Exception {
    return getLSNFromView(columnName, null);
  }

  private LogSequenceNumber getCurrentLSN() throws SQLException {
    try (Statement st = sqlConnection.createStatement();
         ResultSet rs = st.executeQuery("select "
             + (((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
             ? "pg_current_wal_lsn()" : "pg_current_xlog_location()"))
    ) {
      if (rs.next()) {
        String lsn = rs.getString(1);
        return LogSequenceNumber.valueOf(lsn);
      } else {
        return LogSequenceNumber.INVALID_LSN;
      }
    }
  }
}
