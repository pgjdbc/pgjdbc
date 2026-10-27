/*
 * Copyright (c) 2008, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGConnection;
import org.postgresql.PGProperty;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.postgresql.copy.CopyOut;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.copy.PGCopyOutputStream;
import org.postgresql.core.ServerVersion;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;
import org.postgresql.test.util.MessageStallProxyServer;
import org.postgresql.test.util.StrangeProxyServer;
import org.postgresql.util.ByteBufferByteStreamWriter;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author kato@iki.fi
 */
class CopyTest {
  private Connection con;
  private CopyManager copyAPI;
  private String copyParams;
  // 0's required to match DB output for numeric(5,2)
  private final String[] origData =
      {"First Row\t1\t1.10\n",
          "Second Row\t2\t-22.20\n",
          "\\N\t\\N\t\\N\n",
          "\t4\t444.40\n"};
  private final int dataRows = origData.length;

  private static byte[] getData(String[] origData) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(buf);
    for (String anOrigData : origData) {
      ps.print(anOrigData);
    }
    return buf.toByteArray();
  }

  @BeforeEach
  void setUp() throws Exception {
    con = TestUtil.openDB();

    TestUtil.createTempTable(con, "copytest", "stringvalue text, intvalue int, numvalue numeric(5,2)");

    copyAPI = ((PGConnection) con).getCopyAPI();
    if (TestUtil.haveMinimumServerVersion(con, ServerVersion.v9_0)) {
      copyParams = "(FORMAT CSV, HEADER false)";
    } else {
      copyParams = "CSV";
    }
  }

  @AfterEach
  void tearDown() throws Exception {
    TestUtil.closeDB(con);

    // one of the tests will render the existing connection broken,
    // so we need to drop the table on a fresh one.
    con = TestUtil.openDB();
    try {
      TestUtil.dropTable(con, "copytest");
    } finally {
      con.close();
    }
  }

  private int getCount() throws SQLException {
    Statement stmt = con.createStatement();
    ResultSet rs = stmt.executeQuery("SELECT count(*) FROM copytest");
    rs.next();
    int result = rs.getInt(1);
    rs.close();
    return result;
  }

  @Test
  void copyInByRow() throws SQLException {
    String sql = "COPY copytest FROM STDIN";
    CopyIn cp = copyAPI.copyIn(sql);
    for (String anOrigData : origData) {
      byte[] buf = anOrigData.getBytes();
      cp.writeToCopy(buf, 0, buf.length);
    }

    long count1 = cp.endCopy();
    long count2 = cp.getHandledRowCount();
    assertEquals(dataRows, count1);
    assertEquals(dataRows, count2);

    try {
      cp.cancelCopy();
    } catch (SQLException se) { // should fail with obsolete operation
      if (!PSQLState.OBJECT_NOT_IN_STATE.getState().equals(se.getSQLState())) {
        fail("should have thrown object not in state exception.");
      }
    }
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  @Test
  void copyInAsOutputStream() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    OutputStream os = new PGCopyOutputStream((PGConnection) con, sql, 1000);
    for (String anOrigData : origData) {
      byte[] buf = anOrigData.getBytes();
      os.write(buf);
    }
    os.close();
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  @Test
  void copyInAsOutputStreamClosesAfterEndCopy() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    PGCopyOutputStream os = new PGCopyOutputStream((PGConnection) con, sql, 1000);
    try {
      for (String anOrigData : origData) {
        byte[] buf = anOrigData.getBytes();
        os.write(buf);
      }
      os.endCopy();
    } finally {
      os.close();
    }
    assertFalse(os.isActive());
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  @Test
  void copyInAsOutputStreamFailsOnFlushAfterEndCopy() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    PGCopyOutputStream os = new PGCopyOutputStream((PGConnection) con, sql, 1000);
    try {
      for (String anOrigData : origData) {
        byte[] buf = anOrigData.getBytes();
        os.write(buf);
      }
      os.endCopy();
    } finally {
      os.close();
    }
    try {
      os.flush();
      fail("should have failed flushing an inactive copy stream.");
    } catch (IOException e) {
      // We expect "This copy stream is closed", however, the message is locale-dependent
      if (Locale.getDefault().getLanguage().equals(new Locale("en").getLanguage())
          && !e.toString().contains("This copy stream is closed.")) {
        fail("has failed not due to checkClosed(): " + e);
      }
    }
  }

  @Test
  void copyInFromInputStream() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    copyAPI.copyIn(sql, new ByteArrayInputStream(getData(origData)), 3);
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  @Test
  void copyInFromStreamFail() throws SQLException {
    String sql = "COPY copytest FROM STDIN";
    try {
      copyAPI.copyIn(sql, new InputStream() {
        @Override
        public int read() {
          throw new RuntimeException("COPYTEST");
        }
      }, 3);
    } catch (Exception e) {
      if (!e.toString().contains("COPYTEST")) {
        fail("should have failed trying to read from our bogus stream.");
      }
    }
    int rowCount = getCount();
    assertEquals(0, rowCount);
  }

  @Test
  void copyInFromReader() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    copyAPI.copyIn(sql, new StringReader(new String(getData(origData))), 3);
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  @Test
  void copyInFromByteStreamWriter() throws SQLException, IOException {
    String sql = "COPY copytest FROM STDIN";
    copyAPI.copyIn(sql, new ByteBufferByteStreamWriter(ByteBuffer.wrap(getData(origData))));
    int rowCount = getCount();
    assertEquals(dataRows, rowCount);
  }

  /**
   * Tests writing to a COPY ... FROM STDIN using both the standard OutputStream API
   * write(byte[]) and the driver specific write(ByteStreamWriter) API interleaved.
   */
  @Test
  void copyMultiApi() throws SQLException, IOException {
    TestUtil.execute(con, "CREATE TABLE pg_temp.copy_api_test (data text)");
    String sql = "COPY pg_temp.copy_api_test (data) FROM STDIN";
    PGCopyOutputStream out = new PGCopyOutputStream(copyAPI.copyIn(sql));
    try {
      out.write("a".getBytes());
      out.writeToCopy(new ByteBufferByteStreamWriter(ByteBuffer.wrap("b".getBytes())));
      out.write("c".getBytes());
      out.writeToCopy(new ByteBufferByteStreamWriter(ByteBuffer.wrap("d".getBytes())));
      out.write("\n".getBytes());
    } finally {
      out.close();
    }
    String data = TestUtil.queryForString(con, "SELECT data FROM pg_temp.copy_api_test");
    assertEquals("abcd", data, "The writes to the COPY should be in order");
  }

  @Test
  void skipping() {
    String sql = "COPY copytest FROM STDIN";
    String at = "init";
    int rowCount = -1;
    int skip = 0;
    int skipChar = 1;
    try {
      while (skipChar > 0) {
        at = "buffering";
        InputStream ins = new ByteArrayInputStream(getData(origData));
        at = "skipping";
        ins.skip(skip++);
        skipChar = ins.read();
        at = "copying";
        copyAPI.copyIn(sql, ins, 3);
        at = "using connection after writing copy";
        rowCount = getCount();
      }
    } catch (Exception e) {
      if (skipChar != '\t') {
        // error expected when field separator consumed
        fail("testSkipping at " + at + " round " + skip + ": " + e.toString());
      }
    }
    assertEquals(dataRows * (skip - 1), rowCount);
  }

  @Test
  void copyOutByRow() throws SQLException, IOException {
    copyInByRow(); // ensure we have some data.
    String sql = "COPY copytest TO STDOUT";
    CopyOut cp = copyAPI.copyOut(sql);
    int count = 0;
    byte[] buf;
    while ((buf = cp.readFromCopy()) != null) {
      count++;
    }
    assertFalse(cp.isActive());
    assertEquals(dataRows, count);

    long rowCount = cp.getHandledRowCount();

    assertEquals(dataRows, rowCount);

    assertEquals(dataRows, getCount());
  }

  @Test
  void copyOut() throws SQLException, IOException {
    copyInByRow(); // ensure we have some data.
    String sql = "COPY copytest TO STDOUT";
    ByteArrayOutputStream copydata = new ByteArrayOutputStream();
    copyAPI.copyOut(sql, copydata);
    assertEquals(dataRows, getCount());
    // deep comparison of data written and read
    byte[] copybytes = copydata.toByteArray();
    assertNotNull(copybytes);
    for (int i = 0, l = 0; i < origData.length; i++) {
      byte[] origBytes = origData[i].getBytes();
      assertTrue(copybytes.length >= l + origBytes.length, "Copy is shorter than original");
      for (int j = 0; j < origBytes.length; j++, l++) {
        assertEquals(origBytes[j], copybytes[l], "content changed at byte#" + j + ": " + origBytes[j] + copybytes[l]);
      }
    }
  }

  @Test
  void nonCopyOut() throws SQLException, IOException {
    String sql = "SELECT 1";
    try {
      copyAPI.copyOut(sql, new ByteArrayOutputStream());
      fail("Can't use a non-copy query.");
    } catch (SQLException sqle) {
    }
    // Ensure connection still works.
    assertEquals(0, getCount());
  }

  @Test
  void nonCopyIn() throws SQLException, IOException {
    String sql = "SELECT 1";
    try {
      copyAPI.copyIn(sql, new ByteArrayInputStream(new byte[0]));
      fail("Can't use a non-copy query.");
    } catch (SQLException sqle) {
    }
    // Ensure connection still works.
    assertEquals(0, getCount());
  }

  @Test
  void statementCopyIn() throws SQLException {
    Statement stmt = con.createStatement();
    try {
      stmt.execute("COPY copytest FROM STDIN");
      fail("Should have failed because copy doesn't work from a Statement.");
    } catch (SQLException sqle) {
    }
    stmt.close();

    assertEquals(0, getCount());
  }

  @Test
  void statementCopyOut() throws SQLException {
    copyInByRow(); // ensure we have some data.

    Statement stmt = con.createStatement();
    try {
      stmt.execute("COPY copytest TO STDOUT");
      fail("Should have failed because copy doesn't work from a Statement.");
    } catch (SQLException sqle) {
    }
    stmt.close();

    assertEquals(dataRows, getCount());
  }

  @Test
  void copyQuery() throws SQLException, IOException {
    copyInByRow(); // ensure we have some data.

    long count = copyAPI.copyOut("COPY (SELECT generate_series(1,1000)) TO STDOUT",
        new ByteArrayOutputStream());
    assertEquals(1000, count);
  }

  @Test
  void copyRollback() throws SQLException {
    con.setAutoCommit(false);
    copyInByRow();
    con.rollback();
    assertEquals(0, getCount());
  }

  @Test
  void changeDateStyle() throws SQLException {
    try {
      con.setAutoCommit(false);
      con.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      CopyManager manager = con.unwrap(PGConnection.class).getCopyAPI();

      Statement stmt = con.createStatement();

      stmt.execute("SET DateStyle = 'ISO, DMY'");

      // I expect an SQLException
      String sql = "COPY copytest FROM STDIN with xxx " + copyParams;
      CopyIn cp = manager.copyIn(sql);
      for (String anOrigData : origData) {
        byte[] buf = anOrigData.getBytes();
        cp.writeToCopy(buf, 0, buf.length);
      }

      long count1 = cp.endCopy();
      long count2 = cp.getHandledRowCount();
      con.commit();
    } catch (SQLException ex) {

      // the with xxx is a syntax error which should return a state of 42601
      // if this fails the 'S' command is not being handled in the copy manager query handler
      assertEquals("42601", ex.getSQLState());
      con.rollback();
    }
  }

  @Test
  void lockReleaseOnCancelFailure() throws SQLException, InterruptedException {
    if (!TestUtil.haveMinimumServerVersion(con, ServerVersion.v8_4)) {
      // pg_backend_pid() requires PostgreSQL 8.4+
      return;
    }

    // This is a fairly complex test because it is testing a
    // deadlock that only occurs when the connection to postgres
    // is broken during a copy operation. We'll start a copy
    // operation, use pg_terminate_backend to rudely break it,
    // and then cancel. The test passes if a subsequent operation
    // on the Connection object fails to deadlock.
    con.setAutoCommit(false);

    CopyManager manager = con.unwrap(PGConnection.class).getCopyAPI();
    CopyIn copyIn = manager.copyIn("COPY copytest FROM STDIN with " + copyParams);
    TestUtil.terminateBackend(con);
    try {
      byte[] bunchOfNulls = ",,\n".getBytes();
      while (true) {
        copyIn.writeToCopy(bunchOfNulls, 0, bunchOfNulls.length);
      }
    } catch (SQLException e) {
      acceptIOCause(e);
    } finally {
      if (copyIn.isActive()) {
        try {
          copyIn.cancelCopy();
          fail("cancelCopy should have thrown an exception");
        } catch (SQLException e) {
          acceptIOCause(e);
        }
      }
    }

    // Now we'll execute rollback on another thread so that if the
    // deadlock _does_ occur the case doesn't just hange forever.
    Rollback rollback = new Rollback(con);
    rollback.start();
    rollback.join(1000);
    if (rollback.isAlive()) {
      fail("rollback did not terminate");
    }
    SQLException rollbackException = rollback.exception();
    if (rollbackException == null) {
      fail("rollback should have thrown an exception");
    }
    acceptIOCause(rollbackException);
  }

  private static class Rollback extends Thread {
    private final Connection con;
    private SQLException rollbackException;

    Rollback(Connection con) {
      setName("Asynchronous rollback");
      setDaemon(true);
      this.con = con;
    }

    @Override
    public void run() {
      try {
        con.rollback();
      } catch (SQLException e) {
        rollbackException = e;
      }
    }

    public SQLException exception() {
      return rollbackException;
    }
  }

  private static void acceptIOCause(SQLException e) throws SQLException {
    if (e.getSQLState().equals(PSQLState.CONNECTION_FAILURE.getState())
        || e.getSQLState().equals(PSQLState.CONNECTION_DOES_NOT_EXIST.getState())) {
      // The test expects network exception, so CONNECTION_FAILURE looks good
      return;
    }
    if (!(e.getCause() instanceof IOException)) {
      throw e;
    }
  }

  /**
   * Tests that Connection.isValid() does not hang after a network failure
   * during a COPY operation using PGCopyOutputStream.
   *
   * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3957">Issue 3957</a>
   */
  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void isValidShouldNotHangAfterCopyStreamNetworkFailure() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));
      try (Connection proxyCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(proxyCon, "copytest_stream", "data text");

        String copySql = "COPY copytest_stream (data) FROM STDIN WITH (FORMAT CSV)";

        // Start COPY via PGCopyOutputStream then break the network
        assertThrows(IOException.class, () -> {
          try (PGCopyOutputStream copyOut =
                   new PGCopyOutputStream(proxyCon.unwrap(PgConnection.class), copySql)) {
            // Write some data to ensure COPY is active
            for (int i = 0; i < 10; i++) {
              copyOut.write(("row" + i + "\n").getBytes(StandardCharsets.UTF_8));
              copyOut.flush();
            }
            // Close the sockets to trigger an immediate IOException
            proxyServer.closeAllClients();
            // Continue writing until the broken connection is detected
            for (int i = 0; i < 100_000; i++) {
              copyOut.write(("more-data-" + i + "\n").getBytes(StandardCharsets.UTF_8));
              copyOut.flush();
            }
          }
        }, "Expected IOException from broken connection during COPY");

        assertTimeout(Duration.ofSeconds(10), () -> {
          // This is the actual bug: isValid() should return false promptly,
          // not hang indefinitely waiting for the COPY lock to be released.
          assertFalse(proxyCon.isValid(5),
              "isValid should return false for a broken connection, not hang");
        }, "Test timed out — isValid(5) or connection close hung due to unreleased COPY lock");
      }
    }
  }

  /**
   * Tests that the COPY lock is released after a network failure during
   * CopyIn.writeToCopy(), so subsequent operations do not hang.
   *
   * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3957">Issue 3957</a>
   */
  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void isValidShouldNotHangAfterWriteToCopyNetworkFailure() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));
      try (Connection proxyCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(proxyCon, "copytest_write", "data text");

        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
        CopyIn copyIn = manager.copyIn("COPY copytest_write (data) FROM STDIN WITH (FORMAT CSV)");

        // Write some data to ensure COPY is active
        byte[] row = "somedata\n".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 10; i++) {
          copyIn.writeToCopy(row, 0, row.length);
          copyIn.flushCopy();
        }

        // Close the sockets to trigger an immediate IOException
        proxyServer.closeAllClients();

        // writeToCopy or flushCopy should fail with an IOException
        assertThrows(SQLException.class, () -> {
          for (int i = 0; i < 100_000; i++) {
            copyIn.writeToCopy(row, 0, row.length);
            copyIn.flushCopy();
          }
        }, "Expected SQLException from broken connection during writeToCopy");

        assertTimeout(Duration.ofSeconds(5), () -> {
          assertFalse(proxyCon.isValid(3),
              "isValid should return false for a broken connection, not hang");
        }, "isValid() hung — COPY lock was not released after writeToCopy failure");
      }
    }
  }

  /**
   * Tests that the COPY lock is released after a network failure during
   * CopyOut.readFromCopy(), so subsequent operations do not hang.
   *
   * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3957">Issue 3957</a>
   */
  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void isValidShouldNotHangAfterReadFromCopyNetworkFailure() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));
      try (Connection proxyCon = TestUtil.openDB(props)) {
        // Insert enough data so the COPY TO STDOUT will have rows to read
        try (Statement stmt = proxyCon.createStatement()) {
          stmt.execute("CREATE TEMP TABLE copytest_read (data text)");
          stmt.execute(
              "INSERT INTO copytest_read SELECT 'row' || g FROM generate_series(1, 10000) g");
        }

        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
        CopyOut copyOut = manager.copyOut("COPY copytest_read TO STDOUT");

        // Read a few rows to ensure COPY is active
        for (int i = 0; i < 5; i++) {
          assertNotNull(copyOut.readFromCopy(), "Expected data row from COPY TO STDOUT");
        }

        // Close the sockets to trigger an immediate IOException on next read
        proxyServer.closeAllClients();

        assertThrows(SQLException.class, () -> {
          //noinspection StatementWithEmptyBody
          while (copyOut.readFromCopy() != null) {
            // drain until failure
          }
        }, "Expected SQLException from broken connection during readFromCopy");

        assertTimeout(Duration.ofSeconds(5), () -> {
          assertFalse(proxyCon.isValid(3),
              "isValid should return false for a broken connection, not hang");
        }, "isValid() hung — COPY lock was not released after readFromCopy failure");
      }
    }
  }

  /**
   * Tests that the COPY lock is released after a network failure during
   * PGCopyInputStream.read(), so subsequent operations do not hang.
   *
   * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3957">Issue 3957</a>
   */
  @Test
  @Timeout(value = 15, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void isValidShouldNotHangAfterCopyInputStreamNetworkFailure() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));
      try (Connection proxyCon = TestUtil.openDB(props)) {
        try (Statement stmt = proxyCon.createStatement()) {
          stmt.execute("CREATE TEMP TABLE copytest_instream (data text)");
          stmt.execute(
              "INSERT INTO copytest_instream SELECT 'row' || g FROM generate_series(1, 10000) g");
        }

        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();

        assertThrows(IOException.class, () -> {
          try (PGCopyInputStream copyIn = new PGCopyInputStream(
              manager.copyOut("COPY copytest_instream TO STDOUT"))) {
            byte[] buf = new byte[1024];
            int bytesRead = 0;
            // Read a bit of data, then break the connection
            while (copyIn.read(buf) >= 0) {
              bytesRead += buf.length;
              if (bytesRead > 4096) {
                proxyServer.closeAllClients();
              }
            }
          }
        }, "Expected IOException from broken connection during PGCopyInputStream.read()");

        assertTimeout(Duration.ofSeconds(5), () -> {
          assertFalse(proxyCon.isValid(3),
              "isValid should return false for a broken connection, not hang");
        }, "isValid() hung — COPY lock was not released after CopyInputStream failure");
      }
    }
  }

  /**
   * A socket read timeout is not a connection failure, so it must leave the COPY operation active.
   * Deactivating the COPY takes away {@link CopyOut#cancelCopy()}, the only way out of a stalled
   * one, and it takes away the wake-up logical replication sends its status updates on.
   *
   * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/4328">Issue 4328</a>
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void readTimeoutKeepsCopyActive() throws Exception {
    TestUtil.createTable(con, "copytest_timeout", "data text");
    try {
      try (Statement stmt = con.createStatement()) {
        stmt.execute(
            "INSERT INTO copytest_timeout SELECT 'row' || g FROM generate_series(1, 20000) g");
      }

      try (MessageStallProxyServer proxyServer = new MessageStallProxyServer(TestUtil.getServer(),
          TestUtil.getPort())) {
        Properties props = new Properties();
        TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
        TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
            String.valueOf(proxyServer.getServerPort()));
        // Without socketTimeout the driver retries the read instead of reporting the timeout
        PGProperty.SOCKET_TIMEOUT.set(props, 1);
        // The cancel request goes through the same dead proxy, so do not wait 10s for its EOF
        PGProperty.CANCEL_SIGNAL_TIMEOUT.set(props, 1);
        // The proxy reads the v3 framing to find a message boundary, so it needs plain traffic
        PGProperty.SSL_MODE.set(props, "disable");
        PGProperty.GSS_ENC_MODE.set(props, "disable");

        try (Connection proxyCon = TestUtil.openDB(props)) {
          CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
          CopyOut copyOut = manager.copyOut("COPY copytest_timeout TO STDOUT");
          assertNotNull(copyOut.readFromCopy(), "Expected data row from COPY TO STDOUT");

          // Starve the reader between messages, which is the only place a timeout leaves the
          // stream somewhere the caller can carry on from
          proxyServer.stallBeforeNextMessage(TimeUnit.SECONDS.toMillis(30));

          SQLException timeout = readUntilTimeout(copyOut);
          assertInstanceOf(SocketTimeoutException.class, timeout.getCause(),
              () -> "readFromCopy should report the socket read timeout, got " + timeout);
          assertTrue(copyOut.isActive(),
              "A socket read timeout must not deactivate the COPY operation");

          // Canceling is the way out of a stalled COPY, and it needs the COPY lock
          copyOut.cancelCopy();
          assertFalse(copyOut.isActive(), "cancelCopy() should end the COPY operation");
        }
      }
    } finally {
      TestUtil.dropTable(con, "copytest_timeout");
    }
  }

  /**
   * Fails when a read timeout inside a backend message is reported as something the caller can
   * carry on from. The bytes the driver already took are not in the stream any more, so the next
   * read would take the middle of that message for the start of the next one.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void readTimeoutInsideMessageEndsTheCopy() throws Exception {
    TestUtil.createTable(con, "copytest_stall", "data text");
    try {
      try (Statement stmt = con.createStatement()) {
        stmt.execute(
            "INSERT INTO copytest_stall SELECT 'row' || g FROM generate_series(1, 20000) g");
      }

      try (MessageStallProxyServer proxyServer = new MessageStallProxyServer(TestUtil.getServer(),
          TestUtil.getPort())) {
        Properties props = new Properties();
        TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
        TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
            String.valueOf(proxyServer.getServerPort()));
        PGProperty.SOCKET_TIMEOUT.set(props, 1);
        PGProperty.CANCEL_SIGNAL_TIMEOUT.set(props, 1);
        PGProperty.SSL_MODE.set(props, "disable");
        PGProperty.GSS_ENC_MODE.set(props, "disable");

        try (Connection proxyCon = TestUtil.openDB(props)) {
          CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
          CopyOut copyOut = manager.copyOut("COPY copytest_stall TO STDOUT");
          assertNotNull(copyOut.readFromCopy(), "Expected data row from COPY TO STDOUT");

          proxyServer.stallInsideNextMessage(TimeUnit.SECONDS.toMillis(30));

          SQLException timeout = readUntilTimeout(copyOut);
          assertInstanceOf(IOException.class, timeout.getCause(),
              () -> "a timeout inside a message is a connection failure, got " + timeout);
          assertFalse(timeout.getCause() instanceof SocketTimeoutException,
              "a timeout inside a message must not be reported as one the caller can resume from");
          assertTrue(proxyCon.isClosed(),
              "a stream that lost its message boundary has to take the connection with it");
          assertThrows(SQLException.class, copyOut::readFromCopy,
              "the copy must not look like something to carry on reading from");
        }
      }
    } finally {
      TestUtil.dropTable(con, "copytest_stall");
    }
  }


  /**
   * A COPY that dies with the connection keeps the COPY lock, so a thread already waiting for that
   * lock has to be told the connection is gone rather than wait for a copy that can no longer end.
   *
   * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3957">Issue 3957</a>
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void waitingThreadGivesUpWhenCopyDiesWithConnection() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));

      try (Connection proxyCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(proxyCon, "copytest_wait", "data text");

        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
        CopyIn copyIn = manager.copyIn("COPY copytest_wait (data) FROM STDIN WITH (FORMAT CSV)");
        byte[] row = "somedata\n".getBytes(StandardCharsets.UTF_8);
        copyIn.writeToCopy(row, 0, row.length);
        copyIn.flushCopy();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
          AtomicReference<Thread> waiter = new AtomicReference<>();
          Future<Boolean> valid = executor.submit(() -> {
            waiter.set(Thread.currentThread());
            return proxyCon.isValid(20);
          });
          awaitWaitingForCopyLock(waiter);

          // Break the connection under the COPY while the other thread waits for the lock
          proxyServer.closeAllClients();
          assertThrows(SQLException.class, () -> {
            for (int i = 0; i < 100_000; i++) {
              copyIn.writeToCopy(row, 0, row.length);
              copyIn.flushCopy();
            }
          }, "Expected SQLException from broken connection during writeToCopy");

          assertFalse(valid.get(10, TimeUnit.SECONDS),
              "The waiting thread should report the connection as invalid instead of waiting for "
                  + "a COPY that can no longer end");
        } finally {
          executor.shutdownNow();
        }
      }
    }
  }

  /**
   * The query that starts a COPY is already on the wire when the connection breaks, so its response
   * can neither be read nor asked for again. The driver has to give the connection up rather than
   * hand back a live one whose protocol stream is out of step.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void startCopyFailureClosesConnection() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));

      try (Connection proxyCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(proxyCon, "copytest_start", "data text");
        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();

        proxyServer.closeAllClients();

        assertThrows(SQLException.class,
            () -> manager.copyIn("COPY copytest_start (data) FROM STDIN WITH (FORMAT CSV)"),
            "Expected SQLException from broken connection while starting COPY");
        assertTrue(proxyCon.isClosed(),
            "A COPY that cannot read its own start response leaves the protocol stream out of "
                + "step, so the connection should be given up");
      }
    }
  }

  /**
   * Canceling a COPY over a broken connection cannot reach the server, so the driver has to give
   * the connection up. The COPY itself is over either way.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void cancelCopyFailureClosesConnection() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));

      try (Connection proxyCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(proxyCon, "copytest_cancel", "data text");

        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
        CopyIn copyIn = manager.copyIn("COPY copytest_cancel (data) FROM STDIN WITH (FORMAT CSV)");
        byte[] row = "somedata\n".getBytes(StandardCharsets.UTF_8);
        copyIn.writeToCopy(row, 0, row.length);
        copyIn.flushCopy();

        proxyServer.closeAllClients();

        assertThrows(SQLException.class, copyIn::cancelCopy,
            "Expected SQLException from broken connection while canceling COPY");
        assertFalse(copyIn.isActive(), "cancelCopy() should end the COPY operation");
        assertTrue(proxyCon.isClosed(),
            "A cancel that never reached the server should give the connection up");
      }
    }
  }

  /**
   * The {@link CopyIn#writeToCopy(org.postgresql.util.ByteStreamWriter)} overload has to give the
   * connection up on an I/O failure just like the byte-array one.
   *
   * @see <a href="https://github.com/pgjdbc/pgjdbc/issues/3957">Issue 3957</a>
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void writeToCopyFromByteStreamWriterFailureClosesConnection() throws Exception {
    try (StrangeProxyServer proxyServer = new StrangeProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));

      try (Connection proxyCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(proxyCon, "copytest_writer", "data text");

        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
        CopyIn copyIn = manager.copyIn("COPY copytest_writer (data) FROM STDIN WITH (FORMAT CSV)");
        byte[] row = "somedata\n".getBytes(StandardCharsets.UTF_8);
        copyIn.writeToCopy(new ByteBufferByteStreamWriter(ByteBuffer.wrap(row)));
        copyIn.flushCopy();

        proxyServer.closeAllClients();

        // A payload this large outgrows the output buffer, so it has to reach the socket within
        // writeToCopy itself rather than waiting for the flush
        byte[] bigRow = new byte[1024 * 1024];
        Arrays.fill(bigRow, (byte) 'x');
        bigRow[bigRow.length - 1] = '\n';
        assertThrows(SQLException.class,
            () -> copyIn.writeToCopy(new ByteBufferByteStreamWriter(ByteBuffer.wrap(bigRow))),
            "Expected SQLException from broken connection during writeToCopy");

        assertTrue(proxyCon.isClosed(),
            "A COPY that failed to send should give the connection up");
      }
    }
  }

  /**
   * Interrupting a thread that waits for a COPY to finish fails its query and leaves the interrupt
   * flag set, rather than swallowing the interrupt.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void interruptingAThreadWaitingForTheCopyKeepsTheInterruptFlag() throws Exception {
    CopyIn copyIn = copyAPI.copyIn("COPY copytest FROM STDIN");
    byte[] row = origData[0].getBytes(StandardCharsets.UTF_8);
    copyIn.writeToCopy(row, 0, row.length);
    copyIn.flushCopy();

    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      AtomicReference<Thread> waiter = new AtomicReference<>();
      AtomicBoolean stillInterrupted = new AtomicBoolean();
      Future<SQLException> failure = executor.submit(() -> {
        waiter.set(Thread.currentThread());
        try (Statement stmt = con.createStatement()) {
          stmt.execute("SELECT 1");
          return null;
        } catch (SQLException e) {
          stillInterrupted.set(Thread.currentThread().isInterrupted());
          return e;
        }
      });
      awaitWaitingForCopyLock(waiter).interrupt();

      SQLException e = failure.get(10, TimeUnit.SECONDS);
      assertNotNull(e, "The interrupted query should fail instead of waiting for the COPY");
      assertEquals(PSQLState.OBJECT_NOT_IN_STATE.getState(), e.getSQLState(),
          () -> "Unexpected failure: " + e);
      assertTrue(stillInterrupted.get(), "The interrupt flag should survive the failure");
    } finally {
      executor.shutdownNow();
      copyIn.endCopy();
    }
  }

  /**
   * Every thread waiting for a COPY to finish has to be woken when it does, not just one of them.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void allWaitingThreadsResumeWhenCopyEnds() throws Exception {
    CopyIn copyIn = copyAPI.copyIn("COPY copytest FROM STDIN");
    byte[] row = origData[0].getBytes(StandardCharsets.UTF_8);
    copyIn.writeToCopy(row, 0, row.length);
    copyIn.flushCopy();

    ExecutorService executor = Executors.newFixedThreadPool(3);
    try {
      List<Future<Boolean>> waiters = new ArrayList<>();
      List<AtomicReference<Thread>> threads = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        AtomicReference<Thread> thread = new AtomicReference<>();
        threads.add(thread);
        waiters.add(executor.submit(() -> {
          thread.set(Thread.currentThread());
          return con.isValid(20);
        }));
      }
      for (AtomicReference<Thread> thread : threads) {
        awaitWaitingForCopyLock(thread);
      }

      copyIn.endCopy();

      for (Future<Boolean> waiter : waiters) {
        assertTrue(waiter.get(10, TimeUnit.SECONDS),
            "Every thread waiting for the COPY should resume once it ends");
      }
    } finally {
      executor.shutdownNow();
    }
  }

  /**
   * Waits until the helper thread parks on the COPY lock. Fails the test if it never does.
   */
  private static Thread awaitWaitingForCopyLock(AtomicReference<Thread> waiter) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Thread thread = waiter.get();
      if (thread != null && thread.getState() == Thread.State.WAITING) {
        return thread;
      }
      Thread.yield();
    }
    return fail("The helper thread never started waiting for the COPY lock");
  }

  /**
   * Fails when a read timeout throws away an error the backend already sent. The copy loop keeps
   * the error until the ReadyForQuery that follows it, so a timeout waiting for that message must
   * not be reported as "nothing arrived yet" — a caller that reads again would then see the copy
   * finish successfully.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void readTimeoutAfterAnErrorResponseStillReportsTheError() throws Exception {
    try (MessageStallProxyServer proxyServer = new MessageStallProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));
      PGProperty.SOCKET_TIMEOUT.set(props, 1);
      PGProperty.CANCEL_SIGNAL_TIMEOUT.set(props, 1);
      PGProperty.SSL_MODE.set(props, "disable");
      PGProperty.GSS_ENC_MODE.set(props, "disable");

      try (Connection proxyCon = TestUtil.openDB(props)) {
        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
        // The last row divides by zero, so the server streams data and then reports an error
        CopyOut copyOut = manager.copyOut(
            "COPY (SELECT 1 / (20000 - g) FROM generate_series(1, 20000) g) TO STDOUT");

        // Hold back the ReadyForQuery that closes the exchange, so the read that waits for it
        // times out with the error already in hand
        proxyServer.stallBeforeMessageType('Z', TimeUnit.SECONDS.toMillis(20));

        SQLException thrown = readUntilTimeout(copyOut);
        assertEquals(PSQLState.DIVISION_BY_ZERO.getState(), thrown.getSQLState(),
            () -> "the backend error has to survive the timeout that followed it, got " + thrown);
      }
    }
  }

  /**
   * Fails when endCopy keeps the COPY lock after the exchange stopped somewhere other than
   * ReadyForQuery. A retained lock with a live connection is a lock nothing can take back:
   * waitOnLock() gives up only once the connection is closed, so every later operation on it
   * waits forever.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void endCopyGivesTheConnectionUpWhenItCannotReleaseTheLock() throws Exception {
    try (MessageStallProxyServer proxyServer = new MessageStallProxyServer(TestUtil.getServer(),
        TestUtil.getPort())) {
      Properties props = new Properties();
      TestUtil.setTestUrlProperty(props, PGProperty.PG_HOST, "localhost");
      TestUtil.setTestUrlProperty(props, PGProperty.PG_PORT,
          String.valueOf(proxyServer.getServerPort()));
      PGProperty.CANCEL_SIGNAL_TIMEOUT.set(props, 1);
      PGProperty.SSL_MODE.set(props, "disable");
      PGProperty.GSS_ENC_MODE.set(props, "disable");

      try (Connection proxyCon = TestUtil.openDB(props)) {
        TestUtil.createTempTable(proxyCon, "copytest_endcopy", "data text");
        CopyManager manager = proxyCon.unwrap(PGConnection.class).getCopyAPI();
        CopyIn copyIn = manager.copyIn("COPY copytest_endcopy FROM STDIN");
        byte[] row = "one\n".getBytes(StandardCharsets.UTF_8);
        copyIn.writeToCopy(row, 0, row.length);

        // CopyData is the server's answer to a COPY TO, never to a COPY FROM. The copy loop stops
        // on it without reaching the ReadyForQuery that would hand the lock back
        proxyServer.injectBeforeNextMessage(copyDataMessage("stray"));

        assertThrows(SQLException.class, copyIn::endCopy,
            "the driver should report the message it cannot make sense of");
        assertTrue(proxyCon.isClosed(),
            "a copy that cannot give its lock back has to take the connection with it");
      }
    }
  }

  /** Builds a CopyData message: the type byte, a self-inclusive length, then the payload. */
  private static byte[] copyDataMessage(String payload) {
    byte[] body = payload.getBytes(StandardCharsets.UTF_8);
    ByteBuffer message = ByteBuffer.allocate(1 + 4 + body.length);
    message.put((byte) 'd').putInt(4 + body.length).put(body);
    return message.array();
  }

  /**
   * Reads until {@code readFromCopy} fails, and returns that failure.
   */
  private static SQLException readUntilTimeout(CopyOut copyOut) throws SQLException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (System.nanoTime() < deadline) {
      try {
        if (copyOut.readFromCopy() == null) {
          return fail("COPY completed before the reader ran out of buffered data");
        }
      } catch (SQLException e) {
        return e;
      }
    }
    return fail("readFromCopy kept returning data even though the proxy stopped forwarding");
  }
}
