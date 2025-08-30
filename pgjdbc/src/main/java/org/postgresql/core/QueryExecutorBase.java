/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.PGNotification;
import org.postgresql.PGProperty;
import org.postgresql.jdbc.AutoSave;
import org.postgresql.jdbc.EscapeSyntaxCallMode;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.jdbc.ResourceLock;
import org.postgresql.util.HostSpec;
import org.postgresql.util.LruCache;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

import org.checkerframework.checker.lock.qual.Holding;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.locks.Condition;
import java.util.logging.Level;
import java.util.logging.Logger;

public abstract class QueryExecutorBase implements QueryExecutor {

  private static final Logger LOGGER = Logger.getLogger(QueryExecutorBase.class.getName());
  protected final PGStream pgStream;
  private final String user;
  private final String database;
  private final int cancelSignalTimeout;

  protected ProtocolVersion protocolVersion;
  private int cancelPid;
  private byte @Nullable[] cancelKey;
  protected final QueryExecutorCloseAction closeAction;
  private @MonotonicNonNull String serverVersion;
  private int serverVersionNum;
  private TransactionState transactionState = TransactionState.IDLE;
  private final boolean reWriteBatchedInserts;
  private final boolean columnSanitiserDisabled;
  private final EscapeSyntaxCallMode escapeSyntaxCallMode;
  private final boolean quoteReturningIdentifiers;
  private PreferQueryMode preferQueryMode;
  private AutoSave autoSave;
  private boolean flushCacheOnDeallocate = true;
  protected final boolean logServerErrorDetail;

  // default value for server versions that don't report standard_conforming_strings
  private boolean standardConformingStrings;

  private @Nullable SQLWarning warnings;
  private final ArrayList<PGNotification> notifications = new ArrayList<>();

  private final LruCache<Object, CachedQuery> statementCache;
  private final CachedQueryCreateAction cachedQueryCreateAction;

  // For getParameterStatuses(), GUC_REPORT tracking
  private final TreeMap<String,String> parameterStatuses
      = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  protected final ResourceLock lock = new ResourceLock();
  protected final Condition lockCondition = lock.newCondition();

  @SuppressWarnings({"assignment", "argument", "method.invocation"})
  protected QueryExecutorBase(PGStream pgStream, int cancelSignalTimeout, Properties info) throws SQLException {
    this.pgStream = pgStream;
    this.protocolVersion = pgStream.getProtocolVersion();
    this.user = PGProperty.USER.getOrDefault(info);
    this.database = PGProperty.PG_DBNAME.getOrDefault(info);
    this.cancelSignalTimeout = cancelSignalTimeout;
    this.reWriteBatchedInserts = PGProperty.REWRITE_BATCHED_INSERTS.getBoolean(info);
    this.columnSanitiserDisabled = PGProperty.DISABLE_COLUMN_SANITISER.getBoolean(info);
    String callMode = PGProperty.ESCAPE_SYNTAX_CALL_MODE.getOrDefault(info);
    this.escapeSyntaxCallMode = EscapeSyntaxCallMode.of(callMode);
    this.quoteReturningIdentifiers = PGProperty.QUOTE_RETURNING_IDENTIFIERS.getBoolean(info);
    String preferMode = PGProperty.PREFER_QUERY_MODE.getOrDefault(info);
    this.preferQueryMode = PreferQueryMode.of(preferMode);
    this.autoSave = AutoSave.of(PGProperty.AUTOSAVE.getOrDefault(info));
    this.logServerErrorDetail = PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(info);
    // assignment, argument
    this.cachedQueryCreateAction = new CachedQueryCreateAction(this);
    statementCache = new LruCache<>(
        Math.max(0, PGProperty.PREPARED_STATEMENT_CACHE_QUERIES.getInt(info)),
        Math.max(0, PGProperty.PREPARED_STATEMENT_CACHE_SIZE_MIB.getInt(info) * 1024L * 1024L),
        false,
        cachedQueryCreateAction,
        new LruCache.EvictAction<CachedQuery>() {
          @Override
          public void evict(CachedQuery cachedQuery) throws SQLException {
            cachedQuery.query.close();
          }
        });
    // method.invocation
    this.closeAction = createCloseAction();
  }

  protected QueryExecutorCloseAction createCloseAction() {
    return new QueryExecutorCloseAction(pgStream);
  }

  /**
   * Sends "terminate connection" message to the backend.
   * @throws IOException in case connection termination fails
   * @deprecated use {@link #getCloseAction()} instead
   */
  @Deprecated
  protected abstract void sendCloseMessage() throws IOException;

  @Override
  public void setNetworkTimeout(int milliseconds) throws IOException {
    pgStream.setNetworkTimeout(milliseconds);
  }

  @Override
  public int getNetworkTimeout() throws IOException {
    return pgStream.getNetworkTimeout();
  }

  @Override
  public HostSpec getHostSpec() {
    return pgStream.getHostSpec();
  }

  @Override
  public String getUser() {
    return user;
  }

  @Override
  public String getDatabase() {
    return database;
  }

  public void setBackendKeyData(int cancelPid, byte[] cancelKey) {
    this.cancelPid = cancelPid;
    this.cancelKey = cancelKey;
  }

  @Override
  public int getBackendPID() {
    return cancelPid;
  }

  @Override
  public void abort() {
    closeAction.abort();
  }

  @Override
  public Closeable getCloseAction() {
    return closeAction;
  }

  @Override
  public void close() {
    if (closeAction.isClosed()) {
      return;
    }

    try {
      getCloseAction().close();
    } catch (IOException ioe) {
      LOGGER.log(Level.FINEST, "Discarding IOException on close:", ioe);
    }
  }

  @Override
  public boolean isClosed() {
    return closeAction.isClosed();
  }

  @Override
  public void sendQueryCancel() throws SQLException {

    PGStream cancelStream = null;

    // Now we need to construct and send a cancel packet
    try {
      byte[] cancelKey = this.cancelKey;
      if (cancelKey == null) {
        LOGGER.log(Level.FINEST, " FE=> Can't send cancel request since cancelKey is null. It might be the cancel key is not received yet");
        return;
      }
      if (LOGGER.isLoggable(Level.FINEST)) {
        LOGGER.log(Level.FINEST, " FE=> CancelRequest(pid={0},ckey={1})", new Object[]{cancelPid, cancelKey});
      }

      // Cancel signal is variable since protocol 3.2 so we use cancelKey.length + 12
      cancelStream =
          new PGStream(pgStream.getSocketFactory(), pgStream.getHostSpec(), cancelSignalTimeout, cancelKey.length + 12);
      if (cancelSignalTimeout > 0) {
        cancelStream.setNetworkTimeout(cancelSignalTimeout);
      }
      // send the length including self
      cancelStream.sendInteger4(cancelKey.length + 12);
      cancelStream.sendInteger2(1234);
      cancelStream.sendInteger2(5678);
      cancelStream.sendInteger4(cancelPid);
      cancelStream.send(cancelKey);
      cancelStream.flush();
      cancelStream.receiveEOF();
    } catch (IOException e) {
      // Safe to ignore.
      LOGGER.log(Level.FINEST, "Ignoring exception on cancel request:", e);
    } finally {
      if (cancelStream != null) {
        try {
          cancelStream.close();
        } catch (IOException e) {
          // Ignored.
        }
      }
    }
  }

  public void addWarning(SQLWarning newWarning) {
    try (ResourceLock ignore = lock.obtain()) {
      if (warnings == null) {
        warnings = newWarning;
      } else {
        warnings.setNextWarning(newWarning);
      }
    }
  }

  public void addNotification(PGNotification notification) {
    try (ResourceLock ignore = lock.obtain()) {
      notifications.add(notification);
    }
  }

  @Override
  public PGNotification[] getNotifications() throws SQLException {
    try (ResourceLock ignore = lock.obtain()) {
      PGNotification[] array = notifications.toArray(new PGNotification[0]);
      notifications.clear();
      return array;
    }
  }

  @Override
  public @Nullable SQLWarning getWarnings() {
    try (ResourceLock ignore = lock.obtain()) {
      SQLWarning chain = warnings;
      warnings = null;
      return chain;
    }
  }

  @Override
  public String getServerVersion() {
    String serverVersion = this.serverVersion;
    if (serverVersion == null) {
      throw new IllegalStateException("serverVersion must not be null");
    }
    return serverVersion;
  }

  @Override
  public int getServerVersionNum() {
    if (serverVersionNum != 0) {
      return serverVersionNum;
    }
    serverVersionNum = Utils.parseServerVersionStr(getServerVersion());
    return serverVersionNum;
  }

  public void setServerVersion(String serverVersion) {
    this.serverVersion = serverVersion;
  }

  public void setServerVersionNum(int serverVersionNum) {
    this.serverVersionNum = serverVersionNum;
  }

  @Holding("lock")
  public void setTransactionState(TransactionState state) {
    transactionState = state;
  }

  public void setStandardConformingStrings(boolean value) {
    try (ResourceLock ignore = lock.obtain()) {
      standardConformingStrings = value;
    }
  }

  @Override
  public boolean getStandardConformingStrings() {
    try (ResourceLock ignore = lock.obtain()) {
      return standardConformingStrings;
    }
  }

  @Override
  public boolean getQuoteReturningIdentifiers() {
    return quoteReturningIdentifiers;
  }

  @Override
  public TransactionState getTransactionState() {
    try (ResourceLock ignore = lock.obtain()) {
      return transactionState;
    }
  }

  public void setEncoding(Encoding encoding) throws IOException {
    pgStream.setEncoding(encoding);
  }

  @Override
  public Encoding getEncoding() {
    return pgStream.getEncoding();
  }

  @Override
  public boolean isReWriteBatchedInsertsEnabled() {
    return this.reWriteBatchedInserts;
  }

  @Override
  public final CachedQuery borrowQuery(String sql) throws SQLException {
    return statementCache.borrow(sql);
  }

  @Override
  public final CachedQuery borrowCallableQuery(String sql) throws SQLException {
    return statementCache.borrow(new CallableQueryKey(sql));
  }

  @Override
  public final CachedQuery borrowReturningQuery(String sql, String @Nullable [] columnNames)
      throws SQLException {
    return statementCache.borrow(new QueryWithReturningColumnsKey(sql, true, true,
        columnNames
    ));
  }

  @Override
  public CachedQuery borrowQueryByKey(Object key) throws SQLException {
    return statementCache.borrow(key);
  }

  @Override
  public void releaseQuery(CachedQuery cachedQuery) {
    statementCache.put(cachedQuery.key, cachedQuery);
  }

  @Override
  public final Object createQueryKey(String sql, boolean escapeProcessing,
      boolean isParameterized, String @Nullable ... columnNames) {
    Object key;
    if (columnNames == null || columnNames.length != 0) {
      // Null means "return whatever sensible columns are" (e.g. primary key, or serial, or something like that)
      key = new QueryWithReturningColumnsKey(sql, isParameterized, escapeProcessing, columnNames);
    } else if (isParameterized) {
      // If no generated columns requested, just use the SQL as a cache key
      key = sql;
    } else {
      key = new BaseQueryKey(sql, false, escapeProcessing);
    }
    return key;
  }

  @Override
  public CachedQuery createQueryByKey(Object key) throws SQLException {
    return cachedQueryCreateAction.create(key);
  }

  @Override
  public final CachedQuery createQuery(String sql, boolean escapeProcessing,
      boolean isParameterized, String @Nullable ... columnNames)
      throws SQLException {
    Object key = createQueryKey(sql, escapeProcessing, isParameterized, columnNames);
    // Note: cache is not reused here for two reasons:
    //   1) Simplify initial implementation for simple statements
    //   2) Non-prepared statements are likely to have literals, thus query reuse would not be often
    return createQueryByKey(key);
  }

  @Override
  public boolean isColumnSanitiserDisabled() {
    return columnSanitiserDisabled;
  }

  @Override
  public EscapeSyntaxCallMode getEscapeSyntaxCallMode() {
    return escapeSyntaxCallMode;
  }

  @Override
  public PreferQueryMode getPreferQueryMode() {
    return preferQueryMode;
  }

  @Override
  public void setPreferQueryMode(PreferQueryMode mode) {
    preferQueryMode = mode;
  }

  @Override
  public AutoSave getAutoSave() {
    return autoSave;
  }

  @Override
  public void setAutoSave(AutoSave autoSave) {
    this.autoSave = autoSave;
  }

  protected boolean willHealViaReparse(SQLException e) {
    if (e == null || e.getSQLState() == null) {
      return false;
    }

    // "prepared statement \"S_2\" does not exist"
    if (PSQLState.INVALID_SQL_STATEMENT_NAME.getState().equals(e.getSQLState())) {
      return true;
    }
    if (!PSQLState.NOT_IMPLEMENTED.getState().equals(e.getSQLState())) {
      return false;
    }

    if (!(e instanceof PSQLException)) {
      return false;
    }

    PSQLException pe = (PSQLException) e;

    ServerErrorMessage serverErrorMessage = pe.getServerErrorMessage();
    if (serverErrorMessage == null) {
      return false;
    }
    // "cached plan must not change result type"
    String routine = serverErrorMessage.getRoutine();
    return "RevalidateCachedQuery".equals(routine) // 9.2+
        || "RevalidateCachedPlan".equals(routine); // <= 9.1
  }

  @Override
  public boolean willHealOnRetry(SQLException e) {
    if (autoSave == AutoSave.NEVER && getTransactionState() == TransactionState.FAILED) {
      // If autorollback is not activated, then every statement will fail with
      // 'transaction is aborted', etc, etc
      return false;
    }
    return willHealViaReparse(e);
  }

  public boolean isFlushCacheOnDeallocate() {
    return flushCacheOnDeallocate;
  }

  @Override
  public void setFlushCacheOnDeallocate(boolean flushCacheOnDeallocate) {
    this.flushCacheOnDeallocate = flushCacheOnDeallocate;
  }

  protected boolean hasNotifications() {
    return !notifications.isEmpty();
  }

  @Override
  public final Map<String, String> getParameterStatuses() {
    return Collections.unmodifiableMap(parameterStatuses);
  }

  @Override
  public final @Nullable String getParameterStatus(String parameterName) {
    return parameterStatuses.get(parameterName);
  }

  /**
   * Update the parameter status map in response to a new ParameterStatus
   * wire protocol message.
   *
   * <p>The server sends ParameterStatus messages when GUC_REPORT settings are
   * initially assigned and whenever they change.</p>
   *
   * <p>A future version may invoke a client-defined listener class at this point,
   * so this should be the only access path.</p>
   *
   * <p>Keys are case-insensitive and case-preserving.</p>
   *
   * <p>The server doesn't provide a way to report deletion of a reportable
   * parameter so we don't expose one here.</p>
   *
   * @param parameterName case-insensitive case-preserving name of parameter to create or update
   * @param parameterStatus new value of parameter
   * @see org.postgresql.PGConnection#getParameterStatuses
   * @see org.postgresql.PGConnection#getParameterStatus
   */
  protected void onParameterStatus(String parameterName, String parameterStatus) {
    if (parameterName == null || "".equals(parameterName)) {
      throw new IllegalStateException("attempt to set GUC_REPORT parameter with null or empty-string name");
    }

    parameterStatuses.put(parameterName, parameterStatus);
  }
}
