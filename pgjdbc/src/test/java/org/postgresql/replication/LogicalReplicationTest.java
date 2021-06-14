/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeThat;

import org.postgresql.PGConnection;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.Replication;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.ExpectedException;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Category(Replication.class)
@HaveMinimalServerVersion("9.4")
public class LogicalReplicationTest {
  private static final String SLOT_NAME = "pgjdbc_logical_replication_slot";

  @Rule
  public ServerVersionRule versionRule = new ServerVersionRule();
  @Rule
  public ExpectedException exception = ExpectedException.none();

  private Connection replConnection;
  private Connection sqlConnection;

  private static String toString(ByteBuffer buffer) {
    int offset = buffer.arrayOffset();
    byte[] source = buffer.array();
    int length = source.length - offset;

    return new String(source, offset, length);
  }

  @Before
  public void setUp() throws Exception {
    sqlConnection = TestUtil.openPrivilegedDB();
    //DriverManager.setLogWriter(new PrintWriter(System.out));
    replConnection = TestUtil.openReplicationConnection();
    TestUtil.createTable(sqlConnection, "test_logic_table",
        "pk serial primary key, name varchar(100)");

    TestUtil.recreateLogicalReplicationSlot(sqlConnection, SLOT_NAME, "test_decoding");
  }

  @After
  public void tearDown() throws Exception {
    replConnection.close();
    TestUtil.dropTable(sqlConnection, "test_logic_table");
    TestUtil.dropReplicationSlot(sqlConnection, SLOT_NAME);
    sqlConnection.close();
  }

  @Test(timeout = 1000)
  public void testNotAvailableStartNotExistReplicationSlot() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    try {
      PGReplicationStream stream =
          pgConnection
              .getReplicationAPI()
              .replicationStream()
              .logical()
              .withSlotName("notExistSlotName")
              .withStartPosition(lsn)
              .start();

      fail("For logical decoding replication slot name it required parameter "
          + "that should be create on server before start replication");

    } catch (PSQLException e) {
      String state = e.getSQLState();

      assertThat("When replication slot doesn't exists, server can't start replication "
              + "and should throw exception about it",
          state, equalTo(PSQLState.UNDEFINED_OBJECT.getState())
      );
    }
  }

  @Test(timeout = 1000)
  public void testReceiveChangesOccursBeforStartReplication() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('previous value')");
    st.close();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(lsn)
            .withSlotOption("include-xids", false)
            .start();

    String result = group(receiveMessage(stream, 3));

    String wait = group(
        Arrays.asList(
            "BEGIN",
            "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'previous value'",
            "COMMIT"
        )
    );

    assertThat("Logical replication can be start from some LSN position and all changes that "
            + "occurs between last server LSN and specified LSN position should be available to read "
            + "via stream in correct order",
        result, equalTo(wait)
    );
  }

  @Test(timeout = 1000)
  public void testReceiveChangesAfterStartReplication() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(lsn)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    List<String> result = new ArrayList<String>();

    Statement st = sqlConnection.createStatement();
    st.execute(
        "insert into test_logic_table(name) values('first message after start replication')");
    st.close();

    result.addAll(receiveMessage(stream, 3));

    st = sqlConnection.createStatement();
    st.execute(
        "insert into test_logic_table(name) values('second message after start replication')");
    st.close();

    result.addAll(receiveMessage(stream, 3));

    String groupedResult = group(result);

    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'first message after start replication'",
        "COMMIT",
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:2 name[character varying]:'second message after start replication'",
        "COMMIT"
    ));

    assertThat(
        "After starting replication, from stream should be available also new changes that occurs after start replication",
        groupedResult, equalTo(wait)
    );
  }

  @Test(timeout = 1000)
  public void testStartFromCurrentServerLSNWithoutSpecifyLSNExplicitly() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('last server message')");
    st.close();

    String result = group(receiveMessage(stream, 3));

    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'last server message'",
        "COMMIT"
    ));

    assertThat(
        "When start LSN position not specify explicitly, wal should be stream from actual server position",
        result, equalTo(wait));
  }

  @Test(timeout = 1000)
  public void testAfterStartStreamingDBSlotStatusActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    boolean isActive = isActiveOnView();

    assertThat(
        "After start streaming, database status should be update on view pg_replication_slots to active",
        isActive, equalTo(true)
    );
  }

  /**
   * <p>Bug in postgreSQL that should be fixed in 10 version after code review patch <a
   * href="http://www.postgresql.org/message-id/CAFgjRd3hdYOa33m69TbeOfNNer2BZbwa8FFjt2V5VFzTBvUU3w@mail.gmail.com">
   * Stopping logical replication protocol</a>.</p>
   *
   * <p>If you try to run it test on version before 10 they fail with time out, because postgresql
   * wait new changes and until waiting messages from client ignores.</p>
   */
  @Test(timeout = 1000)
  @HaveMinimalServerVersion("11.1")
  public void testAfterCloseReplicationStreamDBSlotStatusNotActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    boolean isActive = isActiveOnView();
    assumeThat(isActive, equalTo(true));

    stream.close();

    isActive = isActiveOnView();
    assertThat("Execute close method on PGREplicationStream should lead to stop replication, "
            + "as result we wait that on view pg_replication_slots status for slot will change to no active",
        isActive, equalTo(false)
    );
  }

  @Test(timeout = 1000)
  public void testAfterCloseConnectionDBSLotStatusNotActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(lsn)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    boolean isActive = isActiveOnView();
    assumeThat(isActive, equalTo(true));

    replConnection.close();

    isActive = isActiveOnView();
    //we doesn't wait replay from server about stop connection that why some delay exists on update view and should wait some time before check view
    if (isActive) {
      TimeUnit.MILLISECONDS.sleep(200L);
      isActive = isActiveOnView();
    }

    assertThat(
        "Execute close method on Connection should lead to stop replication as fast as possible, "
            + "as result we wait that on view pg_replication_slots status for slot will change to no active",
        isActive, equalTo(false)
    );
  }

  /**
   * <p>Bug in postgreSQL that should be fixed in 10 version after code review patch <a
   * href="http://www.postgresql.org/message-id/CAFgjRd3hdYOa33m69TbeOfNNer2BZbwa8FFjt2V5VFzTBvUU3w@mail.gmail.com">
   * Stopping logical replication protocol</a>.</p>
   *
   * <p>If you try to run it test on version before 10 they fail with time out, because postgresql
   * wait new changes and until waiting messages from client ignores.</p>
   */
  @Test(timeout = 10000)
  @HaveMinimalServerVersion("12.1")
  public void testDuringSendBigTransactionConnectionCloseSlotStatusNotActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table\n"
        + "  select id, md5(random()::text) as name from generate_series(1, 200000) as id;");
    st.close();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withStartPosition(lsn)
            .withSlotName(SLOT_NAME)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    //wait first message
    stream.read();

    replConnection.close();

    boolean isActive = isActiveOnView();

    /*
     * we don't wait for replay from server about stop connection that's why some
     * delay exists on update view and should wait some time before check view
     */
    if (isActive) {
      TimeUnit.SECONDS.sleep(2L);
      isActive = isActiveOnView();
    }

    assertThat(
        "Execute close method on Connection should lead to stop replication as fast as possible, "
            + "as result we wait that on view pg_replication_slots status for slot will change to no active",
        isActive, equalTo(false)
    );
  }

  /**
   * <p>Bug in postgreSQL that should be fixed in 10 version after code review patch <a
   * href="http://www.postgresql.org/message-id/CAFgjRd3hdYOa33m69TbeOfNNer2BZbwa8FFjt2V5VFzTBvUU3w@mail.gmail.com">
   * Stopping logical replication protocol</a>.</p>
   *
   * <p>If you try to run it test on version before 10 they fail with time out, because postgresql
   * wait new changes and until waiting messages from client ignores.</p>
   */
  @Test(timeout = 60000)
  @HaveMinimalServerVersion("11.1")
  public void testDuringSendBigTransactionReplicationStreamCloseNotActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table\n"
        + "  select id, md5(random()::text) as name from generate_series(1, 200000) as id;");
    st.close();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withStartPosition(lsn)
            .withSlotName(SLOT_NAME)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    //wait first message
    stream.read();

    stream.close();
    //after replay from server that replication stream stopped, view already should be updated
    boolean isActive = isActiveOnView();
    assertThat("Execute close method on PGREplicationStream should lead to stop replication, "
            + "as result we wait that on view pg_replication_slots status for slot will change to no active",
        isActive, equalTo(false)
    );
  }

  @Test(timeout = 5000)
  //todo fix, fail because backend for logical decoding not reply with CommandComplate & ReadyForQuery
  public void testRepeatWalPositionTwice() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('message to repeat')");
    st.close();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    List<String> result = new ArrayList<String>();
    result.addAll(receiveMessage(stream, 3));

    replConnection.close();
    waitStopReplicationSlot();

    replConnection = TestUtil.openReplicationConnection();
    pgConnection = (PGConnection) replConnection;

    stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    result.addAll(receiveMessage(stream, 3));

    String groupedResult = group(result);
    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'message to repeat'",
        "COMMIT",
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'message to repeat'",
        "COMMIT"
    ));

    assertThat("Logical replication stream after start streaming can be close and "
            + "reopen on previous LSN, that allow reply wal logs, if they was not recycled yet",
        groupedResult, equalTo(wait)
    );
  }

  @Test(timeout = 3000)
  public void testDoesNotHavePendingMessageWhenStartFromLastLSN() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(getCurrentLSN())
            .start();

    ByteBuffer result = stream.readPending();

    assertThat("Read pending message allow without lock on socket read message, "
            + "and if message absent return null. In current test we start replication from last LSN on server, "
            + "so changes absent on server and readPending message will always lead to null ByteBuffer",
        result, equalTo(null)
    );
  }

  @Test(timeout = 3000)
  public void testReadPreviousChangesWithoutBlock() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

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
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    String received = group(receiveMessageWithoutBlock(stream, 3));

    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'previous changes'",
        "COMMIT"
    ));

    assertThat(
        "Messages from stream can be read by readPending method for avoid long block on Socket, "
            + "in current test we wait that behavior will be same as for read message with block",
        received, equalTo(wait)
    );
  }

  @Test(timeout = 3000)
  public void testReadActualChangesWithoutBlock() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(getCurrentLSN())
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('actual changes')");
    st.close();

    String received = group(receiveMessageWithoutBlock(stream, 3));

    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'actual changes'",
        "COMMIT"
    ));

    assertThat(
        "Messages from stream can be read by readPending method for avoid long block on Socket, "
            + "in current test we wait that behavior will be same as for read message with block",
        received, equalTo(wait)
    );
  }

  @Test(timeout = 10000 /* default client keep alive 10s*/)
  public void testAvoidTimeoutDisconnectWithDefaultStatusInterval() throws Exception {
    final int statusInterval = getKeepAliveTimeout();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future future = null;
    boolean done;
    try {
      future =
          executor.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
              PGConnection pgConnection = (PGConnection) replConnection;

              PGReplicationStream stream =
                  pgConnection
                      .getReplicationAPI()
                      .replicationStream()
                      .logical()
                      .withSlotName(SLOT_NAME)
                      .withStartPosition(getCurrentLSN())
                      .withStatusInterval(Math.round(statusInterval / 3), TimeUnit.MILLISECONDS)
                      .start();

              while (!Thread.interrupted()) {
                stream.read();
              }

              return null;
            }
          });

      future.get(5, TimeUnit.SECONDS);
      done = future.isDone();
    } catch (TimeoutException timeout) {
      done = future.isDone();
    } finally {
      executor.shutdownNow();
    }

    assertThat(
        "ReplicationStream should periodically send keep alive message to postgresql to avoid disconnect from server",
        done, CoreMatchers.equalTo(false)
    );
  }

  @Test
  public void testRestartReplicationFromRestartSlotLSNWhenFeedbackAbsent() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('first tx changes')");
    st.close();

    st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('second tx change')");
    st.close();

    List<String> consumedData = new ArrayList<String>();
    consumedData.addAll(receiveMessageWithoutBlock(stream, 3));

    //emulate replication break
    replConnection.close();
    waitStopReplicationSlot();

    replConnection = TestUtil.openReplicationConnection();
    pgConnection = (PGConnection) replConnection;
    stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(LogSequenceNumber.INVALID_LSN) /* Invalid LSN indicate for start from restart lsn */
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    consumedData.addAll(receiveMessageWithoutBlock(stream, 3));
    String result = group(consumedData);

    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'first tx changes'",
        "COMMIT",
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'first tx changes'",
        "COMMIT"
    ));

    assertThat(
        "If was consume message via logical replication stream but wasn't send feedback about apply and flush "
            + "consumed LSN, if replication crash, server should restart from last success apllyed lsn, "
            + "in this case it lsn of start replication slot, so we should consume first 3 message twice",
        result, equalTo(wait)
    );
  }

  @Test
  public void testReplicationRestartFromLastFeedbackPosition() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('first tx changes')");
    st.close();

    st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('second tx change')");
    st.close();

    List<String> consumedData = new ArrayList<String>();
    consumedData.addAll(receiveMessageWithoutBlock(stream, 3));
    stream.setFlushedLSN(stream.getLastReceiveLSN());
    stream.setAppliedLSN(stream.getLastReceiveLSN());
    stream.forceUpdateStatus();

    //emulate replication break
    replConnection.close();
    waitStopReplicationSlot();

    replConnection = TestUtil.openReplicationConnection();
    pgConnection = (PGConnection) replConnection;
    stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(LogSequenceNumber.INVALID_LSN) /* Invalid LSN indicate for start from restart lsn */
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    consumedData.addAll(receiveMessageWithoutBlock(stream, 3));
    String result = group(consumedData);

    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'first tx changes'",
        "COMMIT",
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:2 name[character varying]:'second tx change'",
        "COMMIT"
    ));

    assertThat(
        "When we add feedback about applied lsn to replication stream(in this case it's force update status)"
            + "after restart consume changes via this slot should be started from last success lsn that "
            + "we send before via force status update, that why we wait consume both transaction without duplicates",
        result, equalTo(wait));
  }

  @Test
  public void testReplicationRestartFromLastFeedbackPositionParallelTransaction() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber startLSN = getCurrentLSN();

    PGReplicationStream stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(startLSN)
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    Connection tx1Connection = TestUtil.openPrivilegedDB();
    tx1Connection.setAutoCommit(false);

    Connection tx2Connection = TestUtil.openPrivilegedDB();
    tx2Connection.setAutoCommit(false);

    Statement stTx1 = tx1Connection.createStatement();
    Statement stTx2 = tx2Connection.createStatement();

    stTx1.execute("BEGIN");
    stTx2.execute("BEGIN");

    stTx1.execute("insert into test_logic_table(name) values('first tx changes')");
    stTx2.execute("insert into test_logic_table(name) values('second tx changes')");

    tx1Connection.commit();
    tx2Connection.commit();

    tx1Connection.close();
    tx2Connection.close();

    List<String> consumedData = new ArrayList<String>();
    consumedData.addAll(receiveMessageWithoutBlock(stream, 3));
    stream.setFlushedLSN(stream.getLastReceiveLSN());
    stream.setAppliedLSN(stream.getLastReceiveLSN());

    stream.forceUpdateStatus();

    //emulate replication break
    replConnection.close();
    waitStopReplicationSlot();

    replConnection = TestUtil.openReplicationConnection();
    pgConnection = (PGConnection) replConnection;
    stream =
        pgConnection
            .getReplicationAPI()
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(LogSequenceNumber.INVALID_LSN) /* Invalid LSN indicate for start from restart lsn */
            .withSlotOption("include-xids", false)
            .withSlotOption("skip-empty-xacts", true)
            .start();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table(name) values('third tx changes')");
    st.close();

    consumedData.addAll(receiveMessageWithoutBlock(stream, 3));
    String result = group(consumedData);

    String wait = group(Arrays.asList(
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:1 name[character varying]:'first tx changes'",
        "COMMIT",
        "BEGIN",
        "table public.test_logic_table: INSERT: pk[integer]:2 name[character varying]:'second tx changes'",
        "COMMIT"
    ));

    assertThat(
        "When we add feedback about applied lsn to replication stream(in this case it's force update status)"
            + "after restart consume changes via this slot should be started from last success lsn that "
            + "we send before via force status update, that why we wait consume both transaction without duplicates",
        result, equalTo(wait));
  }

  private void waitStopReplicationSlot() throws SQLException, InterruptedException {
    while (true) {
      PreparedStatement statement =
          sqlConnection.prepareStatement(
              "select 1 from pg_replication_slots where slot_name = ? and active = true"
          );
      statement.setString(1, SLOT_NAME);
      ResultSet rs = statement.executeQuery();
      boolean active = rs.next();
      rs.close();
      statement.close();

      if (!active) {
        return;
      }

      TimeUnit.MILLISECONDS.sleep(10);
    }
  }

  private int getKeepAliveTimeout() throws SQLException {
    Statement statement = sqlConnection.createStatement();
    ResultSet resultSet = statement.executeQuery(
        "select setting, unit from pg_settings where name = 'wal_sender_timeout'");
    int result = 0;
    if (resultSet.next()) {
      result = resultSet.getInt(1);
      String unit = resultSet.getString(2);
      if ("sec".equals(unit)) {
        result = (int) TimeUnit.SECONDS.toMillis(result);
      }
    }

    return result;
  }

  private boolean isActiveOnView() throws SQLException {
    boolean result = false;
    Statement st = sqlConnection.createStatement();
    ResultSet rs =
        st.executeQuery("select * from pg_replication_slots where slot_name = '" + SLOT_NAME + "'");
    if (rs.next()) {
      result = rs.getBoolean("active");
    }
    rs.close();
    st.close();
    return result;
  }

  private String group(List<String> messages) {
    StringBuilder builder = new StringBuilder();
    boolean isFirst = true;
    for (String str : messages) {
      if (isFirst) {
        isFirst = false;
      } else {
        builder.append("\n");
      }

      builder.append(str);
    }

    return builder.toString();
  }

  private List<String> receiveMessage(PGReplicationStream stream, int count) throws SQLException {
    List<String> result = new ArrayList<String>(count);
    for (int index = 0; index < count; index++) {
      result.add(toString(stream.read()));
    }

    return result;
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
