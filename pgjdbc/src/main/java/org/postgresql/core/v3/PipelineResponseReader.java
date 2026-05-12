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
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * NIO-style pipeline response reader. Supports both blocking reads (for final drain)
 * and non-blocking reads (for interleaved send/receive during batch execution).
 *
 * <p>This implements "Option A" NIO pipelining: a single thread interleaves sending
 * queries and reading responses using non-blocking read checks. During batch execution,
 * the caller sends a chunk of queries, then calls {@link #readAvailableResponses} to
 * drain any responses that have arrived without blocking, then sends more queries.
 * After all queries are sent, {@link #readResponses} blocks until all responses arrive.</p>
 */
class PipelineResponseReader {

  private static final Logger LOGGER = Logger.getLogger(PipelineResponseReader.class.getName());

  private final PGStream pgStream;
  private final QueryExecutorImpl executor;
  private final CommandCompleteParser commandCompleteParser = new CommandCompleteParser();

  /** Current slot index being populated — maintained across non-blocking read calls. */
  private int slotIndex;
  /** Current slot being populated — maintained across non-blocking read calls. */
  private @Nullable ResponseSlot currentSlot;
  /** The slot list for the current pipeline execution. */
  private @Nullable List<ResponseSlot> slots;
  /** Whether ReadyForQuery has been received for the current pipeline. */
  private boolean receivedRFQ;

  PipelineResponseReader(PGStream pgStream, QueryExecutorImpl executor) {
    this.pgStream = pgStream;
    this.executor = executor;
  }

  /**
   * Read all responses for the given slots, blocking until ReadyForQuery is received.
   * If {@link #readAvailableResponses} was previously called with the same slot list,
   * this resumes from where it left off. Otherwise starts fresh.
   */
  void readResponses(List<ResponseSlot> slots) throws SQLException {
    if (this.slots != slots) {
      begin(slots);
    }
    if (receivedRFQ) {
      end();
      return;
    }
    try {
      while (!receivedRFQ) {
        processOneMessage();
      }
    } catch (IOException e) {
      failRemaining(new PSQLException(
          "An I/O error occurred while reading from the backend.",
          PSQLState.CONNECTION_FAILURE, e));
      throw new PSQLException(
          "An I/O error occurred while reading from the backend.",
          PSQLState.CONNECTION_FAILURE, e);
    } finally {
      end();
    }
  }

  /**
   * Non-blocking read: process any responses that are immediately available without blocking.
   * Uses a short SO_TIMEOUT to avoid blocking when no data is available.
   *
   * <p>Call this between sending chunks of queries during batch execution to drain
   * responses and keep the TCP window open.</p>
   *
   * @param slots the slot list (must be the same list across all calls for one pipeline)
   * @return the number of slots completed during this call
   */
  int readAvailableResponses(List<ResponseSlot> slots) throws SQLException {
    if (this.slots == null) {
      begin(slots);
    }
    int completedBefore = slotIndex;
    int savedTimeout;
    try {
      savedTimeout = pgStream.getNetworkTimeout();
    } catch (IOException e) {
      throw new PSQLException(
          "Failed to get network timeout",
          PSQLState.CONNECTION_FAILURE, e);
    }

    try {
      // Set a very short timeout for non-blocking behavior
      pgStream.setNetworkTimeout(1);

      // Read as many messages as are available
      while (!receivedRFQ && pgStream.hasMessagePending()) {
        processOneMessage();
      }
    } catch (SocketTimeoutException e) {
      // No more data available — this is expected
    } catch (IOException e) {
      failRemaining(new PSQLException(
          "An I/O error occurred while reading from the backend.",
          PSQLState.CONNECTION_FAILURE, e));
      throw new PSQLException(
          "An I/O error occurred while reading from the backend.",
          PSQLState.CONNECTION_FAILURE, e);
    } finally {
      try {
        pgStream.setNetworkTimeout(savedTimeout);
      } catch (IOException e) {
        // best effort restore
      }
    }
    return slotIndex - completedBefore;
  }

  /**
   * After non-blocking reads, block until all remaining responses arrive.
   * Call this after all queries have been sent and flushed.
   */
  void drainRemaining() throws SQLException {
    if (slots == null || receivedRFQ) {
      end();
      return;
    }
    try {
      while (!receivedRFQ) {
        processOneMessage();
      }
    } catch (IOException e) {
      failRemaining(new PSQLException(
          "An I/O error occurred while reading from the backend.",
          PSQLState.CONNECTION_FAILURE, e));
      throw new PSQLException(
          "An I/O error occurred while reading from the backend.",
          PSQLState.CONNECTION_FAILURE, e);
    } finally {
      end();
    }
  }

  /** Returns true if ReadyForQuery has been received. */
  boolean isComplete() {
    return receivedRFQ;
  }

  private void begin(List<ResponseSlot> slots) {
    this.slots = slots;
    this.slotIndex = 0;
    this.currentSlot = null;
    this.receivedRFQ = false;
  }

  private void end() {
    this.slots = null;
    this.currentSlot = null;
  }

  private void processOneMessage() throws IOException, SQLException {
    int c = pgStream.receiveChar();
    List<ResponseSlot> slots = this.slots;
    if (slots == null) {
      throw new IOException("processOneMessage called with no active slot list");
    }

    switch (c) {
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

      case PgMessageType.ROW_DESCRIPTION_RESPONSE: { // 'T'
        Field[] fields = receiveRowDescription();
        advanceSlot(slots);
        if (currentSlot != null && currentSlot.query != null) {
          currentSlot.fields = fields;
          currentSlot.query.setFields(fields);
        }
        break;
      }

      case PgMessageType.DATA_ROW_RESPONSE: { // 'D'
        Tuple tuple = receiveDataRow();
        advanceSlot(slots);
        if (currentSlot != null) {
          currentSlot.addTuple(tuple);
        }
        break;
      }

      case PgMessageType.COMMAND_COMPLETE_RESPONSE: { // 'C'
        String status = receiveCommandComplete();
        advanceSlot(slots);
        ResponseSlot slot = currentSlot;
        if (slot != null) {
          slot.commandStatus = status;
          commandCompleteParser.parse(status);
          slot.updateCount = commandCompleteParser.getRows();
          slot.insertOID = commandCompleteParser.getOid();
          if (!slot.asSimple) {
            slot.complete();
            currentSlot = null;
            slotIndex++;
          }
        }
        break;
      }

      case PgMessageType.EMPTY_QUERY_RESPONSE: { // 'I'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE EmptyQuery");
        advanceSlot(slots);
        if (currentSlot != null) {
          currentSlot.commandStatus = "EMPTY";
          currentSlot.complete();
          currentSlot = null;
          slotIndex++;
        }
        break;
      }

      case PgMessageType.PORTAL_SUSPENDED_RESPONSE: { // 's'
        pgStream.receiveInteger4();
        LOGGER.log(Level.FINEST, " <=BE PortalSuspended");
        if (currentSlot != null) {
          currentSlot.portalSuspended = true;
          currentSlot.complete();
          currentSlot = null;
          slotIndex++;
        }
        break;
      }

      case PgMessageType.ERROR_RESPONSE: { // 'E'
        PSQLException error = receiveError();
        advanceSlot(slots);
        if (currentSlot != null) {
          currentSlot.completeExceptionally(error);
          currentSlot = null;
          slotIndex++;
        }
        break;
      }

      case PgMessageType.NOTICE_RESPONSE: { // 'N'
        SQLWarning warning = receiveNotice();
        if (currentSlot != null) {
          currentSlot.addWarning(warning);
        } else {
          executor.addWarning(warning);
        }
        break;
      }

      case PgMessageType.PARAMETER_STATUS_RESPONSE: // 'S'
        executor.receiveParameterStatus();
        break;

      case PgMessageType.ASYNCHRONOUS_NOTICE: // 'A'
        receiveAsyncNotify();
        break;

      case PgMessageType.READY_FOR_QUERY_RESPONSE: { // 'Z'
        receiveReadyForQuery();
        // Complete current slot if still active (simple query case)
        if (currentSlot != null) {
          currentSlot.complete();
          currentSlot = null;
          slotIndex++;
        }
        // Fail any remaining slots (error cascading after server error)
        while (slotIndex < slots.size()) {
          ResponseSlot slot = slots.get(slotIndex);
          if (slot != ResponseSlot.SYNC_MARKER) {
            slot.completeExceptionally(new PSQLException(
                "Query discarded due to prior error in pipeline batch",
                PSQLState.IN_FAILED_SQL_TRANSACTION));
          }
          slotIndex++;
        }
        receivedRFQ = true;
        break;
      }

      default: {
        int len = pgStream.receiveInteger4();
        if (len > 4) {
          pgStream.receive(len - 4);
        }
        LOGGER.log(Level.WARNING, " <=BE Unknown message type: {0}", (char) c);
        break;
      }
    }
  }

  private void advanceSlot(List<ResponseSlot> slots) {
    if (currentSlot == null && slotIndex < slots.size()) {
      currentSlot = slots.get(slotIndex);
    }
  }

  private void failRemaining(PSQLException ex) {
    if (currentSlot != null) {
      currentSlot.completeExceptionally(ex);
      currentSlot = null;
      slotIndex++;
    }
    List<ResponseSlot> slots = this.slots;
    if (slots != null) {
      while (slotIndex < slots.size()) {
        ResponseSlot slot = slots.get(slotIndex);
        if (slot != ResponseSlot.SYNC_MARKER) {
          slot.completeExceptionally(ex);
        }
        slotIndex++;
      }
    }
  }

  private Field[] receiveRowDescription() throws IOException {
    pgStream.receiveInteger4(); // message size
    int size = pgStream.receiveInteger2();
    Field[] fields = new Field[size];

    for (int i = 0; i < fields.length; i++) {
      String columnLabel = pgStream.receiveCanonicalString();
      int tableOid = pgStream.receiveInteger4();
      short positionInTable = (short) pgStream.receiveInteger2();
      int typeOid = pgStream.receiveInteger4();
      short typeLength = (short) pgStream.receiveInteger2();
      int typeModifier = pgStream.receiveInteger4();
      short formatType = (short) pgStream.receiveInteger2();
      fields[i] = new Field(columnLabel, typeOid, typeLength, typeModifier, tableOid, positionInTable);
      fields[i].setFormat(formatType);
    }

    LOGGER.log(Level.FINEST, " <=BE RowDescription({0})", size);
    return fields;
  }

  private void receiveParameterDescription() throws IOException {
    pgStream.receiveInteger4(); // message size
    int numParams = pgStream.receiveInteger2();
    for (int i = 0; i < numParams; i++) {
      pgStream.receiveInteger4(); // type OID
    }
    LOGGER.log(Level.FINEST, " <=BE ParameterDescription({0})", numParams);
  }

  private Tuple receiveDataRow() throws IOException {
    try {
      Tuple tuple = pgStream.receiveTupleV3();
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.log(Level.FINEST, " <=BE DataRow(len={0})", tuple.length());
      }
      return tuple;
    } catch (OutOfMemoryError oome) {
      throw new IOException("Ran out of memory retrieving query results", oome);
    } catch (SQLException e) {
      throw new IOException("Error receiving data row", e);
    }
  }

  private String receiveCommandComplete() throws IOException {
    int len = pgStream.receiveInteger4();
    String status = pgStream.receiveString(len - 5);
    pgStream.receiveChar(); // trailing \0
    LOGGER.log(Level.FINEST, " <=BE CommandStatus({0})", status);
    return status;
  }

  private PSQLException receiveError() throws IOException {
    int elen = pgStream.receiveInteger4();
    ServerErrorMessage errorMsg = new ServerErrorMessage(
        pgStream.receiveErrorString(elen - 4));
    LOGGER.log(Level.FINEST, " <=BE ErrorMessage({0})", errorMsg.toString());
    return new PSQLException(errorMsg, executor.getLogServerErrorDetail());
  }

  private SQLWarning receiveNotice() throws IOException {
    int nlen = pgStream.receiveInteger4();
    ServerErrorMessage warnMsg = new ServerErrorMessage(pgStream.receiveErrorString(nlen - 4));
    LOGGER.log(Level.FINEST, " <=BE NoticeResponse({0})", warnMsg.toString());
    return new PSQLWarning(warnMsg);
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
  }
}
