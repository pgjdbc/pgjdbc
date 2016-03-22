/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2015, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/

package org.postgresql.jdbc;

import org.postgresql.Driver;
import org.postgresql.PGNotification;
import org.postgresql.PGProperty;
import org.postgresql.copy.CopyManager;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.BaseStatement;
import org.postgresql.core.CachedQuery;
import org.postgresql.core.ConnectionFactory;
import org.postgresql.core.Encoding;
import org.postgresql.core.Field;
import org.postgresql.core.Logger;
import org.postgresql.core.Oid;
import org.postgresql.core.ProtocolConnection;
import org.postgresql.core.Provider;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultCursor;
import org.postgresql.core.ResultHandler;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.TypeInfo;
import org.postgresql.core.Utils;
import org.postgresql.core.Version;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.LruCache;
import org.postgresql.util.PGBinaryObject;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import java.io.IOException;
import java.sql.Array;
import java.sql.Blob;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLPermission;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executor;

public class PgConnection implements BaseConnection {

  private static final SQLPermission SQL_PERMISSION_ABORT = new SQLPermission("callAbort");
  /**
   * Driver-wide connection ID counter, used for logging
   */
  private static int nextConnectionID = 1;

  //
  // Data initialized on construction:
  //

  private final Properties _clientInfo;
  // Per-connection logger
  private final Logger logger;

  /* URL we were created via */
  private final String creatingURL;

  private Throwable openStackTrace;

  /* Actual network handler */
  private final ProtocolConnection protoConnection;
  /* Compatible version as xxyyzz form */
  private final int compatibleInt;

  /* Query that runs COMMIT */
  private final Query commitQuery;
  /* Query that runs ROLLBACK */
  private final Query rollbackQuery;

  private TypeInfo _typeCache;

  private boolean disableColumnSanitiser = false;

  // Default statement prepare threshold.
  protected int prepareThreshold;

  /**
   * Default fetch size for statement
   *
   * @see PGProperty#DEFAULT_ROW_FETCH_SIZE
   */
  protected int defaultFetchSize;

  // Default forcebinary option.
  protected boolean forcebinary = false;

  private int rsHoldability = ResultSet.CLOSE_CURSORS_AT_COMMIT;
  private int savepointId = 0;
  // Connection's autocommit state.
  private boolean autoCommit = true;
  // Connection's readonly state.
  private boolean readOnly = false;

  // Bind String to UNSPECIFIED or VARCHAR?
  private final boolean bindStringAsVarchar;

  // Current warnings; there might be more on protoConnection too.
  private SQLWarning firstWarning = null;

  /**
   * Set of oids that use binary transfer when sending to server.
   */
  private Set<Integer> useBinarySendForOids;
  /**
   * Set of oids that use binary transfer when receiving from server.
   */
  private Set<Integer> useBinaryReceiveForOids;

  // Timer for scheduling TimerTasks for this connection.
  // Only instantiated if a task is actually scheduled.
  private volatile Timer cancelTimer = null;

  // In general, it is not expected that connection would be used in multiple threads concurrently
  // However, if client performs cleanup of statements via finalizers/phantomreferences, then it
  // might
  // result in AbstractJdbc2Statement.close to be called concurrently with active operation on
  // connection
  // Thus we synchronize access to avoid java.util.ConcurrentModificationException
  private final LruCache<Object, CachedQuery> statementCache;

  CachedQuery borrowQuery(String sql, boolean isCallable) throws SQLException {
    Object key = isCallable ? new CallableQueryKey(sql) : sql;
    // Synchronized to fix #368
    synchronized (statementCache) {
      return statementCache.borrow(key);
    }
  }

  void releaseQuery(CachedQuery cachedQuery) {
    // Synchronized to fix #368
    synchronized (statementCache) {
      statementCache.put(cachedQuery.key, cachedQuery);
    }
  }

  //
  // Ctor.
  //
  public PgConnection(HostSpec[] hostSpecs, String user, String database, Properties info,
      String url) throws SQLException {
    this.creatingURL = url;

    // Read loglevel arg and set the loglevel based on this value
    // otherwise use the programmatic or init logger level;
    // In addition to setting the log level, enable output to
    // standard out if no other printwriter is set

    int logLevel = Driver.getLogLevel();

    String connectionLogLevel = PGProperty.LOG_LEVEL.getSetString(info);
    if (connectionLogLevel != null) {
      try {
        logLevel = Integer.parseInt(connectionLogLevel);
      } catch (NumberFormatException nfe) {
        // ignore
      }
    }
    synchronized (PgConnection.class) {
      logger = new Logger(nextConnectionID++);
      logger.setLogLevel(logLevel);
    }

    setDefaultFetchSize(PGProperty.DEFAULT_ROW_FETCH_SIZE.getInt(info));

    prepareThreshold = PGProperty.PREPARE_THRESHOLD.getInt(info);
    if (prepareThreshold == -1) {
      forcebinary = true;
    }

    boolean binaryTransfer = PGProperty.BINARY_TRANSFER.getBoolean(info);

    // Print out the driver version number
    if (logger.logInfo()) {
      logger.info(Driver.getVersion());
    }

    // Now make the initial connection and set up local state
    this.protoConnection =
        ConnectionFactory.openConnection(hostSpecs, user, database, info, logger);
    int compat = Utils.parseServerVersionStr(PGProperty.COMPATIBLE.get(info));
    if (compat == 0) {
      compat = Driver.MAJORVERSION * 10000 + Driver.MINORVERSION * 100;
    }
    this.compatibleInt = compat;

    // Set read-only early if requested
    if (PGProperty.READ_ONLY.getBoolean(info)) {
      setReadOnly(true);
    }

    // Formats that currently have binary protocol support
    Set<Integer> binaryOids = new HashSet<Integer>();
    if (binaryTransfer && protoConnection.getProtocolVersion() >= 3) {
      binaryOids.add(Oid.BYTEA);
      binaryOids.add(Oid.INT2);
      binaryOids.add(Oid.INT4);
      binaryOids.add(Oid.INT8);
      binaryOids.add(Oid.FLOAT4);
      binaryOids.add(Oid.FLOAT8);
      binaryOids.add(Oid.TIME);
      binaryOids.add(Oid.DATE);
      binaryOids.add(Oid.TIMETZ);
      binaryOids.add(Oid.TIMESTAMP);
      binaryOids.add(Oid.TIMESTAMPTZ);
      binaryOids.add(Oid.INT2_ARRAY);
      binaryOids.add(Oid.INT4_ARRAY);
      binaryOids.add(Oid.INT8_ARRAY);
      binaryOids.add(Oid.FLOAT4_ARRAY);
      binaryOids.add(Oid.FLOAT8_ARRAY);
      binaryOids.add(Oid.FLOAT8_ARRAY);
      binaryOids.add(Oid.VARCHAR_ARRAY);
      binaryOids.add(Oid.TEXT_ARRAY);
      binaryOids.add(Oid.POINT);
      binaryOids.add(Oid.BOX);
      binaryOids.add(Oid.UUID);
    }
    // the pre 8.0 servers do not disclose their internal encoding for
    // time fields so do not try to use them.
    if (!haveMinimumCompatibleVersion(ServerVersion.v8_0)) {
      binaryOids.remove(Oid.TIME);
      binaryOids.remove(Oid.TIMETZ);
      binaryOids.remove(Oid.TIMESTAMP);
      binaryOids.remove(Oid.TIMESTAMPTZ);
    }
    // driver supports only null-compatible arrays
    if (!haveMinimumCompatibleVersion(ServerVersion.v8_3)) {
      binaryOids.remove(Oid.INT2_ARRAY);
      binaryOids.remove(Oid.INT4_ARRAY);
      binaryOids.remove(Oid.INT8_ARRAY);
      binaryOids.remove(Oid.FLOAT4_ARRAY);
      binaryOids.remove(Oid.FLOAT8_ARRAY);
      binaryOids.remove(Oid.FLOAT8_ARRAY);
      binaryOids.remove(Oid.VARCHAR_ARRAY);
      binaryOids.remove(Oid.TEXT_ARRAY);
    }

    binaryOids.addAll(getOidSet(PGProperty.BINARY_TRANSFER_ENABLE.get(info)));
    binaryOids.removeAll(getOidSet(PGProperty.BINARY_TRANSFER_DISABLE.get(info)));

    // split for receive and send for better control
    useBinarySendForOids = new HashSet<Integer>();
    useBinarySendForOids.addAll(binaryOids);
    useBinaryReceiveForOids = new HashSet<Integer>();
    useBinaryReceiveForOids.addAll(binaryOids);

    /*
     * Does not pass unit tests because unit tests expect setDate to have millisecond accuracy
     * whereas the binary transfer only supports date accuracy.
     */
    useBinarySendForOids.remove(Oid.DATE);

    protoConnection.setBinaryReceiveOids(useBinaryReceiveForOids);

    if (logger.logDebug()) {
      logger.debug("    compatible = " + compatibleInt);
      logger.debug("    loglevel = " + logLevel);
      logger.debug("    prepare threshold = " + prepareThreshold);
      logger.debug("    types using binary send = " + oidsToString(useBinarySendForOids));
      logger.debug("    types using binary receive = " + oidsToString(useBinaryReceiveForOids));
      logger.debug("    integer date/time = " + protoConnection.getIntegerDateTimes());
    }

    //
    // String -> text or unknown?
    //

    String stringType = PGProperty.STRING_TYPE.get(info);
    if (stringType != null) {
      if (stringType.equalsIgnoreCase("unspecified")) {
        bindStringAsVarchar = false;
      } else if (stringType.equalsIgnoreCase("varchar")) {
        bindStringAsVarchar = true;
      } else {
        throw new PSQLException(
            GT.tr("Unsupported value for stringtype parameter: {0}", stringType),
            PSQLState.INVALID_PARAMETER_VALUE);
      }
    } else {
      bindStringAsVarchar = haveMinimumCompatibleVersion(ServerVersion.v8_0);
    }

    // Initialize timestamp stuff
    timestampUtils = new TimestampUtils(haveMinimumServerVersion(ServerVersion.v7_4),
        haveMinimumServerVersion(ServerVersion.v8_2), !protoConnection.getIntegerDateTimes(),
        new Provider<TimeZone>() {
          @Override
          public TimeZone get() {
            return protoConnection.getTimeZone();
          }
        });

    // Initialize common queries.
    commitQuery = getQueryExecutor().createSimpleQuery("COMMIT");
    rollbackQuery = getQueryExecutor().createSimpleQuery("ROLLBACK");

    int unknownLength = PGProperty.UNKNOWN_LENGTH.getInt(info);

    // Initialize object handling
    _typeCache = createTypeInfo(this, unknownLength);
    initObjectTypes(info);

    if (PGProperty.LOG_UNCLOSED_CONNECTIONS.getBoolean(info)) {
      openStackTrace = new Throwable("Connection was created at this point:");
    }
    this.disableColumnSanitiser = PGProperty.DISABLE_COLUMN_SANITISER.getBoolean(info);
    statementCache = new LruCache<Object, CachedQuery>(
        Math.max(0, PGProperty.PREPARED_STATEMENT_CACHE_QUERIES.getInt(info)),
        Math.max(0, PGProperty.PREPARED_STATEMENT_CACHE_SIZE_MIB.getInt(info) * 1024 * 1024),
        new CachedQueryCreateAction(this, protoConnection.getServerVersionNum()),
        new LruCache.EvictAction<CachedQuery>() {
          @Override
          public void evict(CachedQuery cachedQuery) throws SQLException {
            cachedQuery.query.close();
          }
        });

    TypeInfo types1 = getTypeInfo();
    if (haveMinimumServerVersion(ServerVersion.v8_3)) {
      types1.addCoreType("uuid", Oid.UUID, Types.OTHER, "java.util.UUID", Oid.UUID_ARRAY);
    }

    TypeInfo types = getTypeInfo();
    if (haveMinimumServerVersion(ServerVersion.v8_3)) {
      types.addCoreType("xml", Oid.XML, Types.SQLXML, "java.sql.SQLXML", Oid.XML_ARRAY);
    }

    this._clientInfo = new Properties();
    if (haveMinimumServerVersion(ServerVersion.v9_0)) {
      String appName = PGProperty.APPLICATION_NAME.get(info);
      if (appName == null) {
        appName = "";
      }
      this._clientInfo.put("ApplicationName", appName);
    }
  }

  private Set<Integer> getOidSet(String oidList) throws PSQLException {
    Set<Integer> oids = new HashSet<Integer>();
    StringTokenizer tokenizer = new StringTokenizer(oidList, ",");
    while (tokenizer.hasMoreTokens()) {
      String oid = tokenizer.nextToken();
      oids.add(Oid.valueOf(oid));
    }
    return oids;
  }

  private String oidsToString(Set<Integer> oids) {
    StringBuilder sb = new StringBuilder();
    for (Integer oid : oids) {
      sb.append(Oid.toString(oid));
      sb.append(',');
    }
    if (sb.length() > 0) {
      sb.setLength(sb.length() - 1);
    } else {
      sb.append(" <none>");
    }
    return sb.toString();
  }

  private final TimestampUtils timestampUtils;

  public TimestampUtils getTimestampUtils() {
    return timestampUtils;
  }

  /**
   * The current type mappings
   */
  protected Map<String, Class<?>> typemap;

  public java.sql.Statement createStatement() throws SQLException {
    // We now follow the spec and default to TYPE_FORWARD_ONLY.
    return createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY,
        java.sql.ResultSet.CONCUR_READ_ONLY);
  }

  public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
    return prepareStatement(sql, java.sql.ResultSet.TYPE_FORWARD_ONLY,
        java.sql.ResultSet.CONCUR_READ_ONLY);
  }

  public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
    return prepareCall(sql, java.sql.ResultSet.TYPE_FORWARD_ONLY,
        java.sql.ResultSet.CONCUR_READ_ONLY);
  }

  public Map<String, Class<?>> getTypeMap() throws SQLException {
    checkClosed();
    return typemap;
  }

  public QueryExecutor getQueryExecutor() {
    return protoConnection.getQueryExecutor();
  }

  /**
   * This adds a warning to the warning chain.
   *
   * @param warn warning to add
   */
  public void addWarning(SQLWarning warn) {
    // Add the warning to the chain
    if (firstWarning != null) {
      firstWarning.setNextWarning(warn);
    } else {
      firstWarning = warn;
    }

  }

  public ResultSet execSQLQuery(String s) throws SQLException {
    return execSQLQuery(s, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
  }

  public ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    BaseStatement stat = (BaseStatement) createStatement(resultSetType, resultSetConcurrency);
    boolean hasResultSet = stat.executeWithFlags(s, QueryExecutor.QUERY_SUPPRESS_BEGIN);

    while (!hasResultSet && stat.getUpdateCount() != -1) {
      hasResultSet = stat.getMoreResults();
    }

    if (!hasResultSet) {
      throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stat.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    return stat.getResultSet();
  }

  public void execSQLUpdate(String s) throws SQLException {
    BaseStatement stmt = (BaseStatement) createStatement();
    if (stmt.executeWithFlags(s, QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN)) {
      throw new PSQLException(GT.tr("A result was returned when none was expected."),
          PSQLState.TOO_MANY_RESULTS);
    }

    // Transfer warnings to the connection, since the user never
    // has a chance to see the statement itself.
    SQLWarning warnings = stmt.getWarnings();
    if (warnings != null) {
      addWarning(warnings);
    }

    stmt.close();
  }

  /**
   * In SQL, a result table can be retrieved through a cursor that is named. The current row of a
   * result can be updated or deleted using a positioned update/delete statement that references the
   * cursor name.
   * <p>
   * We do not support positioned update/delete, so this is a no-op.
   *
   * @param cursor the cursor name
   * @throws SQLException if a database access error occurs
   */
  public void setCursorName(String cursor) throws SQLException {
    checkClosed();
    // No-op.
  }

  /**
   * getCursorName gets the cursor name.
   *
   * @return the current cursor name
   * @throws SQLException if a database access error occurs
   */
  public String getCursorName() throws SQLException {
    checkClosed();
    return null;
  }

  /**
   * We are required to bring back certain information by the DatabaseMetaData class. These
   * functions do that.
   * <p>
   * Method getURL() brings back the URL (good job we saved it)
   *
   * @return the url
   * @throws SQLException just in case...
   */
  public String getURL() throws SQLException {
    return creatingURL;
  }

  /**
   * Method getUserName() brings back the User Name (again, we saved it)
   *
   * @return the user name
   * @throws SQLException just in case...
   */
  public String getUserName() throws SQLException {
    return protoConnection.getUser();
  }

  public Fastpath getFastpathAPI() throws SQLException {
    checkClosed();
    if (fastpath == null) {
      fastpath = new Fastpath(this);
    }
    return fastpath;
  }

  // This holds a reference to the Fastpath API if already open
  private Fastpath fastpath = null;

  public LargeObjectManager getLargeObjectAPI() throws SQLException {
    checkClosed();
    if (largeobject == null) {
      largeobject = new LargeObjectManager(this);
    }
    return largeobject;
  }

  // This holds a reference to the LargeObject API if already open
  private LargeObjectManager largeobject = null;

  /*
   * This method is used internally to return an object based around org.postgresql's more unique
   * data types.
   *
   * <p>It uses an internal HashMap to get the handling class. If the type is not supported, then an
   * instance of org.postgresql.util.PGobject is returned.
   *
   * You can use the getValue() or setValue() methods to handle the returned object. Custom objects
   * can have their own methods.
   *
   * @return PGobject for this type, and set to value
   *
   * @exception SQLException if value is not correct for this type
   */
  public Object getObject(String type, String value, byte[] byteValue) throws SQLException {
    if (typemap != null) {
      Class<?> c = typemap.get(type);
      if (c != null) {
        // Handle the type (requires SQLInput & SQLOutput classes to be implemented)
        throw new PSQLException(GT.tr("Custom type maps are not supported."),
            PSQLState.NOT_IMPLEMENTED);
      }
    }

    PGobject obj = null;

    if (logger.logDebug()) {
      logger.debug("Constructing object from type=" + type + " value=<" + value + ">");
    }

    try {
      Class<? extends PGobject> klass = _typeCache.getPGobject(type);

      // If className is not null, then try to instantiate it,
      // It must be basetype PGobject

      // This is used to implement the org.postgresql unique types (like lseg,
      // point, etc).

      if (klass != null) {
        obj = klass.newInstance();
        obj.setType(type);
        if (byteValue != null && obj instanceof PGBinaryObject) {
          PGBinaryObject binObj = (PGBinaryObject) obj;
          binObj.setByteValue(byteValue, 0);
        } else {
          obj.setValue(value);
        }
      } else {
        // If className is null, then the type is unknown.
        // so return a PGobject with the type set, and the value set
        obj = new PGobject();
        obj.setType(type);
        obj.setValue(value);
      }

      return obj;
    } catch (SQLException sx) {
      // rethrow the exception. Done because we capture any others next
      throw sx;
    } catch (Exception ex) {
      throw new PSQLException(GT.tr("Failed to create object for: {0}.", type),
          PSQLState.CONNECTION_FAILURE, ex);
    }
  }

  protected TypeInfo createTypeInfo(BaseConnection conn, int unknownLength) {
    return new TypeInfoCache(conn, unknownLength);
  }

  public TypeInfo getTypeInfo() {
    return _typeCache;
  }

  public void addDataType(String type, String name) {
    try {
      addDataType(type, Class.forName(name).asSubclass(PGobject.class));
    } catch (Exception e) {
      throw new RuntimeException("Cannot register new type: " + e);
    }
  }

  public void addDataType(String type, Class<? extends PGobject> klass) throws SQLException {
    checkClosed();
    _typeCache.addDataType(type, klass);
  }

  // This initialises the objectTypes hash map
  private void initObjectTypes(Properties info) throws SQLException {
    // Add in the types that come packaged with the driver.
    // These can be overridden later if desired.
    addDataType("box", org.postgresql.geometric.PGbox.class);
    addDataType("circle", org.postgresql.geometric.PGcircle.class);
    addDataType("line", org.postgresql.geometric.PGline.class);
    addDataType("lseg", org.postgresql.geometric.PGlseg.class);
    addDataType("path", org.postgresql.geometric.PGpath.class);
    addDataType("point", org.postgresql.geometric.PGpoint.class);
    addDataType("polygon", org.postgresql.geometric.PGpolygon.class);
    addDataType("money", org.postgresql.util.PGmoney.class);
    addDataType("interval", org.postgresql.util.PGInterval.class);

    Enumeration<?> e = info.propertyNames();
    while (e.hasMoreElements()) {
      String propertyName = (String) e.nextElement();
      if (propertyName.startsWith("datatype.")) {
        String typeName = propertyName.substring(9);
        String className = info.getProperty(propertyName);
        Class<?> klass;

        try {
          klass = Class.forName(className);
        } catch (ClassNotFoundException cnfe) {
          throw new PSQLException(
              GT.tr("Unable to load the class {0} responsible for the datatype {1}",
                  new Object[]{className, typeName}),
              PSQLState.SYSTEM_ERROR, cnfe);
        }

        addDataType(typeName, klass.asSubclass(PGobject.class));
      }
    }
  }

  /**
   * <B>Note:</B> even though {@code Statement} is automatically closed when it is garbage
   * collected, it is better to close it explicitly to lower resource consumption.
   *
   * {@inheritDoc}
   */
  public void close() throws SQLException {
    releaseTimer();
    protoConnection.close();
    openStackTrace = null;
  }

  public String nativeSQL(String sql) throws SQLException {
    checkClosed();
    StringBuilder buf = new StringBuilder(sql.length());
    PgStatement.parseSql(sql, 0, buf, false, getStandardConformingStrings());
    return buf.toString();
  }

  public synchronized SQLWarning getWarnings() throws SQLException {
    checkClosed();
    SQLWarning newWarnings = protoConnection.getWarnings(); // NB: also clears them.
    if (firstWarning == null) {
      firstWarning = newWarnings;
    } else {
      firstWarning.setNextWarning(newWarnings); // Chain them on.
    }

    return firstWarning;
  }

  public synchronized void clearWarnings() throws SQLException {
    checkClosed();
    protoConnection.getWarnings(); // Clear and discard.
    firstWarning = null;
  }


  public void setReadOnly(boolean readOnly) throws SQLException {
    checkClosed();
    if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE) {
      throw new PSQLException(
          GT.tr("Cannot change transaction read-only property in the middle of a transaction."),
          PSQLState.ACTIVE_SQL_TRANSACTION);
    }

    if (haveMinimumServerVersion(ServerVersion.v7_4) && readOnly != this.readOnly) {
      String readOnlySql =
          "SET SESSION CHARACTERISTICS AS TRANSACTION " + (readOnly ? "READ ONLY" : "READ WRITE");
      execSQLUpdate(readOnlySql); // nb: no BEGIN triggered.
    }

    this.readOnly = readOnly;
  }

  public boolean isReadOnly() throws SQLException {
    checkClosed();
    return readOnly;
  }

  public void setAutoCommit(boolean autoCommit) throws SQLException {
    checkClosed();

    if (this.autoCommit == autoCommit) {
      return;
    }

    if (!this.autoCommit) {
      commit();
    }

    this.autoCommit = autoCommit;
  }

  public boolean getAutoCommit() throws SQLException {
    checkClosed();
    return this.autoCommit;
  }

  private void executeTransactionCommand(Query query) throws SQLException {
    int flags = QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS
        | QueryExecutor.QUERY_SUPPRESS_BEGIN;
    if (prepareThreshold == 0) {
      flags |= QueryExecutor.QUERY_ONESHOT;
    }

    getQueryExecutor().execute(query, null, new TransactionCommandHandler(), 0, 0, flags);
  }

  public void commit() throws SQLException {
    checkClosed();

    if (autoCommit) {
      throw new PSQLException(GT.tr("Cannot commit when autoCommit is enabled."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE) {
      executeTransactionCommand(commitQuery);
    }
  }

  protected void checkClosed() throws SQLException {
    if (isClosed()) {
      throw new PSQLException(GT.tr("This connection has been closed."),
          PSQLState.CONNECTION_DOES_NOT_EXIST);
    }
  }


  public void rollback() throws SQLException {
    checkClosed();

    if (autoCommit) {
      throw new PSQLException(GT.tr("Cannot rollback when autoCommit is enabled."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE) {
      executeTransactionCommand(rollbackQuery);
    }
  }

  public int getTransactionState() {
    return protoConnection.getTransactionState();
  }

  public int getTransactionIsolation() throws SQLException {
    checkClosed();

    String level = null;

    if (haveMinimumServerVersion(ServerVersion.v7_3)) {
      // 7.3+ returns the level as a query result.
      ResultSet rs = execSQLQuery("SHOW TRANSACTION ISOLATION LEVEL"); // nb: no BEGIN triggered
      if (rs.next()) {
        level = rs.getString(1);
      }
      rs.close();
    } else {
      // 7.2 returns the level as an INFO message. Ew.
      // We juggle the warning chains a bit here.

      // Swap out current warnings.
      SQLWarning saveWarnings = getWarnings();
      clearWarnings();

      // Run the query any examine any resulting warnings.
      execSQLUpdate("SHOW TRANSACTION ISOLATION LEVEL"); // nb: no BEGIN triggered
      SQLWarning warning = getWarnings();
      if (warning != null) {
        level = warning.getMessage();
      }

      // Swap original warnings back.
      clearWarnings();
      if (saveWarnings != null) {
        addWarning(saveWarnings);
      }
    }

    // XXX revisit: throw exception instead of silently eating the error in unknown cases?
    if (level == null) {
      return Connection.TRANSACTION_READ_COMMITTED; // Best guess.
    }

    level = level.toUpperCase(Locale.US);
    if (level.contains("READ COMMITTED")) {
      return Connection.TRANSACTION_READ_COMMITTED;
    }
    if (level.contains("READ UNCOMMITTED")) {
      return Connection.TRANSACTION_READ_UNCOMMITTED;
    }
    if (level.contains("REPEATABLE READ")) {
      return Connection.TRANSACTION_REPEATABLE_READ;
    }
    if (level.contains("SERIALIZABLE")) {
      return Connection.TRANSACTION_SERIALIZABLE;
    }

    return Connection.TRANSACTION_READ_COMMITTED; // Best guess.
  }

  public void setTransactionIsolation(int level) throws SQLException {
    checkClosed();

    if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE) {
      throw new PSQLException(
          GT.tr("Cannot change transaction isolation level in the middle of a transaction."),
          PSQLState.ACTIVE_SQL_TRANSACTION);
    }

    String isolationLevelName = getIsolationLevelName(level);
    if (isolationLevelName == null) {
      throw new PSQLException(GT.tr("Transaction isolation level {0} not supported.", level),
          PSQLState.NOT_IMPLEMENTED);
    }

    String isolationLevelSQL =
        "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + isolationLevelName;
    execSQLUpdate(isolationLevelSQL); // nb: no BEGIN triggered
  }

  protected String getIsolationLevelName(int level) {
    boolean pg80 = haveMinimumServerVersion(ServerVersion.v8_0);

    if (level == Connection.TRANSACTION_READ_COMMITTED) {
      return "READ COMMITTED";
    } else if (level == Connection.TRANSACTION_SERIALIZABLE) {
      return "SERIALIZABLE";
    } else if (pg80 && level == Connection.TRANSACTION_READ_UNCOMMITTED) {
      return "READ UNCOMMITTED";
    } else if (pg80 && level == Connection.TRANSACTION_REPEATABLE_READ) {
      return "REPEATABLE READ";
    }

    return null;
  }

  public void setCatalog(String catalog) throws SQLException {
    checkClosed();
    // no-op
  }

  public String getCatalog() throws SQLException {
    checkClosed();
    return protoConnection.getDatabase();
  }

  /**
   * Overrides finalize(). If called, it closes the connection.
   * <p>
   * This was done at the request of <a href="mailto:rachel@enlarion.demon.co.uk">Rachel
   * Greenham</a> who hit a problem where multiple clients didn't close the connection, and once a
   * fortnight enough clients were open to kill the postgres server.
   */
  protected void finalize() throws Throwable {
    try {
      if (openStackTrace != null) {
        logger.log(GT.tr("Finalizing a Connection that was never closed:"), openStackTrace);
      }

      close();
    } finally {
      super.finalize();
    }
  }

  /**
   * Get server version number
   *
   * @return server version number
   */
  public String getDBVersionNumber() {
    return protoConnection.getServerVersion();
  }

  /**
   * Get server major version
   *
   * @return server major version
   */
  public int getServerMajorVersion() {
    try {
      StringTokenizer versionTokens = new StringTokenizer(protoConnection.getServerVersion(), "."); // aaXbb.ccYdd
      return integerPart(versionTokens.nextToken()); // return X
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  /**
   * Get server minor version
   *
   * @return server minor version
   */
  public int getServerMinorVersion() {
    try {
      StringTokenizer versionTokens = new StringTokenizer(protoConnection.getServerVersion(), "."); // aaXbb.ccYdd
      versionTokens.nextToken(); // Skip aaXbb
      return integerPart(versionTokens.nextToken()); // return Y
    } catch (NoSuchElementException e) {
      return 0;
    }
  }

  public boolean haveMinimumServerVersion(String ver) {
    int requiredver = Utils.parseServerVersionStr(ver);
    if (requiredver == 0) {
      /*
       * Failed to parse input version. Fall back on legacy behaviour for BC.
       */
      return (protoConnection.getServerVersion().compareTo(ver) >= 0);
    } else {
      return haveMinimumServerVersion(requiredver);
    }
  }

  public boolean haveMinimumServerVersion(int ver) {
    return protoConnection.getServerVersionNum() >= ver;
  }

  public boolean haveMinimumServerVersion(Version ver) {
    return haveMinimumServerVersion(ver.getVersionNum());
  }

  public boolean haveMinimumCompatibleVersion(int ver) {
    return compatibleInt >= ver;
  }

  public boolean haveMinimumCompatibleVersion(String ver) {
    return haveMinimumCompatibleVersion(ServerVersion.from(ver));
  }

  public boolean haveMinimumCompatibleVersion(Version ver) {
    return haveMinimumCompatibleVersion(ver.getVersionNum());
  }


  public Encoding getEncoding() {
    return protoConnection.getEncoding();
  }

  public byte[] encodeString(String str) throws SQLException {
    try {
      return getEncoding().encode(str);
    } catch (IOException ioe) {
      throw new PSQLException(GT.tr("Unable to translate data into the desired encoding."),
          PSQLState.DATA_ERROR, ioe);
    }
  }

  public String escapeString(String str) throws SQLException {
    return Utils.escapeLiteral(null, str, protoConnection.getStandardConformingStrings())
        .toString();
  }

  public boolean getStandardConformingStrings() {
    return protoConnection.getStandardConformingStrings();
  }

  // This is a cache of the DatabaseMetaData instance for this connection
  protected java.sql.DatabaseMetaData metadata;

  public boolean isClosed() throws SQLException {
    return protoConnection.isClosed();
  }

  public void cancelQuery() throws SQLException {
    checkClosed();
    protoConnection.sendQueryCancel();
  }

  public PGNotification[] getNotifications() throws SQLException {
    checkClosed();
    getQueryExecutor().processNotifies();
    // Backwards-compatibility hand-holding.
    PGNotification[] notifications = protoConnection.getNotifications();
    return (notifications.length == 0 ? null : notifications);
  }

  /**
   * Handler for transaction queries
   */
  private class TransactionCommandHandler implements ResultHandler {
    private SQLException error;

    public void handleResultRows(Query fromQuery, Field[] fields, List<byte[][]> tuples,
        ResultCursor cursor) {
    }

    public void handleCommandStatus(String status, int updateCount, long insertOID) {
    }

    public void handleWarning(SQLWarning warning) {
      PgConnection.this.addWarning(warning);
    }

    public void handleError(SQLException newError) {
      if (error == null) {
        error = newError;
      } else {
        error.setNextException(newError);
      }
    }

    public void handleCompletion() throws SQLException {
      if (error != null) {
        throw error;
      }
    }
  }

  public int getPrepareThreshold() {
    return prepareThreshold;
  }

  public void setDefaultFetchSize(int fetchSize) throws SQLException {
    if (fetchSize < 0) {
      throw new PSQLException(GT.tr("Fetch size must be a value greater to or equal to 0."),
          PSQLState.INVALID_PARAMETER_VALUE);
    }

    this.defaultFetchSize = fetchSize;
  }

  public int getDefaultFetchSize() {
    return defaultFetchSize;
  }

  public void setPrepareThreshold(int newThreshold) {
    this.prepareThreshold = newThreshold;
  }

  public boolean getForceBinary() {
    return forcebinary;
  }

  public void setForceBinary(boolean newValue) {
    this.forcebinary = newValue;
  }


  public void setTypeMapImpl(Map<String, Class<?>> map) throws SQLException {
    typemap = map;
  }

  public Logger getLogger() {
    return logger;
  }


  public int getProtocolVersion() {
    return protoConnection.getProtocolVersion();
  }

  public boolean getStringVarcharFlag() {
    return bindStringAsVarchar;
  }

  private CopyManager copyManager = null;

  public CopyManager getCopyAPI() throws SQLException {
    checkClosed();
    if (copyManager == null) {
      copyManager = new CopyManager(this);
    }
    return copyManager;
  }

  public boolean binaryTransferSend(int oid) {
    return useBinarySendForOids.contains(oid);
  }

  public int getBackendPID() {
    return protoConnection.getBackendPID();
  }

  public boolean isColumnSanitiserDisabled() {
    return this.disableColumnSanitiser;
  }

  public void setDisableColumnSanitiser(boolean disableColumnSanitiser) {
    this.disableColumnSanitiser = disableColumnSanitiser;
  }

  protected void abort() {
    protoConnection.abort();
  }

  private synchronized Timer getTimer() {
    if (cancelTimer == null) {
      cancelTimer = Driver.getSharedTimer().getTimer();
    }
    return cancelTimer;
  }

  private synchronized void releaseTimer() {
    if (cancelTimer != null) {
      cancelTimer = null;
      Driver.getSharedTimer().releaseTimer();
    }
  }

  public void addTimerTask(TimerTask timerTask, long milliSeconds) {
    Timer timer = getTimer();
    timer.schedule(timerTask, milliSeconds);
  }

  public void purgeTimerTasks() {
    Timer timer = cancelTimer;
    if (timer != null) {
      timer.purge();
    }
  }

  public String escapeIdentifier(String identifier) throws SQLException {
    return Utils.escapeIdentifier(null, identifier).toString();
  }

  public String escapeLiteral(String literal) throws SQLException {
    return Utils.escapeLiteral(null, literal, protoConnection.getStandardConformingStrings())
        .toString();
  }

  private static void appendArray(StringBuilder sb, Object elements, char delim) {
    sb.append('{');

    int nElements = java.lang.reflect.Array.getLength(elements);
    for (int i = 0; i < nElements; i++) {
      if (i > 0) {
        sb.append(delim);
      }

      Object o = java.lang.reflect.Array.get(elements, i);
      if (o == null) {
        sb.append("NULL");
      } else if (o.getClass().isArray()) {
        appendArray(sb, o, delim);
      } else {
        String s = o.toString();
        PgArray.escapeArrayElement(sb, s);
      }
    }
    sb.append('}');
  }

  // Parse a "dirty" integer surrounded by non-numeric characters
  private static int integerPart(String dirtyString) {
    int start;
    int end;

    for (start = 0; start < dirtyString.length()
        && !Character.isDigit(dirtyString.charAt(start)); ++start) {
      ;
    }

    for (end = start; end < dirtyString.length()
        && Character.isDigit(dirtyString.charAt(end)); ++end) {
      ;
    }

    if (start == end) {
      return 0;
    }

    return Integer.parseInt(dirtyString.substring(start, end));
  }

  public Statement createStatement(int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    checkClosed();
    return new PgStatement(this, resultSetType, resultSetConcurrency, resultSetHoldability);
  }

  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    checkClosed();
    return new PgPreparedStatement(this, sql, resultSetType, resultSetConcurrency,
        resultSetHoldability);
  }

  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency,
      int resultSetHoldability) throws SQLException {
    checkClosed();
    return new PgCallableStatement(this, sql, resultSetType, resultSetConcurrency,
        resultSetHoldability);
  }

  public DatabaseMetaData getMetaData() throws SQLException {
    checkClosed();
    if (metadata == null) {
      metadata = new PgDatabaseMetaData(this);
    }
    return metadata;
  }

  public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
    setTypeMapImpl(map);
  }

  protected Array makeArray(int oid, String fieldString) throws SQLException {
    return new PgArray(this, oid, fieldString);
  }

  protected Blob makeBlob(long oid) throws SQLException {
    return new PgBlob(this, oid);
  }

  protected Clob makeClob(long oid) throws SQLException {
    return new PgClob(this, oid);
  }

  protected SQLXML makeSQLXML() throws SQLException {
    return new PgSQLXML(this);
  }

  public Clob createClob() throws SQLException {
    checkClosed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "createClob()");
  }

  public Blob createBlob() throws SQLException {
    checkClosed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "createBlob()");
  }

  public NClob createNClob() throws SQLException {
    checkClosed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "createNClob()");
  }

  public SQLXML createSQLXML() throws SQLException {
    checkClosed();
    return makeSQLXML();
  }

  public Struct createStruct(String typeName, Object[] attributes) throws SQLException {
    checkClosed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "createStruct(String, Object[])");
  }

  public Array createArrayOf(String typeName, Object[] elements) throws SQLException {
    checkClosed();

    int oid = getTypeInfo().getPGArrayType(typeName);

    if (oid == Oid.UNSPECIFIED) {
      throw new PSQLException(
          GT.tr("Unable to find server array type for provided name {0}.", typeName),
          PSQLState.INVALID_NAME);
    }

    char delim = getTypeInfo().getArrayDelimiter(oid);
    StringBuilder sb = new StringBuilder();
    appendArray(sb, elements, delim);

    return makeArray(oid, sb.toString());
  }

  public boolean isValid(int timeout) throws SQLException {
    if (isClosed()) {
      return false;
    }
    if (timeout < 0) {
      throw new PSQLException(GT.tr("Invalid timeout ({0}<0).", timeout),
          PSQLState.INVALID_PARAMETER_VALUE);
    }
    boolean valid = false;
    Statement stmt = null;
    try {
      if (!isClosed()) {
        stmt = createStatement();
        stmt.setQueryTimeout(timeout);
        stmt.executeUpdate("");
        valid = true;
      }
    } catch (SQLException e) {
      getLogger().log(GT.tr("Validating connection."), e);
    } finally {
      if (stmt != null) {
        try {
          stmt.close();
        } catch (Exception ex) {
        }
      }
    }
    return valid;
  }

  public void setClientInfo(String name, String value) throws SQLClientInfoException {
    try {
      checkClosed();
    } catch (final SQLException cause) {
      Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
      failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
      throw new SQLClientInfoException(GT.tr("This connection has been closed."), failures, cause);
    }

    if (haveMinimumServerVersion(ServerVersion.v9_0) && "ApplicationName".equals(name)) {
      if (value == null) {
        value = "";
      }

      try {
        StringBuilder sql = new StringBuilder("SET application_name = '");
        Utils.escapeLiteral(sql, value, getStandardConformingStrings());
        sql.append("'");
        execSQLUpdate(sql.toString());
      } catch (SQLException sqle) {
        Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
        failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
        throw new SQLClientInfoException(
            GT.tr("Failed to set ClientInfo property: {0}", "ApplicationName"), sqle.getSQLState(),
            failures, sqle);
      }

      _clientInfo.put(name, value);
      return;
    }

    addWarning(new SQLWarning(GT.tr("ClientInfo property not supported."),
        PSQLState.NOT_IMPLEMENTED.getState()));
  }

  public void setClientInfo(Properties properties) throws SQLClientInfoException {
    try {
      checkClosed();
    } catch (final SQLException cause) {
      Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
      for (Map.Entry<Object, Object> e : properties.entrySet()) {
        failures.put((String) e.getKey(), ClientInfoStatus.REASON_UNKNOWN);
      }
      throw new SQLClientInfoException(GT.tr("This connection has been closed."), failures, cause);
    }

    Map<String, ClientInfoStatus> failures = new HashMap<String, ClientInfoStatus>();
    for (String name : new String[]{"ApplicationName"}) {
      try {
        setClientInfo(name, properties.getProperty(name, null));
      } catch (SQLClientInfoException e) {
        failures.putAll(e.getFailedProperties());
      }
    }

    if (!failures.isEmpty()) {
      throw new SQLClientInfoException(GT.tr("One ore more ClientInfo failed."),
          PSQLState.NOT_IMPLEMENTED.getState(), failures);
    }
  }

  public String getClientInfo(String name) throws SQLException {
    checkClosed();
    return _clientInfo.getProperty(name);
  }

  public Properties getClientInfo() throws SQLException {
    checkClosed();
    return _clientInfo;
  }

  public <T> T createQueryObject(Class<T> ifc) throws SQLException {
    checkClosed();
    throw org.postgresql.Driver.notImplemented(this.getClass(), "createQueryObject(Class<T>)");
  }

  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    checkClosed();
    return iface.isAssignableFrom(getClass());
  }

  public <T> T unwrap(Class<T> iface) throws SQLException {
    checkClosed();
    if (iface.isAssignableFrom(getClass())) {
      return iface.cast(this);
    }
    throw new SQLException("Cannot unwrap to " + iface.getName());
  }

  public String getSchema() throws SQLException {
    checkClosed();
    Statement stmt = createStatement();
    try {
      ResultSet rs = stmt.executeQuery("select current_schema()");
      try {
        if (!rs.next()) {
          return null; // Is it ever possible?
        }
        return rs.getString(1);
      } finally {
        rs.close();
      }
    } finally {
      stmt.close();
    }
  }

  public void setSchema(String schema) throws SQLException {
    checkClosed();
    Statement stmt = createStatement();
    try {
      if (schema == null) {
        stmt.executeUpdate("SET SESSION search_path TO DEFAULT");
      } else {
        StringBuilder sb = new StringBuilder();
        sb.append("SET SESSION search_path TO '");
        Utils.escapeLiteral(sb, schema, getStandardConformingStrings());
        sb.append("'");
        stmt.executeUpdate(sb.toString());
      }
    } finally {
      stmt.close();
    }
  }

  public class AbortCommand implements Runnable {
    public void run() {
      abort();
    }
  }

  public void abort(Executor executor) throws SQLException {
    if (isClosed()) {
      return;
    }

    SQL_PERMISSION_ABORT.checkGuard(this);

    AbortCommand command = new AbortCommand();
    if (executor != null) {
      executor.execute(command);
    } else {
      command.run();
    }
  }

  public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "setNetworkTimeout(Executor, int)");
  }

  public int getNetworkTimeout() throws SQLException {
    throw org.postgresql.Driver.notImplemented(this.getClass(), "getNetworkTimeout()");
  }

  public void setHoldability(int holdability) throws SQLException {
    checkClosed();

    switch (holdability) {
      case ResultSet.CLOSE_CURSORS_AT_COMMIT:
        rsHoldability = holdability;
        break;
      case ResultSet.HOLD_CURSORS_OVER_COMMIT:
        rsHoldability = holdability;
        break;
      default:
        throw new PSQLException(GT.tr("Unknown ResultSet holdability setting: {0}.", holdability),
            PSQLState.INVALID_PARAMETER_VALUE);
    }
  }

  public int getHoldability() throws SQLException {
    checkClosed();
    return rsHoldability;
  }

  public Savepoint setSavepoint() throws SQLException {
    String pgName;
    checkClosed();
    if (!haveMinimumServerVersion(ServerVersion.v8_0)) {
      throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."),
          PSQLState.NOT_IMPLEMENTED);
    }
    if (getAutoCommit()) {
      throw new PSQLException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    PSQLSavepoint savepoint = new PSQLSavepoint(savepointId++);
    pgName = savepoint.getPGName();

    // Note we can't use execSQLUpdate because we don't want
    // to suppress BEGIN.
    Statement stmt = createStatement();
    stmt.executeUpdate("SAVEPOINT " + pgName);
    stmt.close();

    return savepoint;
  }

  public Savepoint setSavepoint(String name) throws SQLException {
    checkClosed();
    if (!haveMinimumServerVersion(ServerVersion.v8_0)) {
      throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."),
          PSQLState.NOT_IMPLEMENTED);
    }
    if (getAutoCommit()) {
      throw new PSQLException(GT.tr("Cannot establish a savepoint in auto-commit mode."),
          PSQLState.NO_ACTIVE_SQL_TRANSACTION);
    }

    PSQLSavepoint savepoint = new PSQLSavepoint(name);

    // Note we can't use execSQLUpdate because we don't want
    // to suppress BEGIN.
    Statement stmt = createStatement();
    stmt.executeUpdate("SAVEPOINT " + savepoint.getPGName());
    stmt.close();

    return savepoint;
  }

  public void rollback(Savepoint savepoint) throws SQLException {
    checkClosed();
    if (!haveMinimumServerVersion(ServerVersion.v8_0)) {
      throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."),
          PSQLState.NOT_IMPLEMENTED);
    }

    PSQLSavepoint pgSavepoint = (PSQLSavepoint) savepoint;
    execSQLUpdate("ROLLBACK TO SAVEPOINT " + pgSavepoint.getPGName());
  }

  public void releaseSavepoint(Savepoint savepoint) throws SQLException {
    checkClosed();
    if (!haveMinimumServerVersion(ServerVersion.v8_0)) {
      throw new PSQLException(GT.tr("Server versions prior to 8.0 do not support savepoints."),
          PSQLState.NOT_IMPLEMENTED);
    }

    PSQLSavepoint pgSavepoint = (PSQLSavepoint) savepoint;
    execSQLUpdate("RELEASE SAVEPOINT " + pgSavepoint.getPGName());
    pgSavepoint.invalidate();
  }

  public Statement createStatement(int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkClosed();
    return createStatement(resultSetType, resultSetConcurrency, getHoldability());
  }

  public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkClosed();
    return prepareStatement(sql, resultSetType, resultSetConcurrency, getHoldability());
  }

  public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency)
      throws SQLException {
    checkClosed();
    return prepareCall(sql, resultSetType, resultSetConcurrency, getHoldability());
  }

  public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
    checkClosed();
    if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
      sql = PgStatement.addReturning(this, sql, new String[]{"*"}, false);
    }

    PreparedStatement ps = prepareStatement(sql);

    if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
      ((PgStatement) ps).wantsGeneratedKeysAlways = true;
    }

    return ps;
  }

  public PreparedStatement prepareStatement(String sql, int columnIndexes[]) throws SQLException {
    if (columnIndexes == null || columnIndexes.length == 0) {
      return prepareStatement(sql);
    }

    checkClosed();
    throw new PSQLException(GT.tr("Returning autogenerated keys is not supported."),
        PSQLState.NOT_IMPLEMENTED);
  }

  public PreparedStatement prepareStatement(String sql, String columnNames[]) throws SQLException {
    if (columnNames != null && columnNames.length != 0) {
      sql = PgStatement.addReturning(this, sql, columnNames, true);
    }

    PreparedStatement ps = prepareStatement(sql);

    if (columnNames != null && columnNames.length != 0) {
      ((PgStatement) ps).wantsGeneratedKeysAlways = true;
    }

    return ps;
  }
}
