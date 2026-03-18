/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.TestUtil;
import org.postgresql.test.annotations.EnabledForServerVersionRange;
import org.postgresql.test.annotations.tags.Replication;

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
import java.util.concurrent.TimeUnit;

@Replication
@EnabledForServerVersionRange(gte = "9.4")
class LogicalReplicationStatusTest {
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

    LogSequenceNumber sentByServer = getLSNFromView(sentColumnName(), lastReceivedLSN);

    assertThat("When changes absent on server last receive by stream LSN "
            + "should be equal to last sent by server LSN",
        sentByServer, equalTo(lastReceivedLSN)
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
   * Reads an LSN column from pg_stat_replication, polling until a non-null value appears.
   * If {@code expected} is non-null, keeps polling until the column matches that value
   * (or a timeout expires).  This is necessary because {@code forceUpdateStatus()} only
   * flushes data to the TCP socket; the server needs a short time to process the standby
   * status update and reflect it in pg_stat_replication.
   *
   * @param expected if non-null, poll until the column equals this value; if null, return
   *                 the first non-null value seen (or null on timeout)
   */
  private LogSequenceNumber getLSNFromView(String columnName,
      LogSequenceNumber expected) throws Exception {
    long start = System.nanoTime();
    long timeout = TimeUnit.SECONDS.toNanos(2);

    LogSequenceNumber last = null;
    while (System.nanoTime() - start < timeout) {
      try (
          PreparedStatement st = sqlConnection.prepareStatement(
              "select r.* from pg_stat_replication r"
                  + " join pg_replication_slots s on r.pid = s.active_pid"
                  + " where s.slot_name = ?")
      ) {
        st.setString(1, SLOT_NAME);
        try (ResultSet rs = st.executeQuery()) {
          String result = null;
          if (rs.next()) {
            result = rs.getString(columnName);
          }
          if (result != null && !result.isEmpty()) {
            last = LogSequenceNumber.valueOf(result);
            if (expected == null || last.equals(expected)) {
              return last;
            }
          }
        }
      }
      TimeUnit.MILLISECONDS.sleep(10L);
    }
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
