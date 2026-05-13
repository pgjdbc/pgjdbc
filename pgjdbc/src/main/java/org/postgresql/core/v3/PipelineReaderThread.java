/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.CommandCompleteParser;
import org.postgresql.core.Field;
import org.postgresql.core.Notification;
import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.core.TransactionState;
import org.postgresql.core.Tuple;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLWarning;
import org.postgresql.util.ServerErrorMessage;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Dedicated reader thread for pipeline mode. Owns pgInput exclusively after startup.
 * Reads protocol messages and dispatches results to ResponseSlots.
 */
class PipelineReaderThread extends Thread {

  private static final Logger LOGGER = Logger.getLogger(PipelineReaderThread.class.getName());

  private final PGStream pgStream;
  private final QueryExecutorImpl executor;
  private final ConcurrentLinkedDeque<ResponseSlot> pendingSlots;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final CommandCompleteParser commandCompleteParser = new CommandCompleteParser();

  private @Nullable ResponseSlot currentSlot;

  PipelineReaderThread(PGStream pgStream, QueryExecutorImpl executor,
                       ConcurrentLinkedDeque<ResponseSlot> pendingSlots) {
    super("pgjdbc-reader-" + pgStream.getHostSpec());
    setDaemon(true);
    this.pgStream = pgStream;
    this.executor = executor;
    this.pendingSlots = pendingSlots;
  }

  void shutdown() {
    running.set(false);
    this.interrupt();
  }

  @Override
  public void run() {
    try {
      while (running.get()) {
        try {
          processOneMessage();
        } catch (SocketTimeoutException e) {
          // Network timeout — fail current slot but don't kill the thread
          if (currentSlot != null) {
            currentSlot.completeExceptionally(new PSQLException(
                "Query timed out", PSQLState.QUERY_CANCELED, e));
            currentSlot = null;
          }
        }
      }
    } catch (IOException e) {
      if (running.get()) {
        failAllPending(new PSQLException(
            "An I/O error occurred while reading from the backend.",
            PSQLState.CONNECTION_FAILURE, e));
      }
    }
  }

  private void processOneMessage() throws IOException {
    int c = pgStream.receiveChar();

    switch (c) {
      case PgMessageType.ASYNCHRONOUS_NOTICE: // 'A'
        receiveAsyncNotify();
        break;

      case PgMessageType.PARSE_COMPLETE_RESPONSE: // '1'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE ParseComplete");
        break;

      case PgMessageType.BIND_COMPLETE_RESPONSE: // '2'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE BindComplete");
        break;

      case PgMessageType.CLOSE_COMPLETE_RESPONSE: // '3'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE CloseComplete");
        break;

      case PgMessageType.NO_DATA_RESPONSE: // 'n'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE NoData");
        break;

      case PgMessageType.PARAMETER_DESCRIPTION_RESPONSE: // 't'
        receiveParameterDescription();
        break;

      case PgMessageType.ROW_DESCRIPTION_RESPONSE: // 'T'
        receiveRowDescription();
        break;

      case PgMessageType.DATA_ROW_RESPONSE: // 'D'
        receiveDataRow();
        break;

      case PgMessageType.COMMAND_COMPLETE_RESPONSE: // 'C'
        receiveCommandComplete();
        break;

      case PgMessageType.EMPTY_QUERY_RESPONSE: // 'I'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE EmptyQuery");
        advanceSlot();
        if (currentSlot != null) {
          currentSlot.commandStatus = "EMPTY";
        }
        break;

      case PgMessageType.PORTAL_SUSPENDED_RESPONSE: // 's'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE PortalSuspended");
        if (currentSlot != null) {
          currentSlot.portalSuspended = true;
          currentSlot.complete();
          currentSlot = null;
        }
        break;

      case PgMessageType.ERROR_RESPONSE: // 'E'
        receiveError();
        break;

      case PgMessageType.NOTICE_RESPONSE: // 'N'
        receiveNotice();
        break;

      case PgMessageType.PARAMETER_STATUS_RESPONSE: // 'S'
        receiveParameterStatus();
        break;

      case PgMessageType.READY_FOR_QUERY_RESPONSE: // 'Z'
        receiveReadyForQuery();
        break;

      default:
        // Skip unknown messages
        int len = pgStream.receiveInteger4();
        if (len > 4) {
          pgStream.receive(len - 4);
        }
        LOGGER.log(Level.WARNING, " <=BE Unknown message type: {0}", (char) c);
        break;
    }
  }

  private void advanceSlot() {
    if (currentSlot == null) {
      currentSlot = pendingSlots.poll();
      // Skip SYNC_MARKERs — they're consumed in receiveReadyForQuery
      while (currentSlot == ResponseSlot.SYNC_MARKER) {
        currentSlot = pendingSlots.poll();
      }
    }
  }

  private void receiveRowDescription() throws IOException {
    pgStream.receiveInteger4(); // message size
    int size = pgStream.receiveInteger2();
    Field[] fields = new Field[size];

    for (int i = 0; i < fields.length; i++) {
      String columnLabel = pgStream.receiveCanonicalString();
      int tableOid = pgStream.receiveInteger4();
      int positionInTable = pgStream.receiveInteger2();
      int typeOid = pgStream.receiveInteger4();
      int typeLength = pgStream.receiveInteger2();
      int typeModifier = pgStream.receiveInteger4();
      int formatType = pgStream.receiveInteger2();
      fields[i] = new Field(columnLabel, typeOid, typeLength, typeModifier, tableOid, positionInTable);
      fields[i].setFormat(formatType);
    }

    LOGGER.log(Level.FINEST, " <=BE RowDescription({0})", size);

    advanceSlot();
    if (currentSlot != null && currentSlot.query != null) {
      currentSlot.fields = fields;
      currentSlot.query.setFields(fields);
    }
  }

  private void receiveParameterDescription() throws IOException {
    pgStream.receiveInteger4(); // message size
    int numParams = pgStream.receiveInteger2();
    for (int i = 0; i < numParams; i++) {
      pgStream.receiveInteger4(); // type OID — consumed but not used in pipeline mode
    }
    LOGGER.log(Level.FINEST, " <=BE ParameterDescription({0})", numParams);
  }

  private void receiveDataRow() throws IOException {
    Tuple tuple;
    try {
      tuple = pgStream.receiveTupleV3();
    } catch (OutOfMemoryError oome) {
      throw new IOException("Ran out of memory retrieving query results", oome);
    } catch (SQLException e) {
      throw new IOException("Error receiving data row", e);
    }

    advanceSlot();
    if (currentSlot != null) {
      currentSlot.addTuple(tuple);
    }

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " <=BE DataRow(len={0})", tuple.length());
    }
  }

  private void receiveCommandComplete() throws IOException {
    int len = pgStream.receiveInteger4();
    String status = pgStream.receiveString(len - 5);
    pgStream.receiveChar(); // trailing \0

    LOGGER.log(Level.FINEST, " <=BE CommandStatus({0})", status);

    advanceSlot();
    if (currentSlot != null) {
      ResponseSlot slot = currentSlot;
      slot.commandStatus = status;
      try {
        commandCompleteParser.parse(status);
        slot.updateCount = commandCompleteParser.getRows();
        slot.insertOID = commandCompleteParser.getOid();
      } catch (SQLException e) {
        slot.completeExceptionally(e);
        currentSlot = null;
        return;
      }
      // For non-simple queries, CommandComplete ends this slot's data
      // (ReadyForQuery will finalize it)
      // For simple queries, more results may follow before ReadyForQuery
      if (!slot.asSimple) {
        slot.complete();
        currentSlot = null;
      }
    }
  }

  private void receiveError() throws IOException {
    int elen = pgStream.receiveInteger4();
    ServerErrorMessage errorMsg = new ServerErrorMessage(
        pgStream.receiveErrorString(elen - 4));

    LOGGER.log(Level.FINEST, " <=BE ErrorMessage({0})", errorMsg.toString());

    PSQLException error = new PSQLException(errorMsg, executor.getLogServerErrorDetail());

    advanceSlot();
    if (currentSlot != null) {
      currentSlot.completeExceptionally(error);
      currentSlot = null;
    }
  }

  private void receiveNotice() throws IOException {
    int nlen = pgStream.receiveInteger4();
    ServerErrorMessage warnMsg = new ServerErrorMessage(pgStream.receiveErrorString(nlen - 4));
    SQLWarning warning = new PSQLWarning(warnMsg);

    LOGGER.log(Level.FINEST, " <=BE NoticeResponse({0})", warnMsg.toString());

    if (currentSlot != null) {
      currentSlot.addWarning(warning);
    } else {
      executor.addWarning(warning);
    }
  }

  private void receiveParameterStatus() throws IOException {
    try {
      executor.receiveParameterStatus();
    } catch (SQLException e) {
      // Fatal parameter status change (encoding, datestyle)
      failAllPending(e);
    }
  }

  private void receiveAsyncNotify() throws IOException {
    pgStream.receiveInteger4(); // message length
    int pid = pgStream.receiveInteger4();
    String msg = pgStream.receiveCanonicalString();
    String param = pgStream.receiveString();
    executor.addNotification(new Notification(msg, pid, param));

    LOGGER.log(Level.FINEST, " <=BE AsyncNotify({0},{1},{2})", new Object[]{pid, msg, param});
  }

  private void receiveReadyForQuery() throws IOException {
    if (pgStream.receiveInteger4() != 5) {
      throw new IOException("unexpected length of ReadyForQuery message");
    }

    char tStatus = (char) pgStream.receiveChar();
    LOGGER.log(Level.FINEST, " <=BE ReadyForQuery({0})", tStatus);

    switch (tStatus) {
      case 'I':
        executor.setTransactionState(TransactionState.IDLE);
        break;
      case 'T':
        executor.setTransactionState(TransactionState.OPEN);
        break;
      case 'E':
        executor.setTransactionState(TransactionState.FAILED);
        break;
      default:
        throw new IOException("unexpected transaction state: " + (int) tStatus);
    }

    // Complete current slot if still active (simple query case)
    if (currentSlot != null) {
      currentSlot.complete();
      currentSlot = null;
    }

    // Drain any remaining slots up to the SYNC_MARKER — these were
    // discarded by the server after an error
    ResponseSlot slot;
    while ((slot = pendingSlots.peek()) != null) {
      if (slot == ResponseSlot.SYNC_MARKER) {
        pendingSlots.poll(); // consume the marker
        break;
      }
      pendingSlots.poll();
      slot.completeExceptionally(new PSQLException(
          "Query discarded due to prior error in pipeline batch",
          PSQLState.IN_FAILED_SQL_TRANSACTION));
    }
  }

  private void failAllPending(SQLException ex) {
    if (currentSlot != null) {
      currentSlot.completeExceptionally(ex);
      currentSlot = null;
    }
    ResponseSlot slot;
    while ((slot = pendingSlots.poll()) != null) {
      if (slot != ResponseSlot.SYNC_MARKER) {
        slot.completeExceptionally(ex);
      }
    }
  }

  // Overload for IOException
  private void failAllPending(PSQLException ex) {
    failAllPending((SQLException) ex);
  }
}
