/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.replication;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyDual;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.ServerVersion;
import org.postgresql.test.Replication;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.rules.ServerVersionRule;
import org.postgresql.test.util.rules.annotation.HaveMinimalServerVersion;

import org.hamcrest.CoreMatchers;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

/**
 * CopyBothResponse use since 9.1 PostgreSQL version for replication protocol.
 */
@Category(Replication.class)
@HaveMinimalServerVersion("9.4")
public class CopyBothResponseTest {
  @Rule
  public ServerVersionRule versionRule = new ServerVersionRule();

  private Connection sqlConnection;
  private Connection replConnection;

  @BeforeClass
  public static void beforeClass() throws Exception {
    Connection con = TestUtil.openDB();
    TestUtil.createTable(con, "testreplication", "pk serial primary key, name varchar(100)");
    con.close();
  }

  @AfterClass
  public static void testAfterClass() throws Exception {
    Connection con = TestUtil.openDB();
    TestUtil.dropTable(con, "testreplication");
    con.close();
  }

  @Before
  public void setUp() throws Exception {
    sqlConnection = TestUtil.openDB();
    replConnection = TestUtil.openReplicationConnection();
    replConnection.setAutoCommit(true);
  }

  @After
  public void tearDown() throws Exception {
    sqlConnection.close();
    replConnection.close();
  }

  @Test
  public void testOpenConnectByReplicationProtocol() throws Exception {
    CopyManager cm = ((PGConnection) replConnection).getCopyAPI();

    LogSequenceNumber logSequenceNumber = getCurrentLSN();
    CopyDual copyDual = cm.copyDual(
        "START_REPLICATION " + logSequenceNumber.asString());
    try {
      assertThat(
          "Replication protocol work via copy protocol and initialize as CopyBothResponse, "
              + "we want that first initialize will work",
          copyDual, CoreMatchers.notNullValue()
      );
    } finally {
      copyDual.endCopy();
    }
  }

  @Test
  public void testReceiveKeepAliveMessage() throws Exception {
    CopyManager cm = ((PGConnection) replConnection).getCopyAPI();

    LogSequenceNumber logSequenceNumber = getCurrentLSN();
    CopyDual copyDual = cm.copyDual(
        "START_REPLICATION " + logSequenceNumber.asString());

    sendStandByUpdate(copyDual, logSequenceNumber, logSequenceNumber, logSequenceNumber, true);
    ByteBuffer buf = ByteBuffer.wrap(copyDual.readFromCopy());

    int code = buf.get();
    copyDual.endCopy();

    assertThat(
        "Streaming replication start with swap keep alive message, we want that first get packege will be keep alive",
        code, equalTo((int) 'k')
    );
  }

  @Test
  public void testKeedAliveContaintCorrectLSN() throws Exception {
    CopyManager cm = ((PGConnection) replConnection).getCopyAPI();

    LogSequenceNumber startLsn = getCurrentLSN();
    CopyDual copyDual =
        cm.copyDual("START_REPLICATION " + startLsn.asString());
    sendStandByUpdate(copyDual, startLsn, startLsn, startLsn, true);

    ByteBuffer buf = ByteBuffer.wrap(copyDual.readFromCopy());

    int code = buf.get();
    LogSequenceNumber lastLSN = LogSequenceNumber.valueOf(buf.getLong());
    copyDual.endCopy();

    assertThat(
        "Keep alive message contain last lsn on server, we want that before start replication "
            + "and get keep alive message not occurs wal modifications",
        lastLSN, CoreMatchers.equalTo(startLsn)
    );
  }

  @Test
  public void testReceiveXLogData() throws Exception {
    CopyManager cm = ((PGConnection) replConnection).getCopyAPI();

    LogSequenceNumber startLsn = getCurrentLSN();

    Statement st = sqlConnection.createStatement();
    st.execute("insert into testreplication(name) values('testing get changes')");
    st.close();

    CopyDual copyDual =
        cm.copyDual("START_REPLICATION " + startLsn.asString());
    sendStandByUpdate(copyDual, startLsn, startLsn, startLsn, false);

    ByteBuffer buf = ByteBuffer.wrap(copyDual.readFromCopy());

    char code = (char) buf.get();
    copyDual.endCopy();

    assertThat(
        "When replication starts via slot and specify LSN that lower than last LSN on server, "
            + "we should get all changes that occurs beetween two LSN",
        code, equalTo('w')
    );
  }

  private void sendStandByUpdate(CopyDual copyDual, LogSequenceNumber received,
      LogSequenceNumber flushed, LogSequenceNumber applied, boolean replyRequired)
      throws SQLException {
    ByteBuffer response = ByteBuffer.allocate(1 + 8 + 8 + 8 + 8 + 1);
    response.put((byte) 'r');
    response.putLong(received.asLong()); //received
    response.putLong(flushed.asLong()); //flushed
    response.putLong(applied.asLong()); //applied
    response.putLong(TimeUnit.MICROSECONDS.convert((System.currentTimeMillis() - 946674000000L),
        TimeUnit.MICROSECONDS));
    response.put(replyRequired ? (byte) 1 : (byte) 0); //reply soon as possible

    byte[] standbyUpdate = response.array();
    copyDual.writeToCopy(standbyUpdate, 0, standbyUpdate.length);
    copyDual.flushCopy();
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
