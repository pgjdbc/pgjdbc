/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.Replication;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Category(Replication.class)
@HaveMinimalServerVersion("9.4")
public class LogicalReplicationStatusTest {
  private static final String SLOT_NAME = "pgjdbc_logical_replication_slot";

  @Rule
  public ServerVersionRule versionRule = new ServerVersionRule();

  private Connection replicationConnection;
  private Connection sqlConnection;

  @Before
  public void setUp() throws Exception {
    //statistic available only for privileged user
    sqlConnection = TestUtil.openPrivilegedDB();
    //DriverManager.setLogWriter(new PrintWriter(System.out));
    replicationConnection = TestUtil.openReplicationConnection();
    TestUtil.createTable(sqlConnection, "test_logic_table",
        "pk serial primary key, name varchar(100)");

    TestUtil.recreateLogicalReplicationSlot(sqlConnection, SLOT_NAME, "test_decoding");
  }

  @After
  public void tearDown() throws Exception {
    replicationConnection.close();
    TestUtil.dropTable(sqlConnection, "test_logic_table");
    TestUtil.dropReplicationSlot(sqlConnection, SLOT_NAME);
    sqlConnection.close();
  }

  @Test()
  public void testSentLocationEqualToLastReceiveLSN() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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

    LogSequenceNumber sentByServer = getSentLocationOnView();

    assertThat("When changes absent on server last receive by stream LSN "
            + "should be equal to last sent by server LSN",
        sentByServer, equalTo(lastReceivedLSN)
    );
  }

  /**
   * Test fail on PG version 9.4.5 because postgresql have bug.
   */
  @Test
  @HaveMinimalServerVersion("9.4.8")
  public void testReceivedLSNDependentOnProcessMessage() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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
  public void testLastReceiveLSNCorrectOnView() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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
        lastReceivedLSN, equalTo(getWriteLocationOnView())
    );
  }

  @Test
  public void testWriteLocationCanBeLessThanSendLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 2);
    stream.forceUpdateStatus();

    LogSequenceNumber writeLocation = getWriteLocationOnView();
    LogSequenceNumber sentLocation = getSentLocationOnView();

    assertThat(
        "In view pg_stat_replication column write_location define which position consume client "
            + "but sent_location define which position was sent to client, so in current test we have 1 pending message, "
            + "so write and sent can't be equals",
        writeLocation, not(equalTo(sentLocation))
    );
  }

  @Test
  public void testFlushLocationEqualToSetLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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

    LogSequenceNumber result = getFlushLocationOnView();

    assertThat("Flush LSN use for define which wal can be recycled and it parameter should be "
            + "specify manually on replication stream, because only client "
            + "of replication stream now which wal not necessary. We wait that it status correct "
            + "send to backend and available via view, because if status will "
            + "not send it lead to problem when WALs never recycled",
        result, equalTo(flushLSN)
    );
  }

  @Test
  public void testFlushLocationDoNotChangeDuringReceiveMessage() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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
  public void testApplyLocationEqualToSetLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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

    LogSequenceNumber result = getReplayLocationOnView();

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
  @HaveMinimalServerVersion("9.4.8")
  public void testApplyLocationDoNotDependOnFlushLocation() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .start();

    receiveMessageWithoutBlock(stream, 1);
    stream.setAppliedLSN(stream.getLastReceiveLSN());
    stream.setFlushedLSN(stream.getLastReceiveLSN());

    receiveMessageWithoutBlock(stream, 1);
    stream.setFlushedLSN(stream.getLastReceiveLSN());

    receiveMessageWithoutBlock(stream, 1);
    stream.forceUpdateStatus();

    LogSequenceNumber flushed = getFlushLocationOnView();
    LogSequenceNumber applied = getReplayLocationOnView();

    assertThat(
        "Last applied LSN and last flushed LSN it two not depends parameters and they can be not equal between",
        applied, not(equalTo(flushed))
    );
  }

  @Test
  public void testApplyLocationDoNotChangeDuringReceiveMessage() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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
  public void testStatusCanBeSentToBackendAsynchronously() throws Exception {
    PGConnection pgConnection = (PGConnection) replicationConnection;

    final int intervalTime = 100;
    final TimeUnit timeFormat = TimeUnit.MILLISECONDS;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous changes')");
    st.close();

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

    LogSequenceNumber flushLSN = getFlushLocationOnView();

    assertThat("Status can be sent to backend by some time interval, "
            + "by default it parameter equals to 10 second, but in current test we change it on few millisecond "
            + "and wait that set status on stream will be auto send to backend",
        flushLSN, equalTo(waitLSN)
    );
  }

  private LogSequenceNumber getSentLocationOnView() throws Exception {
    return getLSNFromView((((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "sent_lsn" : "sent_location"));
  }

  private LogSequenceNumber getWriteLocationOnView() throws Exception {
    return getLSNFromView((((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "write_lsn" : "write_location"));
  }

  private LogSequenceNumber getFlushLocationOnView() throws Exception {
    return getLSNFromView((((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "flush_lsn" : "flush_location"));
  }

  private LogSequenceNumber getReplayLocationOnView() throws Exception {
    return getLSNFromView((((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
        ? "replay_lsn" : "replay_location"));
  }

  private List<String> receiveMessageWithoutBlock(PGReplicationStream stream, int count)
      throws Exception {
    List<String> result = new ArrayList<String>(3);
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

  private String toString(ByteBuffer buffer) {
    int offset = buffer.arrayOffset();
    byte[] source = buffer.array();
    int length = source.length - offset;

    return new String(source, offset, length);
  }

  private LogSequenceNumber getLSNFromView(String columnName) throws Exception {
    int pid = ((PGConnection) replicationConnection).getBackendPID();

    int repeatCount = 0;
    while (true) {
      Statement st = sqlConnection.createStatement();
      ResultSet rs = null;
      try {
        rs = st.executeQuery("select * from pg_stat_replication where pid = " + pid);

        String result = null;
        if (rs.next()) {
          result = rs.getString(columnName);
        }

        if (result == null || result.isEmpty()) {
          //replication monitoring view updates with some delay, wait some time and try again
          TimeUnit.MILLISECONDS.sleep(100L);
          repeatCount++;
          if (repeatCount == 10) {
            return null;
          }
        } else {
          return LogSequenceNumber.valueOf(result);
        }
      } finally {
        if (rs != null) {
          rs.close();
        }
        st.close();
      }
    }
  }

  private LogSequenceNumber getCurrentLSN() throws SQLException {
    Statement st = sqlConnection.createStatement();
    ResultSet rs = null;
    try {
      rs = st.executeQuery("select "
          + (((BaseConnection) sqlConnection).haveMinimumServerVersion(ServerVersion.v10)
          ? "pg_current_wal_lsn()" : "pg_current_xlog_location()"));

      if (rs.next()) {
        String lsn = rs.getString(1);
        return LogSequenceNumber.valueOf(lsn);
      } else {
        return LogSequenceNumber.INVALID_LSN;
      }
    } finally {
      if (rs != null) {
        rs.close();
      }
      st.close();
    }
  }
}
