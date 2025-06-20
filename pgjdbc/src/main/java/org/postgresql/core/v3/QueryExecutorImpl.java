/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGProperty;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyOperation;
import org.postgresql.copy.CopyOut;
import org.postgresql.core.CommandCompleteParser;
import org.postgresql.core.Encoding;
import org.postgresql.core.EncodingPredictor;
import org.postgresql.core.Field;
import org.postgresql.core.NativeQuery;
import org.postgresql.core.Notification;
import org.postgresql.core.Oid;
import org.postgresql.core.PGBindException;
import org.postgresql.core.PGStream;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Parser;
import org.postgresql.core.PgMessageType;
import org.postgresql.core.ProtocolVersion;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.QueryExecutorBase;
import org.postgresql.core.ReplicationProtocol;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.ResultHandlerBase;
import org.postgresql.core.ResultHandlerDelegate;
import org.postgresql.core.SqlCommand;
import org.postgresql.core.SqlCommandType;
import org.postgresql.core.TransactionState;
import org.postgresql.core.Tuple;
import org.postgresql.core.v3.adaptivefetch.AdaptiveFetchCache;
import org.postgresql.core.v3.replication.V3ReplicationProtocol;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.BatchResultHandler;
import org.postgresql.jdbc.ResourceLock;
import org.postgresql.jdbc.TimestampUtils;
import org.postgresql.util.ByteStreamWriter;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLWarning;
import org.postgresql.util.ServerErrorMessage;
import org.postgresql.util.internal.IntSet;
import org.postgresql.util.internal.SourceStreamIOException;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * QueryExecutor implementation for the V3 protocol.
 */
public class QueryExecutorImpl extends QueryExecutorBase {

  private static final Logger LOGGER = Logger.getLogger(QueryExecutorImpl.class.getName());

  private static final Field[] NO_FIELDS = new Field[0];

  static {
    //canonicalize commonly seen strings to reduce memory and speed comparisons
    Encoding.canonicalize("application_name");
    Encoding.canonicalize("client_encoding");
    Encoding.canonicalize("DateStyle");
    Encoding.canonicalize("integer_datetimes");
    Encoding.canonicalize("off");
    Encoding.canonicalize("on");
    Encoding.canonicalize("server_encoding");
    Encoding.canonicalize("server_version");
    Encoding.canonicalize("server_version_num");
    Encoding.canonicalize("standard_conforming_strings");
    Encoding.canonicalize("TimeZone");
    Encoding.canonicalize("UTF8");
    Encoding.canonicalize("UTF-8");
    Encoding.canonicalize("in_hot_standby");
  }

  /**
   * TimeZone of the current connection (TimeZone backend parameter).
   */
  private @Nullable TimeZone timeZone;

  /**
   * application_name connection property.
   */
  private @Nullable String applicationName;

  /**
   * True if server uses integers for date and time fields. False if server uses double.
   */
  private boolean integerDateTimes;

  /**
   * Bit set that has a bit set for each oid which should be received using binary format.
   */
  private final IntSet useBinaryReceiveForOids = new IntSet();

  /**
   * Bit set that has a bit set for each oid which should be sent using binary format.
   */
  private final IntSet useBinarySendForOids = new IntSet();

  /**
   * This is a fake query object so processResults can distinguish "ReadyForQuery" messages
   * from Sync messages vs from simple execute (aka 'Q').
   */
  @SuppressWarnings("method.invocation")
  private final SimpleQuery sync = (SimpleQuery) createQuery("SYNC", false, true).query;

  private short deallocateEpoch;

  /**
   * This caches the latest observed {@code set search_path} query so the reset of prepared
   * statement cache can be skipped if using repeated calls for the same {@code set search_path}
   * value.
   */
  private @Nullable String lastSetSearchPathQuery;

  /**
   * The exception that caused the last transaction to fail.
   */
  private @Nullable SQLException transactionFailCause;

  private final ReplicationProtocol replicationProtocol;

  /**
   * {@code CommandComplete(B)} messages are quite common, so we reuse instance to parse those
   */
  private final CommandCompleteParser commandCompleteParser = new CommandCompleteParser();

  private final AdaptiveFetchCache adaptiveFetchCache;

  @SuppressWarnings({"assignment", "argument",
      "method.invocation"})
  public QueryExecutorImpl(PGStream pgStream,
      int cancelSignalTimeout, Properties info) throws SQLException, IOException {
    super(pgStream, cancelSignalTimeout, info);

    long maxResultBuffer = pgStream.getMaxResultBuffer();
    this.adaptiveFetchCache = new AdaptiveFetchCache(maxResultBuffer, info);

    this.allowEncodingChanges = PGProperty.ALLOW_ENCODING_CHANGES.getBoolean(info);
    this.cleanupSavePoints = PGProperty.CLEANUP_SAVEPOINTS.getBoolean(info);
    // assignment, argument
    this.replicationProtocol = new V3ReplicationProtocol(this, pgStream);
    readStartupMessages();
  }

  @Override
  public ProtocolVersion getProtocolVersion() {
    return protocolVersion;
  }

  /**
   * Supplement to synchronization of public methods on current QueryExecutor.
   *
   * <p>Necessary for keeping the connection intact between calls to public methods sharing a state
   * such as COPY subprotocol. waitOnLock() must be called at beginning of each connection access
   * point.</p>
   *
   * <p>Public methods sharing that state must then be synchronized among themselves. Normal method
   * synchronization typically suffices for that.</p>
   *
   * <p>See notes on related methods as well as currentCopy() below.</p>
   */
  private @Nullable Object lockedFor;

  /**
   * Obtain lock over this connection for given object, blocking to wait if necessary.
   *
   * @param obtainer object that gets the lock. Normally current thread.
   * @throws PSQLException when already holding the lock or getting interrupted.
   */
  private void lock(Object obtainer) throws PSQLException {
    if (lockedFor == obtainer) {
      throw new PSQLException(GT.tr("Tried to obtain lock while already holding it"),
          PSQLState.OBJECT_NOT_IN_STATE);

    }
    waitOnLock();
    lockedFor = obtainer;
  }

  /**
   * Release lock on this connection presumably held by given object.
   *
   * @param holder object that holds the lock. Normally current thread.
   * @throws PSQLException when this thread does not hold the lock
   */
  private void unlock(Object holder) throws PSQLException {
    if (lockedFor != holder) {
      throw new PSQLException(GT.tr("Tried to break lock on database connection"),
          PSQLState.OBJECT_NOT_IN_STATE);
    }
    lockedFor = null;
    lockCondition.signal();
  }

  /**
   * Wait until our lock is released. Execution of a single synchronized method can then continue
   * without further ado. Must be called at beginning of each synchronized public method.
   */
  private void waitOnLock() throws PSQLException {
    while (lockedFor != null) {
      try {
        lockCondition.await();
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new PSQLException(
            GT.tr("Interrupted while waiting to obtain lock on database connection"),
            PSQLState.OBJECT_NOT_IN_STATE, ie);
      }
    }
  }

  /**
   * @param holder object assumed to hold the lock
   * @return whether given object actually holds the lock
   */
  boolean hasLockOn(@Nullable Object holder) {
    try (ResourceLock ignore = lock.obtain()) {
      return lockedFor == holder;
    }
  }

  /**
   * @param holder object assumed to hold the lock
   * @return whether given object actually holds the lock
   */
  private boolean hasLock(@Nullable Object holder) {
    return lockedFor == holder;
  }

  //
  // Query parsing
  //

  @Override
  public Query createSimpleQuery(String sql) throws SQLException {
    List<NativeQuery> queries = Parser.parseJdbcSql(sql,
        getStandardConformingStrings(), false, true,
        isReWriteBatchedInsertsEnabled(), getQuoteReturningIdentifiers());
    return wrap(queries);
  }

  @Override
  public Query wrap(List<NativeQuery> queries) {
    if (queries.isEmpty()) {
      // Empty query
      return emptyQuery;
    }
    if (queries.size() == 1) {
      NativeQuery firstQuery = queries.get(0);
      if (isReWriteBatchedInsertsEnabled()
          && firstQuery.getCommand().isBatchedReWriteCompatible()) {
        int valuesBraceOpenPosition =
            firstQuery.getCommand().getBatchRewriteValuesBraceOpenPosition();
        int valuesBraceClosePosition =
            firstQuery.getCommand().getBatchRewriteValuesBraceClosePosition();
        return new BatchedQuery(firstQuery, this, valuesBraceOpenPosition,
            valuesBraceClosePosition, isColumnSanitiserDisabled());
      } else {
        return new SimpleQuery(firstQuery, this, isColumnSanitiserDisabled());
      }
    }

    // Multiple statements.
    SimpleQuery[] subqueries = new SimpleQuery[queries.size()];
    int[] offsets = new int[subqueries.length];
    int offset = 0;
    for (int i = 0; i < queries.size(); i++) {
      NativeQuery nativeQuery = queries.get(i);
      offsets[i] = offset;
      subqueries[i] = new SimpleQuery(nativeQuery, this, isColumnSanitiserDisabled());
      offset += nativeQuery.bindPositions.length;
    }

    return new CompositeQuery(subqueries, offsets);
  }

  //
  // Query execution
  //

  private int updateQueryMode(int flags) {
    switch (getPreferQueryMode()) {
      case SIMPLE:
        return flags | QUERY_EXECUTE_AS_SIMPLE;
      default:
        return flags;
    }
  }

  @Override
  public void execute(Query query, @Nullable ParameterList parameters,
      ResultHandler handler,
      int maxRows, int fetchSize, int flags) throws SQLException {
    execute(query, parameters, handler, maxRows, fetchSize, flags, false);
  }

  @Override
  public void execute(Query query, @Nullable ParameterList parameters,
      ResultHandler handler,
      int maxRows, int fetchSize, int flags, boolean adaptiveFetch) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      waitOnLock();
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.log(Level.FINEST, "  simple execute, handler={0}, maxRows={1}, fetchSize={2}, flags={3}",
            new Object[]{handler, maxRows, fetchSize, flags});
      }

      if (parameters == null) {
        parameters = SimpleQuery.NO_PARAMETERS;
      }

      flags = updateQueryMode(flags);

      boolean describeOnly = (QUERY_DESCRIBE_ONLY & flags) != 0;

      ((V3ParameterList) parameters).convertFunctionOutParameters();

      // Check parameters are all set..
      if (!describeOnly) {
        ((V3ParameterList) parameters).checkAllParametersSet();
      }

      boolean autosave = false;
      try {
        try {
          handler = sendQueryPreamble(handler, flags);
          autosave = sendAutomaticSavepoint(query, flags);
          sendQuery(query, (V3ParameterList) parameters, maxRows, fetchSize, flags,
              handler, null, adaptiveFetch);
          if ((flags & QueryExecutor.QUERY_EXECUTE_AS_SIMPLE) != 0) {
            // Sync message is not required for 'Q' execution as 'Q' ends with ReadyForQuery message
            // on its own
          } else {
            sendSync();
          }
          processResults(handler, flags, adaptiveFetch);
          estimatedReceiveBufferBytes = 0;
        } catch (PGBindException se) {
          // There are three causes of this error, an
          // invalid total Bind message length, a
          // BinaryStream that cannot provide the amount
          // of data claimed by the length argument, and
          // a BinaryStream that throws an Exception
          // when reading.
          //
          // We simply do not send the Execute message
          // so we can just continue on as if nothing
          // has happened. Perhaps we need to
          // introduce an error here to force the
          // caller to rollback if there is a
          // transaction in progress?
          //
          sendSync();
          processResults(handler, flags, adaptiveFetch);
          estimatedReceiveBufferBytes = 0;
          handler
              .handleError(new PSQLException(GT.tr("Unable to bind parameter values for statement."),
                  PSQLState.INVALID_PARAMETER_VALUE, se.getIOException()));
        }
      } catch (IOException e) {
        abort();
        handler.handleError(
            new PSQLException(GT.tr("An I/O error occurred while sending to the backend."),
                PSQLState.CONNECTION_FAILURE, e));
      }

      try {
        handler.handleCompletion();
        if (cleanupSavePoints) {
          releaseSavePoint(autosave);
        }
      } catch (SQLException e) {
        rollbackIfRequired(autosave, e);
      }
    }
  }

  private boolean sendAutomaticSavepoint(Query query, int flags) throws IOException {
    if (((flags & QueryExecutor.QUERY_SUPPRESS_BEGIN) == 0
        || getTransactionState() == TransactionState.OPEN)
        && query != restoreToAutoSave
        && !"COMMIT".equalsIgnoreCase(query.getNativeSql())
        && getAutoSave() != AutoSave.NEVER
        // If query has no resulting fields, it cannot fail with 'cached plan must not change result type'
        // thus no need to set a savepoint before such query
        && (getAutoSave() == AutoSave.ALWAYS
        // If CompositeQuery is observed, just assume it might fail and set the savepoint
        || !(query instanceof SimpleQuery)
        || ((SimpleQuery) query).getFields() != null)) {

      /*
      create a different SAVEPOINT the first time so that all subsequent SAVEPOINTS can be released
      easily. There have been reports of server resources running out if there are too many
      SAVEPOINTS.
       */
      sendOneQuery(autoSaveQuery, SimpleQuery.NO_PARAMETERS, 1, 0,
          QUERY_NO_RESULTS | QUERY_NO_METADATA
              // PostgreSQL does not support bind, exec, simple, sync message flow,
              // so we force autosavepoint to use simple if the main query is using simple
              | QUERY_EXECUTE_AS_SIMPLE);
      return true;
    }
    return false;
  }

  private void releaseSavePoint(boolean autosave) throws SQLException {
    if ( autosave
        && getAutoSave() == AutoSave.ALWAYS
        && getTransactionState() == TransactionState.OPEN) {
      try {
        sendOneQuery(releaseAutoSave, SimpleQuery.NO_PARAMETERS, 1, 0,
            QUERY_NO_RESULTS | QUERY_NO_METADATA
                | QUERY_EXECUTE_AS_SIMPLE);

      } catch (IOException ex) {
        throw  new PSQLException(GT.tr("Error releasing savepoint"), PSQLState.IO_ERROR);
      }
    }
  }

  private void rollbackIfRequired(boolean autosave, SQLException e) throws SQLException {
    if (autosave
        && getTransactionState() == TransactionState.FAILED
        && (getAutoSave() == AutoSave.ALWAYS || willHealOnRetry(e))) {
      try {
        // ROLLBACK and AUTOSAVE are executed as simple always to overcome "statement no longer exists S_xx"
        execute(restoreToAutoSave, SimpleQuery.NO_PARAMETERS, new ResultHandlerDelegate(null),
            1, 0, QUERY_NO_RESULTS | QUERY_NO_METADATA | QUERY_EXECUTE_AS_SIMPLE);
      } catch (SQLException e2) {
        // That's O(N), sorry
        e.setNextException(e2);
      }
    }
    throw e;
  }

  // Deadlock avoidance:
  //
  // It's possible for the send and receive streams to get "deadlocked" against each other since
  // we do not have a separate thread. The scenario is this: we have two streams:
  //
  // driver -> TCP buffering -> server
  // server -> TCP buffering -> driver
  //
  // The server behaviour is roughly:
  // while true:
  // read message
  // execute message
  // write results
  //
  // If the server -> driver stream has a full buffer, the write will block.
  // If the driver is still writing when this happens, and the driver -> server
  // stream also fills up, we deadlock: the driver is blocked on write() waiting
  // for the server to read some more data, and the server is blocked on write()
  // waiting for the driver to read some more data.
  //
  // To avoid this, we guess at how much response data we can request from the
  // server before the server -> driver stream's buffer is full (MAX_BUFFERED_RECV_BYTES).
  // This is the point where the server blocks on write and stops reading data. If we
  // reach this point, we force a Sync message and read pending data from the server
  // until ReadyForQuery, then go back to writing more queries unless we saw an error.
  //
  // This is not 100% reliable -- it's only done in the batch-query case and only
  // at a reasonably high level (per query, not per message), and it's only an estimate
  // -- so it might break. To do it correctly in all cases would seem to require a
  // separate send or receive thread as we can only do the Sync-and-read-results
  // operation at particular points, and also as we don't really know how much data
  // the server is sending.
  //
  // Our message size estimation is coarse, and disregards asynchronous
  // notifications, warnings/info/debug messages, etc, so the response size may be
  // quite different from the 250 bytes assumed here even for queries that don't
  // return data.
  //
  // See github issue #194 and #195 .
  //
  // Assume 64k server->client buffering, which is extremely conservative. A typical
  // system will have 200kb or more of buffers for its receive buffers, and the sending
  // system will typically have the same on the send side, giving us 400kb or to work
  // with. (We could check Java's receive buffer size, but prefer to assume a very
  // conservative buffer instead, and we don't know how big the server's send
  // buffer is.)
  //
  private static final int MAX_BUFFERED_RECV_BYTES = 64000;
  private static final int NODATA_QUERY_RESPONSE_SIZE_BYTES = 250;

  @Override
  public void execute(Query[] queries, @Nullable ParameterList[] parameterLists,
      BatchResultHandler batchHandler, int maxRows, int fetchSize, int flags) throws SQLException {
    execute(queries, parameterLists, batchHandler, maxRows, fetchSize, flags, false);
  }

  @Override
  public void execute(Query[] queries, @Nullable ParameterList[] parameterLists,
      BatchResultHandler batchHandler, int maxRows, int fetchSize, int flags, boolean adaptiveFetch)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      waitOnLock();
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.log(Level.FINEST, "  batch execute {0} queries, handler={1}, maxRows={2}, fetchSize={3}, flags={4}",
            new Object[]{queries.length, batchHandler, maxRows, fetchSize, flags});
      }

      flags = updateQueryMode(flags);

      boolean describeOnly = (QUERY_DESCRIBE_ONLY & flags) != 0;
      // Check parameters and resolve OIDs.
      if (!describeOnly) {
        for (ParameterList parameterList : parameterLists) {
          if (parameterList != null) {
            ((V3ParameterList) parameterList).checkAllParametersSet();
          }
        }
      }

      boolean autosave = false;
      ResultHandler handler = batchHandler;
      try {
        handler = sendQueryPreamble(batchHandler, flags);
        autosave = sendAutomaticSavepoint(queries[0], flags);
        estimatedReceiveBufferBytes = 0;

        for (int i = 0; i < queries.length; i++) {
          Query query = queries[i];
          V3ParameterList parameters = (V3ParameterList) parameterLists[i];
          if (parameters == null) {
            parameters = SimpleQuery.NO_PARAMETERS;
          }

          sendQuery(query, parameters, maxRows, fetchSize, flags, handler, batchHandler, adaptiveFetch);

          if (handler.getException() != null) {
            break;
          }
        }

        if (handler.getException() == null) {
          if ((flags & QueryExecutor.QUERY_EXECUTE_AS_SIMPLE) != 0) {
            // Sync message is not required for 'Q' execution as 'Q' ends with ReadyForQuery message
            // on its own
          } else {
            sendSync();
          }
          processResults(handler, flags, adaptiveFetch);
          estimatedReceiveBufferBytes = 0;
        }
      } catch (IOException e) {
        abort();
        handler.handleError(
            new PSQLException(GT.tr("An I/O error occurred while sending to the backend."),
                PSQLState.CONNECTION_FAILURE, e));
      }

      try {
        handler.handleCompletion();
        if (cleanupSavePoints) {
          releaseSavePoint(autosave);
        }
      } catch (SQLException e) {
        rollbackIfRequired(autosave, e);
      }
    }
  }

  private ResultHandler sendQueryPreamble(final ResultHandler delegateHandler, int flags)
      throws IOException {
    // First, send CloseStatements for finalized SimpleQueries that had statement names assigned.
    processDeadParsedQueries();
    processDeadPortals();

    // Send BEGIN on first statement in transaction.
    if ((flags & QueryExecutor.QUERY_SUPPRESS_BEGIN) != 0
        || getTransactionState() != TransactionState.IDLE) {
      return delegateHandler;
    }

    int beginFlags = QueryExecutor.QUERY_NO_METADATA;
    if ((flags & QueryExecutor.QUERY_ONESHOT) != 0) {
      beginFlags |= QueryExecutor.QUERY_ONESHOT;
    }

    beginFlags |= QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;

    beginFlags = updateQueryMode(beginFlags);

    final SimpleQuery beginQuery = (flags & QueryExecutor.QUERY_READ_ONLY_HINT) == 0 ? beginTransactionQuery : beginReadOnlyTransactionQuery;

    sendOneQuery(beginQuery, SimpleQuery.NO_PARAMETERS, 0, 0, beginFlags);

    // Insert a handler that intercepts the BEGIN.
    return new ResultHandlerDelegate(delegateHandler) {
      private boolean sawBegin = false;

      @Override
      public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
          @Nullable ResultCursor cursor) {
        if (sawBegin) {
          super.handleResultRows(fromQuery, fields, tuples, cursor);
        }
      }

      @Override
      public void handleCommandStatus(String status, long updateCount, long insertOID) {
        if (!sawBegin) {
          sawBegin = true;
          if (!"BEGIN".equals(status)) {
            handleError(new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                PSQLState.PROTOCOL_VIOLATION));
          }
        } else {
          super.handleCommandStatus(status, updateCount, insertOID);
        }
      }
    };
  }

  //
  // Fastpath
  //

  @Override
  @SuppressWarnings("deprecation")
  public byte @Nullable [] fastpathCall(int fnid, ParameterList parameters,
      boolean suppressBegin)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      waitOnLock();
      if (!suppressBegin) {
        doSubprotocolBegin();
      }
      try {
        sendFastpathCall(fnid, (SimpleParameterList) parameters);
        return receiveFastpathResult();
      } catch (IOException ioe) {
        abort();
        throw new PSQLException(GT.tr("An I/O error occurred while sending to the backend."),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }
  }

  public void doSubprotocolBegin() throws SQLException {
    if (getTransactionState() == TransactionState.IDLE) {

      LOGGER.log(Level.FINEST, "Issuing BEGIN before fastpath or copy call.");

      ResultHandler handler = new ResultHandlerBase() {
        private boolean sawBegin = false;

        @Override
        public void handleCommandStatus(String status, long updateCount, long insertOID) {
          if (!sawBegin) {
            if (!"BEGIN".equals(status)) {
              handleError(
                  new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                      PSQLState.PROTOCOL_VIOLATION));
            }
            sawBegin = true;
          } else {
            handleError(new PSQLException(GT.tr("Unexpected command status: {0}.", status),
                PSQLState.PROTOCOL_VIOLATION));
          }
        }

        @Override
        public void handleWarning(SQLWarning warning) {
          // we don't want to ignore warnings and it would be tricky
          // to chain them back to the connection, so since we don't
          // expect to get them in the first place, we just consider
          // them errors.
          handleError(warning);
        }
      };

      try {
        /* Send BEGIN with simple protocol preferred */
        int beginFlags = QueryExecutor.QUERY_NO_METADATA
                         | QueryExecutor.QUERY_ONESHOT
                         | QueryExecutor.QUERY_EXECUTE_AS_SIMPLE;
        beginFlags = updateQueryMode(beginFlags);
        sendOneQuery(beginTransactionQuery, SimpleQuery.NO_PARAMETERS, 0, 0, beginFlags);
        sendSync();
        processResults(handler, 0);
        estimatedReceiveBufferBytes = 0;
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("An I/O error occurred while sending to the backend."),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }

  }

  @Override
  @SuppressWarnings("deprecation")
  public ParameterList createFastpathParameters(int count) {
    return new SimpleParameterList(count, this);
  }

  private void sendFastpathCall(int fnid, SimpleParameterList params)
      throws SQLException, IOException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " FE=> FunctionCall({0}, {1} params)", new Object[]{fnid, params.getParameterCount()});
    }

    //
    // Total size = 4 (length)
    // + 4 (function OID)
    // + 2 (format code count) + N * 2 (format codes)
    // + 2 (parameter count) + encodedSize (parameters)
    // + 2 (result format)

    int paramCount = params.getParameterCount();
    int encodedSize = 0;
    for (int i = 1; i <= paramCount; i++) {
      if (params.isNull(i)) {
        encodedSize += 4;
      } else {
        encodedSize += 4 + params.getV3Length(i);
      }
    }

    pgStream.sendChar(PgMessageType.FUNCTION_CALL_REQ);
    pgStream.sendInteger4(4 + 4 + 2 + 2 * paramCount + 2 + encodedSize + 2);
    pgStream.sendInteger4(fnid);
    pgStream.sendInteger2(paramCount);
    for (int i = 1; i <= paramCount; i++) {
      pgStream.sendInteger2(params.isBinary(i) ? 1 : 0);
    }
    pgStream.sendInteger2(paramCount);
    for (int i = 1; i <= paramCount; i++) {
      if (params.isNull(i)) {
        pgStream.sendInteger4(-1);
      } else {
        pgStream.sendInteger4(params.getV3Length(i)); // Parameter size
        params.writeV3Value(i, pgStream);
      }
    }
    pgStream.sendInteger2(1); // Binary result format
    pgStream.flush();
  }

  // Just for API compatibility with previous versions.
  @Override
  public void processNotifies() throws SQLException {
    processNotifies(-1);
  }

  /**
   * @param timeoutMillis when &gt; 0, block for this time
   *                      when =0, block forever
   *                      when &lt; 0, don't block
   */
  @Override
  public void processNotifies(int timeoutMillis) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      waitOnLock();
      // Asynchronous notifies only arrive when we are not in a transaction
      if (getTransactionState() != TransactionState.IDLE) {
        return;
      }

      if (hasNotifications()) {
        // No need to timeout when there are already notifications. We just check for more in this case.
        timeoutMillis = -1;
      }

      boolean useTimeout = timeoutMillis > 0;
      long startTime = 0;
      int oldTimeout = 0;
      if (useTimeout) {
        startTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        try {
          oldTimeout = pgStream.getSocket().getSoTimeout();
        } catch (SocketException e) {
          throw new PSQLException(GT.tr("An error occurred while trying to get the socket "
              + "timeout."), PSQLState.CONNECTION_FAILURE, e);
        }
      }

      try {
        while (timeoutMillis >= 0 || pgStream.hasMessagePending()) {
          if (useTimeout && timeoutMillis >= 0) {
            setSocketTimeout(timeoutMillis);
          }
          int c = pgStream.receiveChar();
          if (useTimeout && timeoutMillis >= 0) {
            setSocketTimeout(0); // Don't timeout after first char
          }
          switch (c) {
            case 'A': // Asynchronous Notify
              receiveAsyncNotify();
              timeoutMillis = -1;
              continue;
            case 'E':
              // Error Response (response to pretty much everything; backend then skips until Sync)
              throw receiveErrorResponse();
            case 'N': // Notice Response (warnings / info)
              SQLWarning warning = receiveNoticeResponse();
              addWarning(warning);
              if (useTimeout) {
                long newTimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
                timeoutMillis += (int) (startTime - newTimeMillis); // Overflows after 49 days, ignore that
                startTime = newTimeMillis;
                if (timeoutMillis == 0) {
                  timeoutMillis = -1; // Don't accidentally wait forever
                }
              }
              break;
            default:
              throw new PSQLException(GT.tr("Unknown Response Type {0}.", (char) c),
                  PSQLState.CONNECTION_FAILURE);
          }
        }
      } catch (SocketTimeoutException ioe) {
        // No notifications this time...
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("An I/O error occurred while sending to the backend."),
            PSQLState.CONNECTION_FAILURE, ioe);
      } finally {
        if (useTimeout) {
          setSocketTimeout(oldTimeout);
        }
      }
    }
  }

  private void setSocketTimeout(int millis) throws PSQLException {
    try {
      Socket s = pgStream.getSocket();
      if (!s.isClosed()) { // Is this check required?
        pgStream.setNetworkTimeout(millis);
      }
    } catch (IOException e) {
      throw new PSQLException(GT.tr("An error occurred while trying to reset the socket timeout."),
        PSQLState.CONNECTION_FAILURE, e);
    }
  }

  private byte @Nullable [] receiveFastpathResult() throws IOException, SQLException {
    boolean endQuery = false;
    SQLException error = null;
    byte[] returnValue = null;

    while (!endQuery) {
      int c = pgStream.receiveChar();
      switch (c) {
        case PgMessageType.ASYNCHRONOUS_NOTICE:
          receiveAsyncNotify();
          break;

        case PgMessageType.ERROR_RESPONSE:
          // response to pretty much everything; backend then skips until Sync
          SQLException newError = receiveErrorResponse();
          if (error == null) {
            error = newError;
          } else {
            error.setNextException(newError);
          }
          // keep processing
          break;

        case PgMessageType.NOTICE_RESPONSE: // warnings / info
          SQLWarning warning = receiveNoticeResponse();
          addWarning(warning);
          break;

        case PgMessageType.READY_FOR_QUERY_RESPONSE: // eventual response to Sync
          receiveRFQ();
          endQuery = true;
          break;

        case PgMessageType.FUNCTION_CALL_RESPONSE:
          @SuppressWarnings("unused")
          int msgLen = pgStream.receiveInteger4();
          int valueLen = pgStream.receiveInteger4();

          LOGGER.log(Level.FINEST, " <=BE FunctionCallResponse({0} bytes)", valueLen);

          if (valueLen != -1) {
            byte[] buf = new byte[valueLen];
            pgStream.receive(buf, 0, valueLen);
            returnValue = buf;
          }

          break;

        case PgMessageType.PARAMETER_STATUS_RESPONSE: // Parameter Status
          try {
            receiveParameterStatus();
          } catch (SQLException e) {
            if (error == null) {
              error = e;
            } else {
              error.setNextException(e);
            }
            endQuery = true;
          }
          break;

        default:
          throw new PSQLException(GT.tr("Unknown Response Type {0}.", (char) c),
              PSQLState.CONNECTION_FAILURE);
      }

    }

    // did we get an error during this query?
    if (error != null) {
      throw error;
    }

    return returnValue;
  }

  //
  // Copy subprotocol implementation
  //

  /**
   * Sends given query to BE to start, initialize and lock connection for a CopyOperation.
   *
   * @param sql COPY FROM STDIN / COPY TO STDOUT statement
   * @return CopyIn or CopyOut operation object
   * @throws SQLException on failure
   */
  @Override
  public CopyOperation startCopy(String sql, boolean suppressBegin)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      waitOnLock();
      if (!suppressBegin) {
        doSubprotocolBegin();
      }
      byte[] buf = sql.getBytes(StandardCharsets.UTF_8);

      try {
        LOGGER.log(Level.FINEST, " FE=> Query(CopyStart)");

        pgStream.sendChar(PgMessageType.QUERY_REQUEST);
        pgStream.sendInteger4(buf.length + 4 + 1);
        pgStream.send(buf);
        pgStream.sendChar(0);
        pgStream.flush();

        return castNonNull(processCopyResults(null, true));
        // expect a CopyInResponse or CopyOutResponse to our query above
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("Database connection failed when starting copy"),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }
  }

  /**
   * Locks connection and calls initializer for a new CopyOperation Called via startCopy ->
   * processCopyResults.
   *
   * @param op an uninitialized CopyOperation
   * @throws SQLException on locking failure
   * @throws IOException on database connection failure
   */
  private void initCopy(CopyOperationImpl op) throws SQLException, IOException {
    try (ResourceLock ignore = lock.obtain()) {
      pgStream.receiveInteger4(); // length not used
      int rowFormat = pgStream.receiveChar();
      int numFields = pgStream.receiveInteger2();
      int[] fieldFormats = new int[numFields];

      for (int i = 0; i < numFields; i++) {
        fieldFormats[i] = pgStream.receiveInteger2();
      }

      lock(op);
      op.init(this, rowFormat, fieldFormats);
    }
  }

  /**
   * Finishes a copy operation and unlocks connection discarding any exchanged data.
   *
   * @param op the copy operation presumably currently holding lock on this connection
   * @throws SQLException on any additional failure
   */
  public void cancelCopy(CopyOperationImpl op) throws SQLException {
    if (!hasLock(op)) {
      throw new PSQLException(GT.tr("Tried to cancel an inactive copy operation"),
          PSQLState.OBJECT_NOT_IN_STATE);
    }

    SQLException error = null;
    int errors = 0;

    try {
      if (op instanceof CopyIn) {
        try (ResourceLock ignore = lock.obtain()) {
          LOGGER.log(Level.FINEST, "FE => CopyFail");
          final byte[] msg = "Copy cancel requested".getBytes(StandardCharsets.US_ASCII);
          pgStream.sendChar(PgMessageType.COPY_FAIL); // CopyFail
          pgStream.sendInteger4(5 + msg.length);
          pgStream.send(msg);
          pgStream.sendChar(0);
          pgStream.flush();
          do {
            try {
              processCopyResults(op, true); // discard rest of input
            } catch (SQLException se) { // expected error response to failing copy
              errors++;
              if (error != null) {
                SQLException e = se;
                SQLException next;
                while ((next = e.getNextException()) != null) {
                  e = next;
                }
                e.setNextException(error);
              }
              error = se;
            }
          } while (hasLock(op));
        }
      } else if (op instanceof CopyOut) {
        sendQueryCancel();
      }

    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Database connection failed when canceling copy operation"),
          PSQLState.CONNECTION_FAILURE, ioe);
    } finally {
      // Need to ensure the lock isn't held anymore, or else
      // future operations, rather than failing due to the
      // broken connection, will simply hang waiting for this
      // lock.
      try (ResourceLock ignore = lock.obtain()) {
        if (hasLock(op)) {
          unlock(op);
        }
      }
    }

    if (op instanceof CopyIn) {
      if (errors < 1) {
        throw new PSQLException(GT.tr("Missing expected error response to copy cancel request"),
            PSQLState.COMMUNICATION_ERROR);
      } else if (errors > 1) {
        throw new PSQLException(
            GT.tr("Got {0} error responses to single copy cancel request", String.valueOf(errors)),
            PSQLState.COMMUNICATION_ERROR, error);
      }
    }
  }

  /**
   * Finishes writing to copy and unlocks connection.
   *
   * @param op the copy operation presumably currently holding lock on this connection
   * @return number of rows updated for server versions 8.2 or newer
   * @throws SQLException on failure
   */
  public long endCopy(CopyOperationImpl op) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!hasLock(op)) {
        throw new PSQLException(GT.tr("Tried to end inactive copy"), PSQLState.OBJECT_NOT_IN_STATE);
      }

      try {
        LOGGER.log(Level.FINEST, " FE=> CopyDone");

        pgStream.sendChar(PgMessageType.COPY_DONE); // CopyDone
        pgStream.sendInteger4(4);
        pgStream.flush();

        do {
          processCopyResults(op, true);
        } while (hasLock(op));
        return op.getHandledRowCount();
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("Database connection failed when ending copy"),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }
  }

  /**
   * Sends data during a live COPY IN operation. Only unlocks the connection if server suddenly
   * returns CommandComplete, which should not happen
   *
   * @param op the CopyIn operation presumably currently holding lock on this connection
   * @param data bytes to send
   * @param off index of first byte to send (usually 0)
   * @param siz number of bytes to send (usually data.length)
   * @throws SQLException on failure
   */
  public void writeToCopy(CopyOperationImpl op, byte[] data, int off, int siz)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!hasLock(op)) {
        throw new PSQLException(GT.tr("Tried to write to an inactive copy operation"),
            PSQLState.OBJECT_NOT_IN_STATE);
      }

      LOGGER.log(Level.FINEST, " FE=> CopyData({0})", siz);

      try {
        pgStream.sendChar(PgMessageType.COPY_DATA);
        pgStream.sendInteger4(siz + 4);
        pgStream.send(data, off, siz);
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("Database connection failed when writing to copy"),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }
  }

  /**
   * Sends data during a live COPY IN operation. Only unlocks the connection if server suddenly
   * returns CommandComplete, which should not happen
   *
   * @param op   the CopyIn operation presumably currently holding lock on this connection
   * @param from the source of bytes, e.g. a ByteBufferByteStreamWriter
   * @throws SQLException on failure
   */
  public void writeToCopy(CopyOperationImpl op, ByteStreamWriter from)
      throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!hasLock(op)) {
        throw new PSQLException(GT.tr("Tried to write to an inactive copy operation"),
            PSQLState.OBJECT_NOT_IN_STATE);
      }

      int siz = from.getLength();
      LOGGER.log(Level.FINEST, " FE=> CopyData({0})", siz);

      try {
        pgStream.sendChar(PgMessageType.COPY_DATA);
        pgStream.sendInteger4(siz + 4);
        pgStream.send(from);
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("Database connection failed when writing to copy"),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }
  }

  public void flushCopy(CopyOperationImpl op) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!hasLock(op)) {
        throw new PSQLException(GT.tr("Tried to write to an inactive copy operation"),
            PSQLState.OBJECT_NOT_IN_STATE);
      }

      try {
        pgStream.flush();
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("Database connection failed when writing to copy"),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }
  }

  /**
   * Wait for a row of data to be received from server on an active copy operation
   * Connection gets unlocked by processCopyResults() at end of operation.
   *
   * @param op the copy operation presumably currently holding lock on this connection
   * @param block whether to block waiting for input
   * @throws SQLException on any failure
   */
  void readFromCopy(CopyOperationImpl op, boolean block) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      if (!hasLock(op)) {
        throw new PSQLException(GT.tr("Tried to read from inactive copy"),
            PSQLState.OBJECT_NOT_IN_STATE);
      }

      try {
        processCopyResults(op, block); // expect a call to handleCopydata() to store the data
      } catch (IOException ioe) {
        throw new PSQLException(GT.tr("Database connection failed when reading from copy"),
            PSQLState.CONNECTION_FAILURE, ioe);
      }
    }
  }

  AtomicBoolean processingCopyResults = new AtomicBoolean(false);

  /**
   * Handles copy sub protocol responses from server. Unlocks at end of sub protocol, so operations
   * on pgStream or QueryExecutor are not allowed in a method after calling this!
   *
   * @param block whether to block waiting for input
   * @return CopyIn when COPY FROM STDIN starts; CopyOut when COPY TO STDOUT starts; null when copy
   *         ends; otherwise, the operation given as parameter.
   * @throws SQLException in case of misuse
   * @throws IOException from the underlying connection
   */
  @Nullable CopyOperationImpl processCopyResults(@Nullable CopyOperationImpl op, boolean block)
      throws SQLException, IOException {

    /*
    * fixes issue #1592 where one thread closes the stream and another is reading it
     */
    if (pgStream.isClosed()) {
      throw new PSQLException(GT.tr("PGStream is closed"),
          PSQLState.CONNECTION_DOES_NOT_EXIST);
    }
    /*
    *  This is a hack as we should not end up here, but sometimes do with large copy operations.
     */
    if (!processingCopyResults.compareAndSet(false, true)) {
      LOGGER.log(Level.INFO, "Ignoring request to process copy results, already processing");
      return null;
    }

    // put this all in a try, finally block and reset the processingCopyResults in the finally clause
    try {
      boolean endReceiving = false;
      SQLException error = null;
      SQLException errors = null;
      int len;

      while (!endReceiving && (block || pgStream.hasMessagePending())) {

        // There is a bug in the server's implementation of the copy
        // protocol. It returns command complete immediately upon
        // receiving the EOF marker in the binary protocol,
        // potentially before we've issued CopyDone. When we are not
        // blocking, we don't think we are done, so we hold off on
        // processing command complete and any subsequent messages
        // until we actually are done with the copy.
        //
        if (!block) {
          int c = pgStream.peekChar();
          if (c == PgMessageType.COMMAND_COMPLETE_RESPONSE) {
            LOGGER.log(Level.FINEST, " <=BE CommandStatus, Ignored until CopyDone");
            break;
          }
        }

        int c = pgStream.receiveChar();
        switch (c) {

          case PgMessageType.ASYNCHRONOUS_NOTICE:
            LOGGER.log(Level.FINEST, " <=BE Asynchronous Notification while copying");

            receiveAsyncNotify();
            break;

          case PgMessageType.NOTICE_RESPONSE:

            LOGGER.log(Level.FINEST, " <=BE Notification while copying");

            addWarning(receiveNoticeResponse());
            break;

          case PgMessageType.COMMAND_COMPLETE_RESPONSE: // Command Complete

            String status = receiveCommandStatus();

            try {
              if (op == null) {
                throw new PSQLException(GT
                    .tr("Received CommandComplete ''{0}'' without an active copy operation", status),
                    PSQLState.OBJECT_NOT_IN_STATE);
              }
              op.handleCommandStatus(status);
            } catch (SQLException se) {
              error = se;
            }

            block = true;
            break;

          case PgMessageType.ERROR_RESPONSE: // ErrorMessage (expected response to CopyFail)

            error = receiveErrorResponse();
            // We've received the error and we now expect to receive
            // Ready for query, but we must block because it might still be
            // on the wire and not here yet.
            block = true;
            break;

          case PgMessageType.COPY_IN_RESPONSE: // CopyInResponse

            LOGGER.log(Level.FINEST, " <=BE CopyInResponse");

            if (op != null) {
              error = new PSQLException(GT.tr("Got CopyInResponse from server during an active {0}",
                  op.getClass().getName()), PSQLState.OBJECT_NOT_IN_STATE);
            }

            op = new CopyInImpl();
            initCopy(op);
            endReceiving = true;
            break;

          case PgMessageType.COPY_OUT_RESPONSE: // CopyOutResponse

            LOGGER.log(Level.FINEST, " <=BE CopyOutResponse");

            if (op != null) {
              error = new PSQLException(GT.tr("Got CopyOutResponse from server during an active {0}",
                  op.getClass().getName()), PSQLState.OBJECT_NOT_IN_STATE);
            }

            op = new CopyOutImpl();
            initCopy(op);
            endReceiving = true;
            break;

          case PgMessageType.COPY_BOTH_RESPONSE: // CopyBothResponse

            LOGGER.log(Level.FINEST, " <=BE CopyBothResponse");

            if (op != null) {
              error = new PSQLException(GT.tr("Got CopyBothResponse from server during an active {0}",
                  op.getClass().getName()), PSQLState.OBJECT_NOT_IN_STATE);
            }

            op = new CopyDualImpl();
            initCopy(op);
            endReceiving = true;
            break;

          case PgMessageType.COPY_DATA: // CopyData

            LOGGER.log(Level.FINEST, " <=BE CopyData");

            len = pgStream.receiveInteger4() - 4;

            assert len > 0 : "Copy Data length must be greater than 4";

            byte[] buf = pgStream.receive(len);
            if (op == null) {
              error = new PSQLException(GT.tr("Got CopyData without an active copy operation"),
                  PSQLState.OBJECT_NOT_IN_STATE);
            } else if (!(op instanceof CopyOut)) {
              error = new PSQLException(
                  GT.tr("Unexpected copydata from server for {0}", op.getClass().getName()),
                  PSQLState.COMMUNICATION_ERROR);
            } else {
              op.handleCopydata(buf);
            }
            endReceiving = true;
            break;

          case PgMessageType.COPY_DONE: // CopyDone (expected after all copydata received)

            LOGGER.log(Level.FINEST, " <=BE CopyDone");

            len = pgStream.receiveInteger4() - 4;
            if (len > 0) {
              pgStream.receive(len); // not in specification; should never appear
            }

            if (!(op instanceof CopyOut)) {
              error = new PSQLException("Got CopyDone while not copying from server",
                  PSQLState.OBJECT_NOT_IN_STATE);
            }

            // keep receiving since we expect a CommandComplete
            block = true;
            break;
          case PgMessageType.PARAMETER_STATUS_RESPONSE: // Parameter Status
            try {
              receiveParameterStatus();
            } catch (SQLException e) {
              error = e;
              endReceiving = true;
            }
            break;

          case PgMessageType.READY_FOR_QUERY_RESPONSE: // ReadyForQuery: After FE:CopyDone => BE:CommandComplete

            receiveRFQ();
            if (op != null && hasLock(op)) {
              unlock(op);
            }
            op = null;
            endReceiving = true;
            break;

          // If the user sends a non-copy query, we've got to handle some additional things.
          //
          case PgMessageType.ROW_DESCRIPTION_RESPONSE: // Row Description (response to Describe)
            LOGGER.log(Level.FINEST, " <=BE RowDescription (during copy ignored)");

            skipMessage();
            break;

          case PgMessageType.DATA_ROW_RESPONSE: // DataRow
            LOGGER.log(Level.FINEST, " <=BE DataRow (during copy ignored)");

            skipMessage();
            break;

          default:
            throw new IOException(
                GT.tr("Unexpected packet type during copy: {0}", Integer.toString(c)));
        }

        // Collect errors into a neat chain for completeness
        if (error != null) {
          if (errors != null) {
            error.setNextException(errors);
          }
          errors = error;
          error = null;
        }
      }

      if (errors != null) {
        throw errors;
      }
      return op;

    } finally {
      /*
      reset here in the finally block to make sure it really is cleared
       */
      processingCopyResults.set(false);
    }
  }

  /*
   * To prevent client/server protocol deadlocks, we try to manage the estimated recv buffer size
   * and force a sync +flush and process results if we think it might be getting too full.
   *
   * See the comments above MAX_BUFFERED_RECV_BYTES's declaration for details.
   */
  private void flushIfDeadlockRisk(Query query, boolean disallowBatching,
      ResultHandler resultHandler,
      @Nullable BatchResultHandler batchHandler,
      final int flags) throws IOException {
    // Assume all statements need at least this much reply buffer space,
    // plus params
    estimatedReceiveBufferBytes += NODATA_QUERY_RESPONSE_SIZE_BYTES;

    SimpleQuery sq = (SimpleQuery) query;
    if (sq.isStatementDescribed()) {
      /*
       * Estimate the response size of the fields and add it to the expected response size.
       *
       * It's impossible for us to estimate the rowcount. We'll assume one row, as that's the common
       * case for batches and we're leaving plenty of breathing room in this approach. It's still
       * not deadlock-proof though; see pgjdbc github issues #194 and #195.
       */
      int maxResultRowSize = sq.getMaxResultRowSize();
      if (maxResultRowSize >= 0) {
        estimatedReceiveBufferBytes += maxResultRowSize;
      } else {
        LOGGER.log(Level.FINEST, "Couldn't estimate result size or result size unbounded, "
            + "disabling batching for this query.");
        disallowBatching = true;
      }
    } else {
      /*
       * We only describe a statement if we're expecting results from it, so it's legal to batch
       * unprepared statements. We'll abort later if we get any uresults from them where none are
       * expected. For now all we can do is hope the user told us the truth and assume that
       * NODATA_QUERY_RESPONSE_SIZE_BYTES is enough to cover it.
       */
    }

    if (disallowBatching || estimatedReceiveBufferBytes >= MAX_BUFFERED_RECV_BYTES) {
      LOGGER.log(Level.FINEST, "Forcing Sync, receive buffer full or batching disallowed");
      sendSync();
      processResults(resultHandler, flags);
      estimatedReceiveBufferBytes = 0;
      if (batchHandler != null) {
        batchHandler.secureProgress();
      }
    }

  }

  /*
   * Send a query to the backend.
   */
  private void sendQuery(Query query, V3ParameterList parameters, int maxRows, int fetchSize,
      int flags, ResultHandler resultHandler,
      @Nullable BatchResultHandler batchHandler, boolean adaptiveFetch) throws IOException, SQLException {
    // Now the query itself.
    Query[] subqueries = query.getSubqueries();
    SimpleParameterList[] subparams = parameters.getSubparams();

    // We know this is deprecated, but still respect it in case anyone's using it.
    // PgJDBC its self no longer does.
    @SuppressWarnings("deprecation")
    boolean disallowBatching = (flags & QueryExecutor.QUERY_DISALLOW_BATCHING) != 0;

    if (subqueries == null) {
      flushIfDeadlockRisk(query, disallowBatching, resultHandler, batchHandler, flags);

      // If we saw errors, don't send anything more.
      if (resultHandler.getException() == null) {
        if (fetchSize != 0) {
          adaptiveFetchCache.addNewQuery(adaptiveFetch, query);
        }
        sendOneQuery((SimpleQuery) query, (SimpleParameterList) parameters, maxRows, fetchSize,
            flags);
      }
    } else {
      for (int i = 0; i < subqueries.length; i++) {
        final Query subquery = subqueries[i];
        flushIfDeadlockRisk(subquery, disallowBatching, resultHandler, batchHandler, flags);

        // If we saw errors, don't send anything more.
        if (resultHandler.getException() != null) {
          break;
        }

        // In the situation where parameters is already
        // NO_PARAMETERS it cannot know the correct
        // number of array elements to return in the
        // above call to getSubparams(), so it must
        // return null which we check for here.
        //
        SimpleParameterList subparam = SimpleQuery.NO_PARAMETERS;
        if (subparams != null) {
          subparam = subparams[i];
        }
        if (fetchSize != 0) {
          adaptiveFetchCache.addNewQuery(adaptiveFetch, subquery);
        }
        sendOneQuery((SimpleQuery) subquery, subparam, maxRows, fetchSize, flags);
      }
    }
  }

  //
  // Message sending
  //

  private void sendSync() throws IOException {
    LOGGER.log(Level.FINEST, " FE=> Sync");

    pgStream.sendChar(PgMessageType.SYNC_REQUEST); // Sync
    pgStream.sendInteger4(4); // Length
    pgStream.flush();
    // Below "add queues" are likely not required at all
    pendingExecuteQueue.add(new ExecuteRequest(sync, null, true));
    pendingDescribePortalQueue.add(sync);
  }

  private void sendParse(SimpleQuery query, SimpleParameterList params, boolean oneShot)
      throws IOException {
    // Already parsed, or we have a Parse pending and the types are right?
    int[] typeOIDs = params.getTypeOIDs();
    if (query.isPreparedFor(typeOIDs, deallocateEpoch)) {
      return;
    }

    // Clean up any existing statement, as we can't use it.
    query.unprepare();
    processDeadParsedQueries();

    // Remove any cached Field values. The re-parsed query might report different
    // fields because input parameter types may result in different type inferences
    // for unspecified types.
    query.setFields(null);

    String statementName = null;
    if (!oneShot) {
      // Generate a statement name to use.
      statementName = "S_" + (nextUniqueID++);

      // And prepare the new statement.
      // NB: Must clone the OID array, as it's a direct reference to
      // the SimpleParameterList's internal array that might be modified
      // under us.
      query.setStatementName(statementName, deallocateEpoch);
      query.setPrepareTypes(typeOIDs);
      registerParsedQuery(query, statementName);
    }

    byte[] encodedStatementName = query.getEncodedStatementName();
    String nativeSql = query.getNativeSql();

    if (LOGGER.isLoggable(Level.FINEST)) {
      StringBuilder sbuf = new StringBuilder(" FE=> Parse(stmt=" + statementName + ",query=\"");
      sbuf.append(nativeSql);
      sbuf.append("\",oids={");
      for (int i = 1; i <= params.getParameterCount(); i++) {
        if (i != 1) {
          sbuf.append(",");
        }
        sbuf.append(params.getTypeOID(i));
      }
      sbuf.append("})");
      LOGGER.log(Level.FINEST, sbuf.toString());
    }

    //
    // Send Parse.
    //

    byte[] queryUtf8 = nativeSql.getBytes(StandardCharsets.UTF_8);

    // Total size = 4 (size field)
    // + N + 1 (statement name, zero-terminated)
    // + N + 1 (query, zero terminated)
    // + 2 (parameter count) + N * 4 (parameter types)
    int encodedSize = 4
        + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
        + queryUtf8.length + 1
        + 2 + 4 * params.getParameterCount();

    pgStream.sendChar(PgMessageType.PARSE_REQUEST); // Parse
    pgStream.sendInteger4(encodedSize);
    if (encodedStatementName != null) {
      pgStream.send(encodedStatementName);
    }
    pgStream.sendChar(0); // End of statement name
    pgStream.send(queryUtf8); // Query string
    pgStream.sendChar(0); // End of query string.
    pgStream.sendInteger2(params.getParameterCount()); // # of parameter types specified
    for (int i = 1; i <= params.getParameterCount(); i++) {
      pgStream.sendInteger4(params.getTypeOID(i));
    }

    pendingParseQueue.add(query);
  }

  private void sendBind(SimpleQuery query, SimpleParameterList params, @Nullable Portal portal,
      boolean noBinaryTransfer) throws IOException {
    //
    // Send Bind.
    //

    String statementName = query.getStatementName();
    byte[] encodedStatementName = query.getEncodedStatementName();
    byte[] encodedPortalName = portal == null ? null : portal.getEncodedPortalName();

    if (LOGGER.isLoggable(Level.FINEST)) {
      StringBuilder sbuf = new StringBuilder(" FE=> Bind(stmt=" + statementName + ",portal=" + portal);
      for (int i = 1; i <= params.getParameterCount(); i++) {
        sbuf.append(",$").append(i).append("=<")
            .append(params.toString(i, getStandardConformingStrings()))
            .append(">,type=").append(Oid.toString(params.getTypeOID(i)));
      }
      sbuf.append(")");
      LOGGER.log(Level.FINEST, sbuf.toString());
    }

    // Total size = 4 (size field) + N + 1 (destination portal)
    // + N + 1 (statement name)
    // + 2 (param format code count) + N * 2 (format codes)
    // + 2 (param value count) + N (encoded param value size)
    // + 2 (result format code count, 0)
    long encodedSize = 0;
    for (int i = 1; i <= params.getParameterCount(); i++) {
      if (params.isNull(i)) {
        encodedSize += 4;
      } else {
        encodedSize += 4L + params.getV3Length(i);
      }
    }

    Field[] fields = query.getFields();
    if (!noBinaryTransfer && query.needUpdateFieldFormats() && fields != null) {
      for (Field field : fields) {
        if (useBinary(field)) {
          field.setFormat(Field.BINARY_FORMAT);
          query.setHasBinaryFields(true);
        }
      }
    }
    // If text-only results are required (e.g. updateable resultset), and the query has binary columns,
    // flip to text format.
    if (noBinaryTransfer && query.hasBinaryFields() && fields != null) {
      for (Field field : fields) {
        if (field.getFormat() != Field.TEXT_FORMAT) {
          field.setFormat(Field.TEXT_FORMAT);
        }
      }
      query.resetNeedUpdateFieldFormats();
      query.setHasBinaryFields(false);
    }

    // This is not the number of binary fields, but the total number
    // of fields if any of them are binary or zero if all of them
    // are text.
    int numBinaryFields = !noBinaryTransfer && query.hasBinaryFields() && fields != null
        ? fields.length : 0;

    encodedSize = 4
        + (encodedPortalName == null ? 0 : encodedPortalName.length) + 1
        + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
        + 2 + params.getParameterCount() * 2L
        + 2 + encodedSize
        + 2 + numBinaryFields * 2L;

    // backend's MaxAllocSize is the largest message that can
    // be received from a client. If we have a bigger value
    // from either very large parameters or incorrect length
    // descriptions of setXXXStream we do not send the bind
    // message.
    //
    if (encodedSize > 0x3fffffff) {
      throw new PGBindException(new IOException(GT.tr(
          "Bind message length {0} too long.  This can be caused by very large or incorrect length specifications on InputStream parameters.",
          encodedSize)));
    }

    pgStream.sendChar(PgMessageType.BIND); // Bind
    pgStream.sendInteger4((int) encodedSize); // Message size
    if (encodedPortalName != null) {
      pgStream.send(encodedPortalName); // Destination portal name.
    }
    pgStream.sendChar(0); // End of portal name.
    if (encodedStatementName != null) {
      pgStream.send(encodedStatementName); // Source statement name.
    }
    pgStream.sendChar(0); // End of statement name.

    pgStream.sendInteger2(params.getParameterCount()); // # of parameter format codes
    for (int i = 1; i <= params.getParameterCount(); i++) {
      pgStream.sendInteger2(params.isBinary(i) ? 1 : 0); // Parameter format code
    }

    pgStream.sendInteger2(params.getParameterCount()); // # of parameter values

    // If an error occurs when reading a stream we have to
    // continue pumping out data to match the length we
    // said we would. Once we've done that we throw
    // this exception. Multiple exceptions can occur and
    // it really doesn't matter which one is reported back
    // to the caller.
    //
    PGBindException bindException = null;

    for (int i = 1; i <= params.getParameterCount(); i++) {
      if (params.isNull(i)) {
        pgStream.sendInteger4(-1); // Magic size of -1 means NULL
      } else {
        pgStream.sendInteger4(params.getV3Length(i)); // Parameter size
        try {
          params.writeV3Value(i, pgStream); // Parameter value
        } catch (SourceStreamIOException sse) {
          // Remember the error for rethrow later
          if (bindException == null) {
            bindException = new PGBindException(sse.getCause());
          } else {
            bindException.addSuppressed(sse.getCause());
          }
          // Write out the missing bytes so the stream does not corrupt
          pgStream.sendZeros(sse.getBytesRemaining());
        }
      }
    }

    pgStream.sendInteger2(numBinaryFields); // # of result format codes
    for (int i = 0; fields != null && i < numBinaryFields; i++) {
      pgStream.sendInteger2(fields[i].getFormat());
    }

    pendingBindQueue.add(portal == null ? UNNAMED_PORTAL : portal);

    if (bindException != null) {
      throw bindException;
    }
  }

  /**
   * Returns true if the specified field should be retrieved using binary encoding.
   *
   * @param field The field whose Oid type to analyse.
   * @return True if {@link Field#BINARY_FORMAT} should be used, false if
   *         {@link Field#BINARY_FORMAT}.
   */
  private boolean useBinary(Field field) {
    int oid = field.getOID();
    return useBinaryForReceive(oid);
  }

  private void sendDescribePortal(SimpleQuery query, @Nullable Portal portal) throws IOException {
    //
    // Send Describe.
    //

    LOGGER.log(Level.FINEST, " FE=> Describe(portal={0})", portal);

    byte[] encodedPortalName = portal == null ? null : portal.getEncodedPortalName();

    // Total size = 4 (size field) + 1 (describe type, 'P') + N + 1 (portal name)
    int encodedSize = 4 + 1 + (encodedPortalName == null ? 0 : encodedPortalName.length) + 1;

    pgStream.sendChar(PgMessageType.DESCRIBE_REQUEST); // Describe
    pgStream.sendInteger4(encodedSize); // message size
    pgStream.sendChar(PgMessageType.PORTAL); // Describe (Portal)
    if (encodedPortalName != null) {
      pgStream.send(encodedPortalName); // portal name to close
    }
    pgStream.sendChar(0); // end of portal name

    pendingDescribePortalQueue.add(query);
    query.setPortalDescribed(true);
  }

  private void sendDescribeStatement(SimpleQuery query, SimpleParameterList params,
      boolean describeOnly) throws IOException {
    // Send Statement Describe

    LOGGER.log(Level.FINEST, " FE=> Describe(statement={0})", query.getStatementName());

    byte[] encodedStatementName = query.getEncodedStatementName();

    // Total size = 4 (size field) + 1 (describe type, 'S') + N + 1 (portal name)
    int encodedSize = 4 + 1 + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1;

    pgStream.sendChar(PgMessageType.DESCRIBE_REQUEST); // Describe
    pgStream.sendInteger4(encodedSize); // Message size
    pgStream.sendChar(PgMessageType.STATEMENT); // Describe (Statement);
    if (encodedStatementName != null) {
      pgStream.send(encodedStatementName); // Statement name
    }
    pgStream.sendChar(0); // end message

    // Note: statement name can change over time for the same query object
    // Thus we take a snapshot of the query name
    pendingDescribeStatementQueue.add(
        new DescribeRequest(query, params, describeOnly, query.getStatementName()));
    pendingDescribePortalQueue.add(query);
    query.setStatementDescribed(true);
    query.setPortalDescribed(true);
  }

  private void sendExecute(SimpleQuery query, @Nullable Portal portal, int limit)
      throws IOException {
    //
    // Send Execute.
    //
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " FE=> Execute(portal={0},limit={1})", new Object[]{portal, limit});
    }

    byte[] encodedPortalName = portal == null ? null : portal.getEncodedPortalName();
    int encodedSize = encodedPortalName == null ? 0 : encodedPortalName.length;

    // Total size = 4 (size field) + 1 + N (source portal) + 4 (max rows)
    pgStream.sendChar(PgMessageType.EXECUTE_REQUEST); // Execute
    pgStream.sendInteger4(4 + 1 + encodedSize + 4); // message size
    if (encodedPortalName != null) {
      pgStream.send(encodedPortalName); // portal name
    }
    pgStream.sendChar(0); // portal name terminator
    pgStream.sendInteger4(limit); // row limit

    pendingExecuteQueue.add(new ExecuteRequest(query, portal, false));
  }

  private void sendClosePortal(String portalName) throws IOException {
    //
    // Send Close.
    //

    LOGGER.log(Level.FINEST, " FE=> ClosePortal({0})", portalName);

    byte[] encodedPortalName = portalName == null ? null : portalName.getBytes(StandardCharsets.UTF_8);
    int encodedSize = encodedPortalName == null ? 0 : encodedPortalName.length;

    // Total size = 4 (size field) + 1 (close type, 'P') + 1 + N (portal name)
    pgStream.sendChar(PgMessageType.CLOSE_REQUEST); // Close
    pgStream.sendInteger4(4 + 1 + 1 + encodedSize); // message size
    pgStream.sendChar(PgMessageType.PORTAL); // Close (Portal)
    if (encodedPortalName != null) {
      pgStream.send(encodedPortalName);
    }
    pgStream.sendChar(0); // unnamed portal
  }

  private void sendCloseStatement(String statementName) throws IOException {
    //
    // Send Close.
    //

    LOGGER.log(Level.FINEST, " FE=> CloseStatement({0})", statementName);

    byte[] encodedStatementName = statementName.getBytes(StandardCharsets.UTF_8);

    // Total size = 4 (size field) + 1 (close type, 'S') + N + 1 (statement name)
    pgStream.sendChar(PgMessageType.CLOSE_REQUEST); // Close
    pgStream.sendInteger4(4 + 1 + encodedStatementName.length + 1); // message size
    pgStream.sendChar(PgMessageType.STATEMENT); // Close (Statement)
    pgStream.send(encodedStatementName); // statement to close
    pgStream.sendChar(0); // statement name terminator
  }

  // sendOneQuery sends a single statement via the extended query protocol.
  // Per the FE/BE docs this is essentially the same as how a simple query runs
  // (except that it generates some extra acknowledgement messages, and we
  // can send several queries before doing the Sync)
  //
  // Parse S_n from "query string with parameter placeholders"; skipped if already done previously
  // or if oneshot
  // Bind C_n from S_n plus parameters (or from unnamed statement for oneshot queries)
  // Describe C_n; skipped if caller doesn't want metadata
  // Execute C_n with maxRows limit; maxRows = 1 if caller doesn't want results
  // (above repeats once per call to sendOneQuery)
  // Sync (sent by caller)
  //
  private void sendOneQuery(SimpleQuery query, SimpleParameterList params, int maxRows,
      int fetchSize, int flags) throws IOException {
    boolean asSimple = (flags & QueryExecutor.QUERY_EXECUTE_AS_SIMPLE) != 0;
    if (asSimple) {
      assert (flags & QueryExecutor.QUERY_DESCRIBE_ONLY) == 0
          : "Simple mode does not support describe requests. sql = " + query.getNativeSql()
          + ", flags = " + flags;
      sendSimpleQuery(query, params);
      return;
    }

    assert !query.getNativeQuery().multiStatement
        : "Queries that might contain ; must be executed with QueryExecutor.QUERY_EXECUTE_AS_SIMPLE mode. "
        + "Given query is " + query.getNativeSql();

    // Per https://www.postgresql.org/docs/current/static/protocol-flow.html#PROTOCOL-FLOW-EXT-QUERY
    // A Bind message can use the unnamed prepared statement to create a named portal.
    // If the Bind is successful, an Execute message can reference that named portal until either
    //      the end of the current transaction
    //   or the named portal is explicitly destroyed

    boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;
    boolean noMeta = (flags & QueryExecutor.QUERY_NO_METADATA) != 0;
    boolean describeOnly = (flags & QueryExecutor.QUERY_DESCRIBE_ONLY) != 0;
    // extended queries always use a portal
    // the usePortal flag controls whether or not we use a *named* portal
    boolean usePortal = (flags & QueryExecutor.QUERY_FORWARD_CURSOR) != 0 && !noResults && !noMeta
        && fetchSize > 0 && !describeOnly;
    boolean oneShot = (flags & QueryExecutor.QUERY_ONESHOT) != 0;
    boolean noBinaryTransfer = (flags & QUERY_NO_BINARY_TRANSFER) != 0;
    boolean forceDescribePortal = (flags & QUERY_FORCE_DESCRIBE_PORTAL) != 0;

    // Work out how many rows to fetch in this pass.

    int rows;
    if (noResults) {
      rows = 1; // We're discarding any results anyway, so limit data transfer to a minimum
    } else if (!usePortal) {
      rows = maxRows; // Not using a portal -- fetchSize is irrelevant
    } else if (maxRows != 0 && fetchSize > maxRows) {
      // fetchSize > maxRows, use maxRows (nb: fetchSize cannot be 0 if usePortal == true)
      rows = maxRows;
    } else {
      rows = fetchSize; // maxRows > fetchSize
    }

    sendParse(query, params, oneShot);

    // Must do this after sendParse to pick up any changes to the
    // query's state.
    //
    boolean queryHasUnknown = query.hasUnresolvedTypes();
    boolean paramsHasUnknown = params.hasUnresolvedTypes();

    boolean describeStatement = describeOnly
        || (!oneShot && paramsHasUnknown && queryHasUnknown && !query.isStatementDescribed());

    if (!describeStatement && paramsHasUnknown && !queryHasUnknown) {
      int[] queryOIDs = castNonNull(query.getPrepareTypes());
      int[] paramOIDs = params.getTypeOIDs();
      for (int i = 0; i < paramOIDs.length; i++) {
        // Only supply type information when there isn't any
        // already, don't arbitrarily overwrite user supplied
        // type information.
        if (paramOIDs[i] == Oid.UNSPECIFIED) {
          params.setResolvedType(i + 1, queryOIDs[i]);
        }
      }
    }

    if (describeStatement) {
      sendDescribeStatement(query, params, describeOnly);
      if (describeOnly) {
        return;
      }
    }

    // Construct a new portal if needed.
    Portal portal = null;
    if (usePortal) {
      String portalName = "C_" + (nextUniqueID++);
      portal = new Portal(query, portalName);
    }

    sendBind(query, params, portal, noBinaryTransfer);

    // A statement describe will also output a RowDescription,
    // so don't reissue it here if we've already done so.
    //
    if (!noMeta && !describeStatement) {
      /*
       * don't send describe if we already have cached the row description from previous executions
       *
       * XXX Clearing the fields / unpreparing the query (in sendParse) is incorrect, see bug #267.
       * We might clear the cached fields in a later execution of this query if the bind parameter
       * types change, but we're assuming here that they'll still be valid when we come to process
       * the results of this query, so we don't send a new describe here. We re-describe after the
       * fields are cleared, but the result of that gets processed after processing the results from
       * earlier executions that we didn't describe because we didn't think we had to.
       *
       * To work around this, force a Describe at each execution in batches where this can be a
       * problem. It won't cause more round trips so the performance impact is low, and it'll ensure
       * that the field information available when we decoded the results. This is undeniably a
       * hack, but there aren't many good alternatives.
       */
      if (!query.isPortalDescribed() || forceDescribePortal) {
        sendDescribePortal(query, portal);
      }
    }

    sendExecute(query, portal, rows);
  }

  private void sendSimpleQuery(SimpleQuery query, SimpleParameterList params) throws IOException {
    String nativeSql = query.toString(
        params,
        SqlSerializationContext.of(getStandardConformingStrings(), false));

    LOGGER.log(Level.FINEST, " FE=> SimpleQuery(query=\"{0}\")", nativeSql);
    Encoding encoding = pgStream.getEncoding();

    byte[] encoded = encoding.encode(nativeSql);
    pgStream.sendChar(PgMessageType.QUERY_REQUEST);
    pgStream.sendInteger4(encoded.length + 4 + 1);
    pgStream.send(encoded);
    pgStream.sendChar(0);
    pgStream.flush();
    pendingExecuteQueue.add(new ExecuteRequest(query, null, true));
    pendingDescribePortalQueue.add(query);
  }

  //
  // Garbage collection of parsed statements.
  //
  // When a statement is successfully parsed, registerParsedQuery is called.
  // This creates a PhantomReference referring to the "owner" of the statement
  // (the originating Query object) and inserts that reference as a key in
  // parsedQueryMap. The values of parsedQueryMap are the corresponding allocated
  // statement names. The originating Query object also holds a reference to the
  // PhantomReference.
  //
  // When the owning Query object is closed, it enqueues and clears the associated
  // PhantomReference.
  //
  // If the owning Query object becomes unreachable (see java.lang.ref javadoc) before
  // being closed, the corresponding PhantomReference is enqueued on
  // parsedQueryCleanupQueue. In the Sun JVM, phantom references are only enqueued
  // when a GC occurs, so this is not necessarily prompt but should eventually happen.
  //
  // Periodically (currently, just before query execution), the parsedQueryCleanupQueue
  // is polled. For each enqueued PhantomReference we find, we remove the corresponding
  // entry from parsedQueryMap, obtaining the name of the underlying statement in the
  // process. Then we send a message to the backend to deallocate that statement.
  //

  private final HashMap<PhantomReference<SimpleQuery>, String> parsedQueryMap =
      new HashMap<>();
  private final ReferenceQueue<SimpleQuery> parsedQueryCleanupQueue =
      new ReferenceQueue<>();

  private void registerParsedQuery(SimpleQuery query, String statementName) {
    if (statementName == null) {
      return;
    }

    PhantomReference<SimpleQuery> cleanupRef =
        new PhantomReference<>(query, parsedQueryCleanupQueue);
    parsedQueryMap.put(cleanupRef, statementName);
    query.setCleanupRef(cleanupRef);
  }

  private void processDeadParsedQueries() throws IOException {
    Reference<? extends SimpleQuery> deadQuery;
    while ((deadQuery = parsedQueryCleanupQueue.poll()) != null) {
      String statementName = castNonNull(parsedQueryMap.remove(deadQuery));
      sendCloseStatement(statementName);
      deadQuery.clear();
    }
  }

  //
  // Essentially the same strategy is used for the cleanup of portals.
  // Note that each Portal holds a reference to the corresponding Query
  // that generated it, so the Query won't be collected (and the statement
  // closed) until all the Portals are, too. This is required by the mechanics
  // of the backend protocol: when a statement is closed, all dependent portals
  // are also closed.
  //

  private final HashMap<PhantomReference<Portal>, String> openPortalMap =
      new HashMap<>();
  private final ReferenceQueue<Portal> openPortalCleanupQueue = new ReferenceQueue<>();

  private static final Portal UNNAMED_PORTAL = new Portal(null, "unnamed");

  private void registerOpenPortal(Portal portal) {
    if (portal == UNNAMED_PORTAL) {
      return; // Using the unnamed portal.
    }

    String portalName = portal.getPortalName();
    PhantomReference<Portal> cleanupRef =
        new PhantomReference<>(portal, openPortalCleanupQueue);
    openPortalMap.put(cleanupRef, portalName);
    portal.setCleanupRef(cleanupRef);
  }

  private void processDeadPortals() throws IOException {
    Reference<? extends Portal> deadPortal;
    while ((deadPortal = openPortalCleanupQueue.poll()) != null) {
      String portalName = castNonNull(openPortalMap.remove(deadPortal));
      sendClosePortal(portalName);
      deadPortal.clear();
    }
  }

  protected void processResults(ResultHandler handler, int flags) throws IOException {
    processResults(handler, flags, false);
  }

  protected void processResults(ResultHandler handler, int flags, boolean adaptiveFetch)
      throws IOException {
    boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;
    boolean bothRowsAndStatus = (flags & QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS) != 0;

    List<Tuple> tuples = null;

    int c;
    boolean endQuery = false;

    // At the end of a command execution we have the CommandComplete
    // message to tell us we're done, but with a describeOnly command
    // we have no real flag to let us know we're done. We've got to
    // look for the next RowDescription or NoData message and return
    // from there.
    boolean doneAfterRowDescNoData = false;

    while (!endQuery) {
      c = pgStream.receiveChar();
      switch (c) {
        case 'A': // Asynchronous Notify
          receiveAsyncNotify();
          break;

        case PgMessageType.PARSE_COMPLETE_RESPONSE: // Parse Complete (response to Parse)
          pgStream.receiveInteger4(); // len, discarded

          SimpleQuery parsedQuery = pendingParseQueue.removeFirst();
          String parsedStatementName = parsedQuery.getStatementName();

          LOGGER.log(Level.FINEST, " <=BE ParseComplete [{0}]", parsedStatementName);

          break;

        case PgMessageType.PARAMETER_DESCRIPTION_RESPONSE: {
          pgStream.receiveInteger4(); // len, discarded

          LOGGER.log(Level.FINEST, " <=BE ParameterDescription");

          DescribeRequest describeData = pendingDescribeStatementQueue.getFirst();
          SimpleQuery query = describeData.query;
          SimpleParameterList params = describeData.parameterList;
          boolean describeOnly = describeData.describeOnly;
          // This might differ from query.getStatementName if the query was re-prepared
          String origStatementName = describeData.statementName;

          int numParams = pgStream.receiveInteger2();

          for (int i = 1; i <= numParams; i++) {
            int typeOid = pgStream.receiveInteger4();
            params.setResolvedType(i, typeOid);
          }

          // Since we can issue multiple Parse and DescribeStatement
          // messages in a single network trip, we need to make
          // sure the describe results we requested are still
          // applicable to the latest parsed query.
          //
          if ((origStatementName == null && query.getStatementName() == null)
              || (origStatementName != null
                  && origStatementName.equals(query.getStatementName()))) {
            query.setPrepareTypes(params.getTypeOIDs());
          }

          if (describeOnly) {
            doneAfterRowDescNoData = true;
          } else {
            pendingDescribeStatementQueue.removeFirst();
          }
          break;
        }

        case PgMessageType.BIND_COMPLETE_RESPONSE: // (response to Bind)
          pgStream.receiveInteger4(); // len, discarded

          Portal boundPortal = pendingBindQueue.removeFirst();
          LOGGER.log(Level.FINEST, " <=BE BindComplete [{0}]", boundPortal);

          registerOpenPortal(boundPortal);
          break;

        case PgMessageType.CLOSE_COMPLETE_RESPONSE: // response to Close
          pgStream.receiveInteger4(); // len, discarded
          LOGGER.log(Level.FINEST, " <=BE CloseComplete");
          break;

        case PgMessageType.NO_DATA_RESPONSE: // response to Describe
          pgStream.receiveInteger4(); // len, discarded
          LOGGER.log(Level.FINEST, " <=BE NoData");

          pendingDescribePortalQueue.removeFirst();

          if (doneAfterRowDescNoData) {
            DescribeRequest describeData = pendingDescribeStatementQueue.removeFirst();
            SimpleQuery currentQuery = describeData.query;

            Field[] fields = currentQuery.getFields();

            if (fields != null) { // There was a resultset.
              tuples = new ArrayList<>();
              handler.handleResultRows(currentQuery, fields, tuples, null);
              tuples = null;
            }
          }
          break;

        case PgMessageType.PORTAL_SUSPENDED_RESPONSE: { // end of Execute
          // nb: this appears *instead* of CommandStatus.
          // Must be a SELECT if we suspended, so don't worry about it.

          pgStream.receiveInteger4(); // len, discarded
          LOGGER.log(Level.FINEST, " <=BE PortalSuspended");

          ExecuteRequest executeData = pendingExecuteQueue.removeFirst();
          SimpleQuery currentQuery = executeData.query;
          Portal currentPortal = executeData.portal;

          if (currentPortal != null) {
            // Existence of portal defines if query was using fetching.
            adaptiveFetchCache
              .updateQueryFetchSize(adaptiveFetch, currentQuery, pgStream.getMaxRowSizeBytes());
          }
          pgStream.clearMaxRowSizeBytes();

          Field[] fields = currentQuery.getFields();
          if (fields != null && tuples == null) {
            // When no results expected, pretend an empty resultset was returned
            // Not sure if new ArrayList can be always replaced with emptyList
            tuples = noResults ? Collections.emptyList() : new ArrayList<Tuple>();
          }

          if (fields != null && tuples != null) {
            handler.handleResultRows(currentQuery, fields, tuples, currentPortal);
          }
          tuples = null;
          break;
        }

        case PgMessageType.COMMAND_COMPLETE_RESPONSE: { // end of Execute
          // Handle status.
          String status = receiveCommandStatus();
          if (isFlushCacheOnDeallocate()
              && (status.startsWith("DEALLOCATE ALL") || status.startsWith("DISCARD ALL"))) {
            deallocateEpoch++;
          }

          doneAfterRowDescNoData = false;

          ExecuteRequest executeData = castNonNull(pendingExecuteQueue.peekFirst());
          SimpleQuery currentQuery = executeData.query;
          Portal currentPortal = executeData.portal;

          if (currentPortal != null) {
            // Existence of portal defines if query was using fetching.

            // Command executed, adaptive fetch size can be removed for this query, max row size can be cleared
            adaptiveFetchCache.removeQuery(adaptiveFetch, currentQuery);
            // Update to change fetch size for other fetch portals of this query
            adaptiveFetchCache
                .updateQueryFetchSize(adaptiveFetch, currentQuery, pgStream.getMaxRowSizeBytes());
          }
          pgStream.clearMaxRowSizeBytes();

          if (status.startsWith("SET")) {
            String nativeSql = currentQuery.getNativeQuery().nativeSql;
            // Scan only the first 1024 characters to
            // avoid big overhead for long queries.
            if (nativeSql.lastIndexOf("search_path", 1024) != -1
                && !nativeSql.equals(lastSetSearchPathQuery)) {
              // Search path was changed, invalidate prepared statement cache
              lastSetSearchPathQuery = nativeSql;
              deallocateEpoch++;
            }
          }

          if (!executeData.asSimple) {
            pendingExecuteQueue.removeFirst();
          } else {
            // For simple 'Q' queries, executeQueue is cleared via ReadyForQuery message
          }

          // we want to make sure we do not add any results from these queries to the result set
          if (currentQuery == autoSaveQuery
              || currentQuery == releaseAutoSave) {
            // ignore "SAVEPOINT" or RELEASE SAVEPOINT status from autosave query
            break;
          }

          Field[] fields = currentQuery.getFields();
          if (fields != null && tuples == null) {
            // When no results expected, pretend an empty resultset was returned
            // Not sure if new ArrayList can be always replaced with emptyList
            tuples = noResults ? Collections.emptyList() : new ArrayList<Tuple>();
          }

          // If we received tuples we must know the structure of the
          // resultset, otherwise we won't be able to fetch columns
          // from it, etc, later.
          if (fields == null && tuples != null) {
            throw new IllegalStateException(
                "Received resultset tuples, but no field structure for them");
          }

          if (fields != null && tuples != null) {
            // There was a resultset.
            handler.handleResultRows(currentQuery, fields, tuples, null);
            tuples = null;

            if (bothRowsAndStatus) {
              interpretCommandStatus(status, handler);
            }
          } else {
            interpretCommandStatus(status, handler);
          }

          if (executeData.asSimple) {
            // Simple queries might return several resultsets, thus we clear
            // fields, so queries like "select 1;update; select2" will properly
            // identify that "update" did not return any results
            currentQuery.setFields(null);
          }

          if (currentPortal != null) {
            currentPortal.close();
          }
          break;
        }

        case PgMessageType.DATA_ROW_RESPONSE: // Data Transfer (ongoing Execute response)
          Tuple tuple = null;
          try {
            tuple = pgStream.receiveTupleV3();
          } catch (OutOfMemoryError oome) {
            if (!noResults) {
              handler.handleError(
                  new PSQLException(GT.tr("Ran out of memory retrieving query results."),
                      PSQLState.OUT_OF_MEMORY, oome));
            }
          } catch (SQLException e) {
            handler.handleError(e);
          }
          if (!noResults) {
            if (tuples == null) {
              tuples = new ArrayList<>();
            }
            if (tuple != null) {
              tuples.add(tuple);
            }
          }

          if (LOGGER.isLoggable(Level.FINEST)) {
            int length;
            if (tuple == null) {
              length = -1;
            } else {
              length = tuple.length();
            }
            LOGGER.log(Level.FINEST, " <=BE DataRow(len={0})", length);
          }

          break;

        case PgMessageType.ERROR_RESPONSE:
          // Error Response (response to pretty much everything; backend then skips until Sync)
          SQLException error = receiveErrorResponse();
          handler.handleError(error);
          if (willHealViaReparse(error)) {
            // prepared statement ... is not valid kind of error
            // Technically speaking, the error is unexpected, thus we invalidate other
            // server-prepared statements just in case.
            deallocateEpoch++;
            if (LOGGER.isLoggable(Level.FINEST)) {
              LOGGER.log(Level.FINEST, " FE: received {0}, will invalidate statements. deallocateEpoch is now {1}",
                  new Object[]{error.getSQLState(), deallocateEpoch});
            }
          }
          // keep processing
          break;

        case PgMessageType.EMPTY_QUERY_RESPONSE: { // Empty Query (end of Execute)
          pgStream.receiveInteger4();

          LOGGER.log(Level.FINEST, " <=BE EmptyQuery");

          ExecuteRequest executeData = pendingExecuteQueue.removeFirst();
          Portal currentPortal = executeData.portal;
          handler.handleCommandStatus("EMPTY", 0, 0);
          if (currentPortal != null) {
            currentPortal.close();
          }
          break;
        }

        case PgMessageType.NOTICE_RESPONSE:
          SQLWarning warning = receiveNoticeResponse();
          handler.handleWarning(warning);
          break;

        case PgMessageType.PARAMETER_STATUS_RESPONSE:
          try {
            receiveParameterStatus();
          } catch (SQLException e) {
            handler.handleError(e);
            endQuery = true;
          }
          break;

        case PgMessageType.ROW_DESCRIPTION_RESPONSE: // response to Describe
          Field[] fields = receiveFields();
          tuples = new ArrayList<>();

          SimpleQuery query = castNonNull(pendingDescribePortalQueue.peekFirst());
          if (!pendingExecuteQueue.isEmpty()
              && !castNonNull(pendingExecuteQueue.peekFirst()).asSimple) {
            pendingDescribePortalQueue.removeFirst();
          }
          query.setFields(fields);

          if (doneAfterRowDescNoData) {
            DescribeRequest describeData = pendingDescribeStatementQueue.removeFirst();
            SimpleQuery currentQuery = describeData.query;
            currentQuery.setFields(fields);

            // We do not need creating resultset here, however, it is actually used
            // in PgPreparedStatement#getMetaData()
            handler.handleResultRows(currentQuery, fields, tuples, null);
            tuples = null;
          }
          break;

        case PgMessageType.READY_FOR_QUERY_RESPONSE: // eventual response to Sync
          receiveRFQ();
          if (!pendingExecuteQueue.isEmpty()
              && castNonNull(pendingExecuteQueue.peekFirst()).asSimple) {
            tuples = null;
            pgStream.clearResultBufferCount();

            ExecuteRequest executeRequest = pendingExecuteQueue.removeFirst();
            // Simple queries might return several resultsets, thus we clear
            // fields, so queries like "select 1;update; select2" will properly
            // identify that "update" did not return any results
            executeRequest.query.setFields(null);

            pendingDescribePortalQueue.removeFirst();
            if (!pendingExecuteQueue.isEmpty()) {
              if (getTransactionState() == TransactionState.IDLE) {
                handler.secureProgress();
              }
              // process subsequent results (e.g. for cases like batched execution of simple 'Q' queries)
              break;
            }
          }
          endQuery = true;

          // Reset the statement name of Parses that failed.
          while (!pendingParseQueue.isEmpty()) {
            SimpleQuery failedQuery = pendingParseQueue.removeFirst();
            failedQuery.unprepare();
          }

          pendingParseQueue.clear(); // No more ParseComplete messages expected.
          // Pending "describe" requests might be there in case of error
          // If that is the case, reset "described" status, so the statement is properly
          // described on next execution
          while (!pendingDescribeStatementQueue.isEmpty()) {
            DescribeRequest request = pendingDescribeStatementQueue.removeFirst();
            LOGGER.log(Level.FINEST, " FE marking setStatementDescribed(false) for query {0}", request.query);
            request.query.setStatementDescribed(false);
          }
          while (!pendingDescribePortalQueue.isEmpty()) {
            SimpleQuery describePortalQuery = pendingDescribePortalQueue.removeFirst();
            LOGGER.log(Level.FINEST, " FE marking setPortalDescribed(false) for query {0}", describePortalQuery);
            describePortalQuery.setPortalDescribed(false);
          }
          pendingBindQueue.clear(); // No more BindComplete messages expected.
          pendingExecuteQueue.clear(); // No more query executions expected.
          break;

        case PgMessageType.COPY_IN_RESPONSE:
          LOGGER.log(Level.FINEST, " <=BE CopyInResponse");
          LOGGER.log(Level.FINEST, " FE=> CopyFail");

          // COPY sub-protocol is not implemented yet
          // We'll send a CopyFail message for COPY FROM STDIN so that
          // server does not wait for the data.

          byte[] buf = "COPY commands are only supported using the CopyManager API.".getBytes(StandardCharsets.US_ASCII);
          pgStream.sendChar(PgMessageType.COPY_FAIL);
          pgStream.sendInteger4(buf.length + 4 + 1);
          pgStream.send(buf);
          pgStream.sendChar(0);
          pgStream.flush();
          sendSync(); // send sync message
          skipMessage(); // skip the response message
          break;

        case PgMessageType.COPY_OUT_RESPONSE:
          LOGGER.log(Level.FINEST, " <=BE CopyOutResponse");

          skipMessage();
          // In case of CopyOutResponse, we cannot abort data transfer,
          // so just throw an error and ignore CopyData messages
          handler.handleError(
              new PSQLException(GT.tr("COPY commands are only supported using the CopyManager API."),
                  PSQLState.NOT_IMPLEMENTED));
          break;

        case PgMessageType.COPY_DONE:
          skipMessage();
          LOGGER.log(Level.FINEST, " <=BE CopyDone");
          break;

        case PgMessageType.COPY_DATA:
          skipMessage();
          LOGGER.log(Level.FINEST, " <=BE CopyData");
          break;

        default:
          throw new IOException("Unexpected packet type: " + c);
      }

    }
  }

  /**
   * Ignore the response message by reading the message length and skipping over those bytes in the
   * communication stream.
   */
  private void skipMessage() throws IOException {
    int len = pgStream.receiveInteger4();

    assert len >= 4 : "Length from skip message must be at least 4 ";

    // skip len-4 (length includes the 4 bytes for message length itself
    pgStream.skip(len - 4);
  }

  @Override
  public void fetch(ResultCursor cursor, ResultHandler handler, int fetchSize,
      boolean adaptiveFetch) throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      waitOnLock();
      final Portal portal = (Portal) cursor;

      // Insert a ResultHandler that turns bare command statuses into empty datasets
      // (if the fetch returns no rows, we see just a CommandStatus..)
      final ResultHandler delegateHandler = handler;
      final SimpleQuery query = castNonNull(portal.getQuery());
      handler = new ResultHandlerDelegate(delegateHandler) {
        @Override
        public void handleCommandStatus(String status, long updateCount, long insertOID) {
          handleResultRows(query, NO_FIELDS, new ArrayList<>(), null);
        }
      };

      // Now actually run it.

      try {
        processDeadParsedQueries();
        processDeadPortals();

        sendExecute(query, portal, fetchSize);
        sendSync();

        processResults(handler, 0, adaptiveFetch);
        estimatedReceiveBufferBytes = 0;
      } catch (IOException e) {
        abort();
        handler.handleError(
            new PSQLException(GT.tr("An I/O error occurred while sending to the backend."),
                PSQLState.CONNECTION_FAILURE, e));
      }

      handler.handleCompletion();
    }
  }

  @Override
  public int getAdaptiveFetchSize(boolean adaptiveFetch, ResultCursor cursor) {
    if (cursor instanceof Portal) {
      Query query = ((Portal) cursor).getQuery();
      if (Objects.nonNull(query)) {
        return adaptiveFetchCache
            .getFetchSizeForQuery(adaptiveFetch, query);
      }
    }
    return -1;
  }

  @Override
  public void setAdaptiveFetch(boolean adaptiveFetch) {
    this.adaptiveFetchCache.setAdaptiveFetch(adaptiveFetch);
  }

  @Override
  public boolean getAdaptiveFetch() {
    return this.adaptiveFetchCache.getAdaptiveFetch();
  }

  @Override
  public void addQueryToAdaptiveFetchCache(boolean adaptiveFetch, ResultCursor cursor) {
    if (cursor instanceof Portal) {
      Query query = ((Portal) cursor).getQuery();
      if (Objects.nonNull(query)) {
        adaptiveFetchCache.addNewQuery(adaptiveFetch, query);
      }
    }
  }

  @Override
  public void removeQueryFromAdaptiveFetchCache(boolean adaptiveFetch, ResultCursor cursor) {
    if (cursor instanceof Portal) {
      Query query = ((Portal) cursor).getQuery();
      if (Objects.nonNull(query)) {
        adaptiveFetchCache.removeQuery(adaptiveFetch, query);
      }
    }
  }

  /*
   * Receive the field descriptions from the back end.
   */
  private Field[] receiveFields() throws IOException {
    pgStream.receiveInteger4(); // MESSAGE SIZE
    int size = pgStream.receiveInteger2();
    Field[] fields = new Field[size];

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " <=BE RowDescription({0})", size);
    }

    for (int i = 0; i < fields.length; i++) {
      String columnLabel = pgStream.receiveCanonicalString();
      int tableOid = pgStream.receiveInteger4();
      short positionInTable = (short) pgStream.receiveInteger2();
      int typeOid = pgStream.receiveInteger4();
      int typeLength = pgStream.receiveInteger2();
      int typeModifier = pgStream.receiveInteger4();
      int formatType = pgStream.receiveInteger2();
      fields[i] = new Field(columnLabel,
          typeOid, typeLength, typeModifier, tableOid, positionInTable);
      fields[i].setFormat(formatType);

      LOGGER.log(Level.FINEST, "        {0}", fields[i]);
    }

    return fields;
  }

  private void receiveAsyncNotify() throws IOException {
    int len = pgStream.receiveInteger4(); // MESSAGE SIZE
    assert len > 4 : "Length for AsyncNotify must be at least 4";

    int pid = pgStream.receiveInteger4();
    String msg = pgStream.receiveCanonicalString();
    String param = pgStream.receiveString();
    addNotification(new Notification(msg, pid, param));

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " <=BE AsyncNotify({0},{1},{2})", new Object[]{pid, msg, param});
    }
  }

  private SQLException receiveErrorResponse() throws IOException {
    // it's possible to get more than one error message for a query
    // see libpq comments wrt backend closing a connection
    // so, append messages to a string buffer and keep processing
    // check at the bottom to see if we need to throw an exception

    int elen = pgStream.receiveInteger4();
    assert elen > 4 : "Error response length must be greater than 4";

    EncodingPredictor.DecodeResult totalMessage = pgStream.receiveErrorString(elen - 4);
    ServerErrorMessage errorMsg = new ServerErrorMessage(totalMessage);

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " <=BE ErrorMessage({0})", errorMsg.toString());
    }

    PSQLException error = new PSQLException(errorMsg, this.logServerErrorDetail);
    if (transactionFailCause == null) {
      transactionFailCause = error;
    } else {
      error.initCause(transactionFailCause);
    }
    return error;
  }

  private SQLWarning receiveNoticeResponse() throws IOException {
    int nlen = pgStream.receiveInteger4();
    assert nlen > 4 : "Notice Response length must be greater than 4";

    ServerErrorMessage warnMsg = new ServerErrorMessage(pgStream.receiveString(nlen - 4));

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " <=BE NoticeResponse({0})", warnMsg.toString());
    }

    return new PSQLWarning(warnMsg);
  }

  private String receiveCommandStatus() throws IOException {
    // TODO: better handle the msg len
    int len = pgStream.receiveInteger4();
    // read len -5 bytes (-4 for len and -1 for trailing \0)
    String status = pgStream.receiveString(len - 5);
    // now read and discard the trailing \0
    pgStream.receiveChar(); // Receive(1) would allocate new byte[1], so avoid it

    LOGGER.log(Level.FINEST, " <=BE CommandStatus({0})", status);

    return status;
  }

  private void interpretCommandStatus(String status, ResultHandler handler) {
    try {
      commandCompleteParser.parse(status);
    } catch (SQLException e) {
      handler.handleError(e);
      return;
    }
    long oid = commandCompleteParser.getOid();
    long count = commandCompleteParser.getRows();

    handler.handleCommandStatus(status, count, oid);
  }

  private void receiveRFQ() throws IOException {
    if (pgStream.receiveInteger4() != 5) {
      throw new IOException("unexpected length of ReadyForQuery message");
    }

    char tStatus = (char) pgStream.receiveChar();
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " <=BE ReadyForQuery({0})", tStatus);
    }

    // Update connection state.
    switch (tStatus) {
      case 'I':
        transactionFailCause = null;
        setTransactionState(TransactionState.IDLE);
        break;
      case 'T':
        transactionFailCause = null;
        setTransactionState(TransactionState.OPEN);
        break;
      case 'E':
        setTransactionState(TransactionState.FAILED);
        break;
      default:
        throw new IOException(
            "unexpected transaction state in ReadyForQuery message: " + (int) tStatus);
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  protected void sendCloseMessage() throws IOException {
    closeAction.sendCloseMessage(pgStream);
  }

  public void readStartupMessages() throws IOException, SQLException {
    for (int i = 0; i < 1000; i++) {
      int beresp = pgStream.receiveChar();
      switch (beresp) {
        case PgMessageType.READY_FOR_QUERY_RESPONSE:
          receiveRFQ();
          // Ready For Query; we're done.
          return;

        case PgMessageType.BACKEND_KEY_DATA_RESPONSE:
          // BackendKeyData
          int msgLen = pgStream.receiveInteger4();
          int pid = pgStream.receiveInteger4();
          int keyLen = msgLen - 8;
          byte[] ckey;
          if (ProtocolVersion.v3_0.equals(protocolVersion)) {
            if (keyLen != 4) {
              throw new PSQLException(GT.tr("Protocol error. Cancel Key should be 4 bytes for protocol version {0},"
                  + " but received {1} bytes. Session setup failed.", ProtocolVersion.v3_0, keyLen),
                  PSQLState.PROTOCOL_VIOLATION);
            }
          }
          if (ProtocolVersion.v3_2.equals(protocolVersion)) {
            if (keyLen > 256) {
              throw new PSQLException(GT.tr(
                  "Protocol error. Cancel Key cannot be greater than 256 for protocol version {0},"
                      + " but received {1} bytes. Session setup failed.",
                  ProtocolVersion.v3_2, keyLen),
                  PSQLState.PROTOCOL_VIOLATION);
            }
          }
          ckey = pgStream.receive(keyLen);

          if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, " <=BE BackendKeyData(pid={0},ckey={1})", new Object[]{pid, ckey});
          }

          setBackendKeyData(pid, ckey);
          break;

        case PgMessageType.ERROR_RESPONSE:
          // Error
          throw receiveErrorResponse();

        case PgMessageType.NOTICE_RESPONSE:
          // Warning
          addWarning(receiveNoticeResponse());
          break;

        case PgMessageType.PARAMETER_STATUS_RESPONSE:
          // ParameterStatus
          receiveParameterStatus();

          break;

        default:
          if (LOGGER.isLoggable(Level.FINEST)) {
            LOGGER.log(Level.FINEST, "  invalid message type={0}", (char) beresp);
          }
          throw new PSQLException(GT.tr("Protocol error.  Session setup failed."),
              PSQLState.PROTOCOL_VIOLATION);
      }
    }
    throw new PSQLException(GT.tr("Protocol error.  Session setup failed."),
        PSQLState.PROTOCOL_VIOLATION);
  }

  public void receiveParameterStatus() throws IOException, SQLException {
    // ParameterStatus
    pgStream.receiveInteger4(); // MESSAGE SIZE
    final String name = pgStream.receiveCanonicalStringIfPresent();
    final String value = pgStream.receiveCanonicalStringIfPresent();

    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " <=BE ParameterStatus({0} = {1})", new Object[]{name, value});
    }

    // if the name is empty, there is nothing to do
    if (name.isEmpty()) {
      return;
    }

    // Update client-visible parameter status map for getParameterStatuses()
    onParameterStatus(name, value);

    if ("client_encoding".equals(name)) {
      if (allowEncodingChanges) {
        if (!"UTF8".equalsIgnoreCase(value) && !"UTF-8".equalsIgnoreCase(value)) {
          LOGGER.log(Level.FINE,
              "pgjdbc expects client_encoding to be UTF8 for proper operation. Actual encoding is {0}",
              value);
        }
        pgStream.setEncoding(Encoding.getDatabaseEncoding(value));
      } else if (!"UTF8".equalsIgnoreCase(value) && !"UTF-8".equalsIgnoreCase(value)) {
        close(); // we're screwed now; we can't trust any subsequent string.
        throw new PSQLException(GT.tr(
            "The server''s client_encoding parameter was changed to {0}. The JDBC driver requires client_encoding to be UTF8 for correct operation.",
            value), PSQLState.CONNECTION_FAILURE);

      }
    }

    if ("DateStyle".equals(name) && !value.startsWith("ISO")
        && !value.toUpperCase(Locale.ROOT).startsWith("ISO")) {
      close(); // we're screwed now; we can't trust any subsequent date.
      throw new PSQLException(GT.tr(
          "The server''s DateStyle parameter was changed to {0}. The JDBC driver requires DateStyle to begin with ISO for correct operation.",
          value), PSQLState.CONNECTION_FAILURE);
    }

    if ("standard_conforming_strings".equals(name)) {
      if ("on".equals(value)) {
        setStandardConformingStrings(true);
      } else if ("off".equals(value)) {
        setStandardConformingStrings(false);
      } else {
        close();
        // we're screwed now; we don't know how to escape string literals
        throw new PSQLException(GT.tr(
            "The server''s standard_conforming_strings parameter was reported as {0}. The JDBC driver expected on or off.",
            value), PSQLState.CONNECTION_FAILURE);
      }
      return;
    }

    if ("TimeZone".equals(name)) {
      setTimeZone(TimestampUtils.parseBackendTimeZone(value));
    } else if ("application_name".equals(name)) {
      setApplicationName(value);
    } else if ("server_version_num".equals(name)) {
      setServerVersionNum(Integer.parseInt(value));
    } else if ("server_version".equals(name)) {
      setServerVersion(value);
    }  else if ("integer_datetimes".equals(name)) {
      if ("on".equals(value)) {
        setIntegerDateTimes(true);
      } else if ("off".equals(value)) {
        setIntegerDateTimes(false);
      } else {
        throw new PSQLException(GT.tr("Protocol error.  Session setup failed."),
            PSQLState.PROTOCOL_VIOLATION);
      }
    }
  }

  public void setTimeZone(TimeZone timeZone) {
    this.timeZone = timeZone;
  }

  @Override
  public @Nullable TimeZone getTimeZone() {
    return timeZone;
  }

  public void setApplicationName(String applicationName) {
    this.applicationName = applicationName;
  }

  @Override
  public String getApplicationName() {
    if (applicationName == null) {
      return "";
    }
    return applicationName;
  }

  @Override
  public ReplicationProtocol getReplicationProtocol() {
    return replicationProtocol;
  }

  @Override
  public void addBinaryReceiveOid(int oid) {
    synchronized (useBinaryReceiveForOids) {
      useBinaryReceiveForOids.add(oid);
    }
  }

  @Override
  public void removeBinaryReceiveOid(int oid) {
    synchronized (useBinaryReceiveForOids) {
      useBinaryReceiveForOids.remove(oid);
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public Set<? extends Integer> getBinaryReceiveOids() {
    // copy the values to prevent ConcurrentModificationException when reader accesses the elements
    synchronized (useBinaryReceiveForOids) {
      return useBinaryReceiveForOids.toMutableSet();
    }
  }

  @Override
  public boolean useBinaryForReceive(int oid) {
    synchronized (useBinaryReceiveForOids) {
      return useBinaryReceiveForOids.contains(oid);
    }
  }

  @Override
  public void setBinaryReceiveOids(Set<Integer> oids) {
    synchronized (useBinaryReceiveForOids) {
      useBinaryReceiveForOids.clear();
      useBinaryReceiveForOids.addAll(oids);
    }
  }

  @Override
  public void addBinarySendOid(int oid) {
    synchronized (useBinarySendForOids) {
      useBinarySendForOids.add(oid);
    }
  }

  @Override
  public void removeBinarySendOid(int oid) {
    synchronized (useBinarySendForOids) {
      useBinarySendForOids.remove(oid);
    }
  }

  @Override
  @SuppressWarnings("deprecation")
  public Set<? extends Integer> getBinarySendOids() {
    // copy the values to prevent ConcurrentModificationException when reader accesses the elements
    synchronized (useBinarySendForOids) {
      return useBinarySendForOids.toMutableSet();
    }
  }

  @Override
  public boolean useBinaryForSend(int oid) {
    synchronized (useBinarySendForOids) {
      return useBinarySendForOids.contains(oid);
    }
  }

  @Override
  public void setBinarySendOids(Set<Integer> oids) {
    synchronized (useBinarySendForOids) {
      useBinarySendForOids.clear();
      useBinarySendForOids.addAll(oids);
    }
  }

  private void setIntegerDateTimes(boolean state) {
    integerDateTimes = state;
  }

  @Override
  public boolean getIntegerDateTimes() {
    return integerDateTimes;
  }

  private final Deque<SimpleQuery> pendingParseQueue = new ArrayDeque<>();
  private final Deque<Portal> pendingBindQueue = new ArrayDeque<>();
  private final Deque<ExecuteRequest> pendingExecuteQueue = new ArrayDeque<>();
  private final Deque<DescribeRequest> pendingDescribeStatementQueue =
      new ArrayDeque<>();
  private final Deque<SimpleQuery> pendingDescribePortalQueue = new ArrayDeque<>();

  private long nextUniqueID = 1;
  private final boolean allowEncodingChanges;
  private final boolean cleanupSavePoints;

  /**
   * The estimated server response size since we last consumed the input stream from the server, in
   * bytes.
   *
   * <p>Starts at zero, reset by every Sync message. Mainly used for batches.</p>
   *
   * <p>Used to avoid deadlocks, see MAX_BUFFERED_RECV_BYTES.</p>
   */
  private int estimatedReceiveBufferBytes;

  private final SimpleQuery beginTransactionQuery =
      new SimpleQuery(
          new NativeQuery("BEGIN", null, false, SqlCommand.BLANK),
          null, false);

  private final SimpleQuery beginReadOnlyTransactionQuery =
      new SimpleQuery(
          new NativeQuery("BEGIN READ ONLY", null, false, SqlCommand.BLANK),
          null, false);

  private final SimpleQuery emptyQuery =
      new SimpleQuery(
          new NativeQuery("", null, false,
              SqlCommand.createStatementTypeInfo(SqlCommandType.BLANK)
          ), null, false);

  private final SimpleQuery autoSaveQuery =
      new SimpleQuery(
          new NativeQuery("SAVEPOINT PGJDBC_AUTOSAVE", null, false, SqlCommand.BLANK),
          null, false);

  private final SimpleQuery releaseAutoSave =
      new SimpleQuery(
          new NativeQuery("RELEASE SAVEPOINT PGJDBC_AUTOSAVE", null, false, SqlCommand.BLANK),
          null, false);

  /*
  In autosave mode we use this query to roll back errored transactions
   */
  private final SimpleQuery restoreToAutoSave =
      new SimpleQuery(
          new NativeQuery("ROLLBACK TO SAVEPOINT PGJDBC_AUTOSAVE", null, false, SqlCommand.BLANK),
          null, false);
}
