package org.postgresql.replication;


import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeThat;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;
import org.postgresql.util.PSQLException;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runners.model.TestTimedOutException;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

@HaveMinimalServerVersion("9.4")
public class LogicalReplicationTest {
  private static final String SLOT_NAME = "pgjdbc_logical_replication_slot";

  @Rule
  public ServerVersionRule versionRule = new ServerVersionRule();
  @Rule
  public ExpectedException exception = ExpectedException.none();

  private Connection replConnection;
  private Connection sqlConnection;

  @Before
  public void setUp() throws Exception {
    sqlConnection = TestUtil.openDB();
    //DriverManager.setLogWriter(new PrintWriter(System.out));
    replConnection = openReplicationConnection();
    TestUtil.createTable(sqlConnection, "test_logic_table",
        "pk serial primary key, name varchar(100)");
    Statement st = sqlConnection.createStatement();
    st.execute(
        "SELECT * FROM pg_create_logical_replication_slot('" + SLOT_NAME + "', 'test_decoding')");
    st.close();
  }

  @After
  public void tearDown() throws Exception {
    replConnection.close();
    TestUtil.dropTable(sqlConnection, "test_logic_table");

    Statement st = sqlConnection.createStatement();
    st.execute("select pg_drop_replication_slot('" + SLOT_NAME + "')");
    st.close();
    sqlConnection.close();
  }

  @Test(timeout = 1000)
  public void testNotAvailableStartNotExistReplicationSlot() throws Exception {
    exception.expect(PSQLException.class);
    exception.expectMessage(CoreMatchers.containsString("does not exist"));
    exception.reportMissingExceptionWithMessage(
        "For logical decoding replication slot name it required parameter "
            + "that should be create on server before start replication"
    );

    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    PGReplicationStream stream =
        pgConnection
            .replicationStream()
            .logical()
            .withSlotName("notExistSlotName")
            .withStartPosition(lsn)
            .start();
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
   * <p>Bug in postgreSQL that should be fixed in 9.7 version after code review patch <a
   * href="http://www.postgresql.org/message-id/CAFgjRd3hdYOa33m69TbeOfNNer2BZbwa8FFjt2V5VFzTBvUU3w@mail.gmail.com">
   * Stopping logical replication protocol</a>.
   *
   * <p>If you try to run it test on version before 9.7 they fail with time out, because postgresql
   * wait new changes and until waiting messages from client ignores.
   */
  @Test(timeout = 1000)
  @HaveMinimalServerVersion("9.7")
  public void testAfterCloseReplicationStreamDBSlotStatusNotActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    PGReplicationStream stream =
        pgConnection
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
    assertThat(
        "Execute close method on Connection should lead to stop replication as fast as possible, "
            + "as result we wait that on view pg_replication_slots status for slot will change to no active",
        isActive, equalTo(false)
    );
  }

  @Test(timeout = 10000)
  public void testDuringSendBigTransactionConnectionCloseSlotStatusNotActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table\n"
        + "  select id, md5(random()::text) as name from generate_series(1, 200000) as id;");
    st.close();

    PGReplicationStream stream =
        pgConnection
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
    assertThat(
        "Execute close method on Connection should lead to stop replication as fast as possible, "
            + "as result we wait that on view pg_replication_slots status for slot will change to no active",
        isActive, equalTo(false)
    );
  }

  @Test(timeout = 60000)
  public void testDuringSendBigTransactionReplicationStreamCloseNotActive() throws Exception {
    PGConnection pgConnection = (PGConnection) replConnection;

    LogSequenceNumber lsn = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into test_logic_table\n"
        + "  select id, md5(random()::text) as name from generate_series(1, 200000) as id;");
    st.close();

    PGReplicationStream stream =
        pgConnection
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
    replConnection = openReplicationConnection();
    pgConnection = (PGConnection) replConnection;

    stream =
        pgConnection
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

  @Test(timeout = 30000 /* backend keep alive 10s * 3 */)
  public void testAvoidTimeoutDisconnectWithDefaultStatusInterval() throws Exception {
    exception.expect(TestTimedOutException.class);
    exception.expectMessage(CoreMatchers.containsString("test timed out after"));

    PGConnection pgConnection = (PGConnection) replConnection;

    PGReplicationStream stream =
        pgConnection
            .replicationStream()
            .logical()
            .withSlotName(SLOT_NAME)
            .withStartPosition(getCurrentLSN())
            .start();

    while (!Thread.interrupted()) {
      ByteBuffer buffer = stream.read();
      System.out.println("Received: " + toString(buffer));
    }
  }

  private boolean isActiveOnView() throws SQLException {
    boolean result = false;
    Statement st = sqlConnection.createStatement();
    ResultSet
        rs =
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

  private String toString(ByteBuffer buffer) {
    int offset = buffer.arrayOffset();
    byte[] source = buffer.array();
    int length = source.length - offset;

    return new String(source, offset, length);
  }

  private LogSequenceNumber getCurrentLSN() throws SQLException {
    Statement st = sqlConnection.createStatement();
    ResultSet rs = null;
    try {
      rs = st.executeQuery("select pg_current_xlog_location()");

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

  private Connection openReplicationConnection() throws Exception {
    Properties properties = new Properties();
    PGProperty.ASSUME_MIN_SERVER_VERSION.set(properties, "9.4");
    PGProperty.REPLICATION.set(properties, "database");
    return TestUtil.openDB(properties);
  }
}
