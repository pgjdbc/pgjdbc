/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.postgresql.core.PGStreamTestSupport.openStream;

import org.postgresql.core.v3.QueryExecutorImpl;
import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.List;
import java.util.Properties;

/**
 * {@link QueryExecutorImpl} restarts the {@code maxResultBuffer} count and re-arms the over-sized
 * row report on every ReadyForQuery, so the rows of one statement never affect the next one on
 * the connection, whether the earlier statement completed, failed on the server, or had a row
 * skipped.
 *
 * <p>The executor reads from bytes built here through a fake socket, so no server is involved.
 * Each backend response ends in ReadyForQuery. The failed statement is sent through the extended
 * protocol, where the failed Execute stays at the head of the executor's queue when ReadyForQuery
 * arrives.</p>
 */
class QueryExecutorImplMaxResultBufferTest {
  private static final String SINGLE_ROW =
      "Result set exceeded maxResultBuffer limit. A single row of {0} bytes exceeds the limit of {1} bytes, so the row was skipped.";

  /**
   * The first statement buffers a 6-byte row and then fails with an ErrorResponse, and the second
   * returns another 6-byte row: 12 bytes together, over the limit of 10.
   */
  @Test
  void theCountRestartsAfterAStatementThatFailedOnTheServerAfterARow() throws Exception {
    byte[] failing = concat(parseAndBindComplete(), rowDescription(), dataRow(6),
        errorResponse("22012"), readyForQuery());
    byte[] succeeding = concat(parseAndBindComplete(), rowDescription(), dataRow(6),
        commandComplete(), readyForQuery());
    Session s = new Session(concat(readyForQuery(), failing, succeeding));
    s.stream.setMaxResultBuffer("10");
    QueryExecutorImpl executor = s.connect();
    SQLException serverError = assertThrowsExactly(PSQLException.class,
        () -> s.executeExtendedQuery(executor), "first statement");
    assertEquals("22012", serverError.getSQLState(), "first statement SQLState");

    Recorder second = s.executeExtendedQuery(executor);

    assertEquals(1, second.rowCount, "second statement rows");
  }

  /**
   * Each statement returns rows of 11 and 12 bytes against a limit of 10. Within one statement
   * only the first skipped row throws, so the handler chains a single exception.
   */
  @Test
  void eachReadyForQueryReArmsTheOversizedRowReport() throws Exception {
    byte[] statement = concat(rowDescription(), dataRow(11), dataRow(12), commandComplete(),
        readyForQuery());
    Session s = new Session(concat(readyForQuery(), statement, statement));
    s.stream.setMaxResultBuffer("10");
    QueryExecutorImpl executor = s.connect();
    PSQLException first = assertThrowsExactly(PSQLException.class,
        () -> s.executeSimpleQuery(executor), "first statement");

    PSQLException second = assertThrowsExactly(PSQLException.class,
        () -> s.executeSimpleQuery(executor), "second statement");

    assertAll(
        () -> assertEquals(GT.tr(SINGLE_ROW, "11", "10"), first.getMessage(), "first statement"),
        () -> assertNull(first.getNextException(), "first statement next exception"),
        () -> assertEquals(GT.tr(SINGLE_ROW, "11", "10"), second.getMessage(), "second statement"),
        () -> assertFalse(executor.isClosed(), "executor isClosed()"));
  }

  private static byte[] readyForQuery() {
    return message('Z', new byte[]{'I'});
  }

  private static byte[] parseAndBindComplete() {
    return concat(message('1', new byte[0]), message('2', new byte[0]));
  }

  /** One text column of type OID 25. */
  private static byte[] rowDescription() {
    return message('T', new Wire().int2(1).raw(cstring("a")).int4(0).int2(1).int4(25).int2(-1)
        .int4(-1).int2(0).toBytes());
  }

  private static byte[] dataRow(int fieldLength) {
    return message('D', new Wire().int2(1).int4(fieldLength).bytes(fieldLength).toBytes());
  }

  private static byte[] commandComplete() {
    return message('C', cstring("SELECT 1"));
  }

  private static byte[] errorResponse(String sqlState) {
    return message('E', new Wire()
        .int1('S').raw(cstring("ERROR"))
        .int1('C').raw(cstring(sqlState))
        .int1('M').raw(cstring("division by zero"))
        .int1(0).toBytes());
  }

  /** A message whose length field counts itself and {@code body}. */
  private static byte[] message(char type, byte[] body) {
    return new Wire().int1(type).int4(4 + body.length).raw(body).toBytes();
  }

  private static byte[] cstring(String value) {
    return new Wire().raw(value.getBytes(StandardCharsets.UTF_8)).int1(0).toBytes();
  }

  private static byte[] concat(byte[]... parts) {
    Wire wire = new Wire();
    for (byte[] part : parts) {
      wire.raw(part);
    }
    return wire.toBytes();
  }

  /** A {@link QueryExecutorImpl} over a fake socket that serves the backend bytes. */
  private static final class Session {
    final PGStream stream;

    Session(byte[] backendBytes) {
      stream = openStream(new FakeSocket(backendBytes));
    }

    /** Creates the executor, which reads the startup ReadyForQuery. */
    QueryExecutorImpl connect() throws Exception {
      return new QueryExecutorImpl(stream, 0, new Properties());
    }

    Recorder executeSimpleQuery(QueryExecutorImpl executor) throws SQLException {
      Recorder recorder = new Recorder();
      executor.execute(executor.createSimpleQuery("SELECT a"), null, recorder, 0, 0,
          QueryExecutor.QUERY_EXECUTE_AS_SIMPLE | QueryExecutor.QUERY_SUPPRESS_BEGIN);
      return recorder;
    }

    /** Sends Parse, Bind, Describe, Execute, and Sync for an unnamed statement. */
    Recorder executeExtendedQuery(QueryExecutorImpl executor) throws SQLException {
      Recorder recorder = new Recorder();
      Query query = executor.createSimpleQuery("SELECT a");
      executor.execute(query, query.createParameterList(), recorder, 0, 0,
          QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_SUPPRESS_BEGIN);
      return recorder;
    }
  }

  /** Counts the rows the executor hands over, and throws the first error from handleCompletion. */
  private static final class Recorder extends ResultHandlerBase {
    int rowCount;

    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        ResultCursor cursor) {
      rowCount += tuples.size();
    }

    @Override
    public void handleCommandStatus(String status, long updateCount, long insertOID) {
    }

    @Override
    public void handleWarning(SQLWarning warning) {
    }
  }
}
