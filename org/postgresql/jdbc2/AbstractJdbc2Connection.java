/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.jdbc2;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.*;
import java.util.*;

import org.postgresql.core.*;

import org.postgresql.Driver;
import org.postgresql.PGNotification;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.largeobject.LargeObjectManager;
import org.postgresql.util.*;
import org.postgresql.util.HostSpec;
import org.postgresql.copy.*;

/**
 * This class defines methods of the jdbc2 specification.
 * The real Connection class (for jdbc2) is org.postgresql.jdbc2.Jdbc2Connection
 */
public abstract class AbstractJdbc2Connection implements BaseConnection
{
    //
    // Driver-wide connection ID counter, used for logging
    //
    private static int nextConnectionID = 1;

    //
    // Data initialized on construction:
    //

    // Per-connection logger
    private final Logger logger;

    /* URL we were created via */
    private final String creatingURL;

    private Throwable openStackTrace;

    /* Actual network handler */
    private final ProtocolConnection protoConnection;
    /* Compatibility version */
    private final String compatible;
    /* Actual server version */
    private final String dbVersionNumber;

    /* Query that runs COMMIT */
    private final Query commitQuery;
    /* Query that runs ROLLBACK */
    private final Query rollbackQuery;

    private TypeInfo _typeCache;

    // Default statement prepare threshold.
    protected int prepareThreshold;
    // Connection's autocommit state.
    public boolean autoCommit = true;
    // Connection's readonly state.
    public boolean readOnly = false;

    // Bind String to UNSPECIFIED or VARCHAR?
    public final boolean bindStringAsVarchar;

    // Current warnings; there might be more on protoConnection too.
    public SQLWarning firstWarning = null;

    /** Set of oids that use binary transfer when sending to server. */
    private Set<Integer> useBinarySendForOids;
    /** Set of oids that use binary transfer when receiving from server. */
    private Set<Integer> useBinaryReceiveForOids;

    public abstract DatabaseMetaData getMetaData() throws SQLException;

    //
    // Ctor.
    //
    protected AbstractJdbc2Connection(HostSpec[] hostSpecs, String user, String database, Properties info, String url) throws SQLException
    {
        this.creatingURL = url;

        // Read loglevel arg and set the loglevel based on this value;
        // In addition to setting the log level, enable output to
        // standard out if no other printwriter is set

        int logLevel = Driver.getLogLevel();
        String connectionLogLevel = info.getProperty("loglevel");
        if (connectionLogLevel != null) {
            try {
                logLevel = Integer.parseInt(connectionLogLevel);
            } catch (Exception l_e) {
                // XXX revisit
                // invalid value for loglevel; ignore it
            }
        }

        synchronized (AbstractJdbc2Connection.class) {
            logger = new Logger(nextConnectionID++);
            logger.setLogLevel(logLevel);
        }

        if (logLevel > 0)
            enableDriverManagerLogging();

        prepareThreshold = 5;
        try
        {
            prepareThreshold = Integer.parseInt(info.getProperty("prepareThreshold", "5"));
            if (prepareThreshold < 0)
                prepareThreshold = 0;
        }
        catch (Exception e)
        {
        }
        boolean binaryTransfer = true;
        try
        {
            binaryTransfer = Boolean.valueOf(info.getProperty("binaryTransfer", "true")).booleanValue();
        }
        catch (Exception e)
        {
        }

        //Print out the driver version number
        if (logger.logInfo())
            logger.info(Driver.getVersion());

        // Now make the initial connection and set up local state
        this.protoConnection = ConnectionFactory.openConnection(hostSpecs, user, database, info, logger);
        this.dbVersionNumber = protoConnection.getServerVersion();
        this.compatible = info.getProperty("compatible", Driver.MAJORVERSION + "." + Driver.MINORVERSION);

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
        if (!haveMinimumCompatibleVersion("8.0")) {
            binaryOids.remove(Oid.TIME);
            binaryOids.remove(Oid.TIMETZ);
            binaryOids.remove(Oid.TIMESTAMP);
            binaryOids.remove(Oid.TIMESTAMPTZ);
        }
        // driver supports only null-compatible arrays
        if (!haveMinimumCompatibleVersion("8.3")) {
            binaryOids.remove(Oid.INT2_ARRAY);
            binaryOids.remove(Oid.INT4_ARRAY);
            binaryOids.remove(Oid.INT8_ARRAY);
            binaryOids.remove(Oid.FLOAT4_ARRAY);
            binaryOids.remove(Oid.FLOAT8_ARRAY);
            binaryOids.remove(Oid.FLOAT8_ARRAY);
            binaryOids.remove(Oid.VARCHAR_ARRAY);
            binaryOids.remove(Oid.TEXT_ARRAY);
        }

        binaryOids.addAll(getOidSet(info.getProperty("binaryTransferEnable", "")));
        binaryOids.removeAll(getOidSet(info.getProperty("binaryTransferDisable", "")));

        // split for receive and send for better control
        useBinarySendForOids = new HashSet<Integer>();
        useBinarySendForOids.addAll(binaryOids);
        useBinaryReceiveForOids = new HashSet<Integer>();
        useBinaryReceiveForOids.addAll(binaryOids);

        /*
         * Does not pass unit tests because unit tests expect setDate to have
         * millisecond accuracy whereas the binary transfer only supports
         * date accuracy.
         */
        useBinarySendForOids.remove(Oid.DATE);

        protoConnection.setBinaryReceiveOids(useBinaryReceiveForOids);

        if (logger.logDebug())
        {
            logger.debug("    compatible = " + compatible);
            logger.debug("    loglevel = " + logLevel);
            logger.debug("    prepare threshold = " + prepareThreshold);
            logger.debug("    types using binary send = " + oidsToString(useBinarySendForOids));
            logger.debug("    types using binary receive = " + oidsToString(useBinaryReceiveForOids));
            logger.debug("    integer date/time = " + protoConnection.getIntegerDateTimes());
        }

        //
        // String -> text or unknown?
        //

        String stringType = info.getProperty("stringtype");
        if (stringType != null) {
            if (stringType.equalsIgnoreCase("unspecified"))
                bindStringAsVarchar = false;
            else if (stringType.equalsIgnoreCase("varchar"))
                bindStringAsVarchar = true;
            else
                throw new PSQLException(GT.tr("Unsupported value for stringtype parameter: {0}", stringType),
                                        PSQLState.INVALID_PARAMETER_VALUE);
        } else {
            bindStringAsVarchar = haveMinimumCompatibleVersion("8.0");
        }

        // Initialize timestamp stuff
        timestampUtils = new TimestampUtils(haveMinimumServerVersion("7.4"), haveMinimumServerVersion("8.2"),
                                            !protoConnection.getIntegerDateTimes());

        // Initialize common queries.
        commitQuery = getQueryExecutor().createSimpleQuery("COMMIT");
        rollbackQuery = getQueryExecutor().createSimpleQuery("ROLLBACK");

        int unknownLength = Integer.MAX_VALUE;
        String strLength = info.getProperty("unknownLength");
        if (strLength != null) {
            try {
                unknownLength = Integer.parseInt(strLength);
            } catch (NumberFormatException nfe) {
                throw new PSQLException(GT.tr("unknownLength parameter value must be an integer"), PSQLState.INVALID_PARAMETER_VALUE, nfe);
            }
        }

        // Initialize object handling
        _typeCache = createTypeInfo(this, unknownLength);
        initObjectTypes(info);

        if (Boolean.valueOf(info.getProperty("logUnclosedConnections")).booleanValue()) {
            openStackTrace = new Throwable("Connection was created at this point:");
            enableDriverManagerLogging();
        }
    }

    private Set<Integer> getOidSet(String oidList) throws PSQLException {
        Set oids = new HashSet();
        StringTokenizer tokenizer = new StringTokenizer(oidList, ",");
        while (tokenizer.hasMoreTokens()) {
            String oid = tokenizer.nextToken();
            oids.add(Oid.valueOf(oid));
        }
        return oids;
    }

    private String oidsToString(Set<Integer> oids) {
        StringBuffer sb = new StringBuffer();
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
    public TimestampUtils getTimestampUtils() { return timestampUtils; }

    /*
     * The current type mappings
     */
    protected java.util.Map typemap;

    public java.sql.Statement createStatement() throws SQLException
    {
        // We now follow the spec and default to TYPE_FORWARD_ONLY.
        return createStatement(java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
    }

    public abstract java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException;

    public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException
    {
        return prepareStatement(sql, java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
    }

    public abstract java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException;

    public java.sql.CallableStatement prepareCall(String sql) throws SQLException
    {
        return prepareCall(sql, java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY);
    }

    public abstract java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException;

    public java.util.Map getTypeMap() throws SQLException
    {
        checkClosed();
        return typemap;
    }

    // Query executor associated with this connection.
    public QueryExecutor getQueryExecutor() {
        return protoConnection.getQueryExecutor();
    }

    /*
     * This adds a warning to the warning chain.
     * @param warn warning to add
     */
    public void addWarning(SQLWarning warn)
    {
        // Add the warning to the chain
        if (firstWarning != null)
            firstWarning.setNextWarning(warn);
        else
            firstWarning = warn;

    }

    public ResultSet execSQLQuery(String s) throws SQLException {
        return execSQLQuery(s, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
    }

    /**
     * Simple query execution.
     */
    public ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency) throws SQLException {
        BaseStatement stat = (BaseStatement) createStatement(resultSetType, resultSetConcurrency);
        boolean hasResultSet = stat.executeWithFlags(s, QueryExecutor.QUERY_SUPPRESS_BEGIN);

        while (!hasResultSet && stat.getUpdateCount() != -1)
            hasResultSet = stat.getMoreResults();

        if (!hasResultSet)
            throw new PSQLException(GT.tr("No results were returned by the query."), PSQLState.NO_DATA);

        // Transfer warnings to the connection, since the user never
        // has a chance to see the statement itself.
        SQLWarning warnings = stat.getWarnings();
        if (warnings != null)
            addWarning(warnings);

        return stat.getResultSet();
    }

    public void execSQLUpdate(String s) throws SQLException {
        BaseStatement stmt = (BaseStatement) createStatement();
        if (stmt.executeWithFlags(s, QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS | QueryExecutor.QUERY_SUPPRESS_BEGIN))
            throw new PSQLException(GT.tr("A result was returned when none was expected."),
                                    PSQLState.TOO_MANY_RESULTS);

        // Transfer warnings to the connection, since the user never
        // has a chance to see the statement itself.
        SQLWarning warnings = stmt.getWarnings();
        if (warnings != null)
            addWarning(warnings);

        stmt.close();
    }

    /*
     * In SQL, a result table can be retrieved through a cursor that
     * is named.  The current row of a result can be updated or deleted
     * using a positioned update/delete statement that references the
     * cursor name.
     *
     * We do not support positioned update/delete, so this is a no-op.
     *
     * @param cursor the cursor name
     * @exception SQLException if a database access error occurs
     */
    public void setCursorName(String cursor) throws SQLException
    {
        checkClosed();
        // No-op.
    }

    /*
     * getCursorName gets the cursor name.
     *
     * @return the current cursor name
     * @exception SQLException if a database access error occurs
     */
    public String getCursorName() throws SQLException
    {
        checkClosed();
        return null;
    }

    /*
     * We are required to bring back certain information by
     * the DatabaseMetaData class. These functions do that.
     *
     * Method getURL() brings back the URL (good job we saved it)
     *
     * @return the url
     * @exception SQLException just in case...
     */
    public String getURL() throws SQLException
    {
        return creatingURL;
    }

    /*
     * Method getUserName() brings back the User Name (again, we
     * saved it)
     *
     * @return the user name
     * @exception SQLException just in case...
     */
    public String getUserName() throws SQLException
    {
        return protoConnection.getUser();
    }

    /*
     * This returns the Fastpath API for the current connection.
     *
     * <p><b>NOTE:</b> This is not part of JDBC, but allows access to
     * functions on the org.postgresql backend itself.
     *
     * <p>It is primarily used by the LargeObject API
     *
     * <p>The best way to use this is as follows:
     *
     * <p><pre>
     * import org.postgresql.fastpath.*;
     * ...
     * Fastpath fp = ((org.postgresql.Connection)myconn).getFastpathAPI();
     * </pre>
     *
     * <p>where myconn is an open Connection to org.postgresql.
     *
     * @return Fastpath object allowing access to functions on the org.postgresql
     * backend.
     * @exception SQLException by Fastpath when initialising for first time
     */
    public Fastpath getFastpathAPI() throws SQLException
    {
        checkClosed();
        if (fastpath == null)
            fastpath = new Fastpath(this);
        return fastpath;
    }

    // This holds a reference to the Fastpath API if already open
    private Fastpath fastpath = null;

    /*
     * This returns the LargeObject API for the current connection.
     *
     * <p><b>NOTE:</b> This is not part of JDBC, but allows access to
     * functions on the org.postgresql backend itself.
     *
     * <p>The best way to use this is as follows:
     *
     * <p><pre>
     * import org.postgresql.largeobject.*;
     * ...
     * LargeObjectManager lo = ((org.postgresql.Connection)myconn).getLargeObjectAPI();
     * </pre>
     *
     * <p>where myconn is an open Connection to org.postgresql.
     *
     * @return LargeObject object that implements the API
     * @exception SQLException by LargeObject when initialising for first time
     */
    public LargeObjectManager getLargeObjectAPI() throws SQLException
    {
        checkClosed();
        if (largeobject == null)
            largeobject = new LargeObjectManager(this);
        return largeobject;
    }

    // This holds a reference to the LargeObject API if already open
    private LargeObjectManager largeobject = null;

    /*
     * This method is used internally to return an object based around
     * org.postgresql's more unique data types.
     *
     * <p>It uses an internal HashMap to get the handling class. If the
     * type is not supported, then an instance of org.postgresql.util.PGobject
     * is returned.
     *
     * You can use the getValue() or setValue() methods to handle the returned
     * object. Custom objects can have their own methods.
     *
     * @return PGobject for this type, and set to value
     * @exception SQLException if value is not correct for this type
     */
    public Object getObject(String type, String value, byte[] byteValue) throws SQLException
    {
        if (typemap != null)
        {
            Class c = (Class) typemap.get(type);
            if (c != null)
            {
                // Handle the type (requires SQLInput & SQLOutput classes to be implemented)
                throw new PSQLException(GT.tr("Custom type maps are not supported."), PSQLState.NOT_IMPLEMENTED);
            }
        }

        PGobject obj = null;

        if (logger.logDebug())
            logger.debug("Constructing object from type=" + type + " value=<" + value + ">");

        try
        {
            Class klass = _typeCache.getPGobject(type);

            // If className is not null, then try to instantiate it,
            // It must be basetype PGobject

            // This is used to implement the org.postgresql unique types (like lseg,
            // point, etc).

            if (klass != null)
            {
                obj = (PGobject) (klass.newInstance());
                obj.setType(type);
                if (byteValue != null && obj instanceof PGBinaryObject) {
                    PGBinaryObject binObj = (PGBinaryObject) obj;
                    binObj.setByteValue(byteValue, 0);
                } else {
                    obj.setValue(value);
                }
            }
            else
            {
                // If className is null, then the type is unknown.
                // so return a PGobject with the type set, and the value set
                obj = new PGobject();
                obj.setType( type );
                obj.setValue( value );
            }

            return obj;
        }
        catch (SQLException sx)
        {
            // rethrow the exception. Done because we capture any others next
            throw sx;
        }
        catch (Exception ex)
        {
            throw new PSQLException(GT.tr("Failed to create object for: {0}.", type), PSQLState.CONNECTION_FAILURE, ex);
        }
    }

    protected TypeInfo createTypeInfo(BaseConnection conn, int unknownLength)
    {
        return new TypeInfoCache(conn, unknownLength);
    }

    public TypeInfo getTypeInfo()
    {
        return _typeCache;
    }

    public void addDataType(String type, String name)
    {
        try
        {
            addDataType(type, Class.forName(name));
        }
        catch (Exception e)
        {
            throw new RuntimeException("Cannot register new type: " + e);
        }
    }

    public void addDataType(String type, Class klass) throws SQLException
    {
        checkClosed();
        _typeCache.addDataType(type, klass);
    }

    // This initialises the objectTypes hash map
    private void initObjectTypes(Properties info) throws SQLException
    {
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

        for (Enumeration e = info.propertyNames(); e.hasMoreElements(); )
        {
            String propertyName = (String)e.nextElement();
            if (propertyName.startsWith("datatype."))
            {
                String typeName = propertyName.substring(9);
                String className = info.getProperty(propertyName);
                Class klass;

                try
                {
                    klass = Class.forName(className);
                }
                catch (ClassNotFoundException cnfe)
                {
                    throw new PSQLException(GT.tr("Unable to load the class {0} responsible for the datatype {1}", new Object[] { className, typeName }),
                                            PSQLState.SYSTEM_ERROR, cnfe);
                }

                addDataType(typeName, klass);
            }
        }
    }

    /**
     * In some cases, it is desirable to immediately release a Connection's
     * database and JDBC resources instead of waiting for them to be
     * automatically released.
     *
     * <B>Note:</B> A Connection is automatically closed when it is
     * garbage collected.  Certain fatal errors also result in a closed
     * connection.
     *
     * @exception SQLException if a database access error occurs
     */
    public void close()
    {
        protoConnection.close();
        openStackTrace = null;
    }

    /*
     * A driver may convert the JDBC sql grammar into its system's
     * native SQL grammar prior to sending it; nativeSQL returns the
     * native form of the statement that the driver would have sent.
     *
     * @param sql a SQL statement that may contain one or more '?'
     * parameter placeholders
     * @return the native form of this statement
     * @exception SQLException if a database access error occurs
     */
    public String nativeSQL(String sql) throws SQLException
    {
        checkClosed();
        StringBuffer buf = new StringBuffer(sql.length());
        AbstractJdbc2Statement.parseSql(sql,0,buf,false,getStandardConformingStrings());
        return buf.toString();
    }

    /*
     * The first warning reported by calls on this Connection is
     * returned.
     *
     * <B>Note:</B> Sebsequent warnings will be changed to this
     * SQLWarning
     *
     * @return the first SQLWarning or null
     * @exception SQLException if a database access error occurs
     */
    public synchronized SQLWarning getWarnings()
    throws SQLException
    {
        checkClosed();
        SQLWarning newWarnings = protoConnection.getWarnings(); // NB: also clears them.
        if (firstWarning == null)
            firstWarning = newWarnings;
        else
            firstWarning.setNextWarning(newWarnings); // Chain them on.

        return firstWarning;
    }

    /*
     * After this call, getWarnings returns null until a new warning
     * is reported for this connection.
     *
     * @exception SQLException if a database access error occurs
     */
    public synchronized void clearWarnings()
    throws SQLException
    {
        checkClosed();
        protoConnection.getWarnings(); // Clear and discard.
        firstWarning = null;
    }


    /*
     * You can put a connection in read-only mode as a hunt to enable
     * database optimizations
     *
     * <B>Note:</B> setReadOnly cannot be called while in the middle
     * of a transaction
     *
     * @param readOnly - true enables read-only mode; false disables it
     * @exception SQLException if a database access error occurs
     */
    public void setReadOnly(boolean readOnly) throws SQLException
    {
        checkClosed();
        if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            throw new PSQLException(GT.tr("Cannot change transaction read-only property in the middle of a transaction."),
                                    PSQLState.ACTIVE_SQL_TRANSACTION);

        if (haveMinimumServerVersion("7.4") && readOnly != this.readOnly)
        {
            String readOnlySql = "SET SESSION CHARACTERISTICS AS TRANSACTION " + (readOnly ? "READ ONLY" : "READ WRITE");
            execSQLUpdate(readOnlySql); // nb: no BEGIN triggered.
        }

        this.readOnly = readOnly;
    }

    /*
     * Tests to see if the connection is in Read Only Mode.
     *
     * @return true if the connection is read only
     * @exception SQLException if a database access error occurs
     */
    public boolean isReadOnly() throws SQLException
    {
        checkClosed();
        return readOnly;
    }

    /*
     * If a connection is in auto-commit mode, than all its SQL
     * statements will be executed and committed as individual
     * transactions.  Otherwise, its SQL statements are grouped
     * into transactions that are terminated by either commit()
     * or rollback().  By default, new connections are in auto-
     * commit mode.  The commit occurs when the statement completes
     * or the next execute occurs, whichever comes first.  In the
     * case of statements returning a ResultSet, the statement
     * completes when the last row of the ResultSet has been retrieved
     * or the ResultSet has been closed.  In advanced cases, a single
     * statement may return multiple results as well as output parameter
     * values. Here the commit occurs when all results and output param
     * values have been retrieved.
     *
     * @param autoCommit - true enables auto-commit; false disables it
     * @exception SQLException if a database access error occurs
     */
    public void setAutoCommit(boolean autoCommit) throws SQLException
    {
        checkClosed();

        if (this.autoCommit == autoCommit)
            return ;

        if (!this.autoCommit)
            commit();

        this.autoCommit = autoCommit;
    }

    /*
     * gets the current auto-commit state
     *
     * @return Current state of the auto-commit mode
     * @see setAutoCommit
     */
    public boolean getAutoCommit() throws SQLException
    {
        checkClosed();
        return this.autoCommit;
    }

    private void executeTransactionCommand(Query query) throws SQLException {
        getQueryExecutor().execute(query, null, new TransactionCommandHandler(),
                                   0, 0, QueryExecutor.QUERY_NO_METADATA | QueryExecutor.QUERY_NO_RESULTS | QueryExecutor.QUERY_SUPPRESS_BEGIN);
    }

    /*
     * The method commit() makes all changes made since the previous
     * commit/rollback permanent and releases any database locks currently
     * held by the Connection. This method should only be used when
     * auto-commit has been disabled.
     *
     * @exception SQLException if a database access error occurs,
     *                         this method  is called on a closed connection or
     *                         this Connection object is in auto-commit mode
     * @see setAutoCommit
     */
    public void commit() throws SQLException
    {
        checkClosed();

        if (autoCommit)
            throw new PSQLException(GT.tr("Cannot commit when autoCommit is enabled."),
                                    PSQLState.NO_ACTIVE_SQL_TRANSACTION);

        if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            executeTransactionCommand(commitQuery);
    }

    protected void checkClosed() throws SQLException {
        if (isClosed())
            throw new PSQLException(GT.tr("This connection has been closed."),
                                    PSQLState.CONNECTION_DOES_NOT_EXIST);
    }
 

    /*
     * The method rollback() drops all changes made since the previous
     * commit/rollback and releases any database locks currently held by
     * the Connection.
     *
     * @exception SQLException if a database access error occurs,
     *                         this method  is called on a closed connection or
     *                         this Connection object is in auto-commit mode
     * @see commit
     */
    public void rollback() throws SQLException
    {
        checkClosed();

        if (autoCommit)
            throw new PSQLException(GT.tr("Cannot rollback when autoCommit is enabled."),
                                    PSQLState.NO_ACTIVE_SQL_TRANSACTION);

        if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            executeTransactionCommand(rollbackQuery);
    }

    public int getTransactionState() {
        return protoConnection.getTransactionState();
    }

    /*
     * Get this Connection's current transaction isolation mode.
     *
     * @return the current TRANSACTION_* mode value
     * @exception SQLException if a database access error occurs
     */
    public int getTransactionIsolation() throws SQLException
    {
        checkClosed();

        String level = null;

        if (haveMinimumServerVersion("7.3"))
        {
            // 7.3+ returns the level as a query result.
            ResultSet rs = execSQLQuery("SHOW TRANSACTION ISOLATION LEVEL"); // nb: no BEGIN triggered
            if (rs.next())
                level = rs.getString(1);
            rs.close();
        }
        else
        {
            // 7.2 returns the level as an INFO message. Ew.
            // We juggle the warning chains a bit here.

            // Swap out current warnings.
            SQLWarning saveWarnings = getWarnings();
            clearWarnings();

            // Run the query any examine any resulting warnings.
            execSQLUpdate("SHOW TRANSACTION ISOLATION LEVEL"); // nb: no BEGIN triggered
            SQLWarning warning = getWarnings();
            if (warning != null)
                level = warning.getMessage();

            // Swap original warnings back.
            clearWarnings();
            if (saveWarnings != null)
                addWarning(saveWarnings);
        }

        // XXX revisit: throw exception instead of silently eating the error in unkwon cases?
        if (level == null)
            return Connection.TRANSACTION_READ_COMMITTED; // Best guess.

        level = level.toUpperCase(Locale.US);
        if (level.indexOf("READ COMMITTED") != -1)
            return Connection.TRANSACTION_READ_COMMITTED;
        if (level.indexOf("READ UNCOMMITTED") != -1)
            return Connection.TRANSACTION_READ_UNCOMMITTED;
        if (level.indexOf("REPEATABLE READ") != -1)
            return Connection.TRANSACTION_REPEATABLE_READ;
        if (level.indexOf("SERIALIZABLE") != -1)
            return Connection.TRANSACTION_SERIALIZABLE;

        return Connection.TRANSACTION_READ_COMMITTED; // Best guess.
    }

    /*
     * You can call this method to try to change the transaction
     * isolation level using one of the TRANSACTION_* values.
     *
     * <B>Note:</B> setTransactionIsolation cannot be called while
     * in the middle of a transaction
     *
     * @param level one of the TRANSACTION_* isolation values with
     * the exception of TRANSACTION_NONE; some databases may
     * not support other values
     * @exception SQLException if a database access error occurs
     * @see java.sql.DatabaseMetaData#supportsTransactionIsolationLevel
     */
    public void setTransactionIsolation(int level) throws SQLException
    {
        checkClosed();

        if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            throw new PSQLException(GT.tr("Cannot change transaction isolation level in the middle of a transaction."),
                                    PSQLState.ACTIVE_SQL_TRANSACTION);

        String isolationLevelName = getIsolationLevelName(level);
        if (isolationLevelName == null)
            throw new PSQLException(GT.tr("Transaction isolation level {0} not supported.", new Integer(level)), PSQLState.NOT_IMPLEMENTED);

        String isolationLevelSQL = "SET SESSION CHARACTERISTICS AS TRANSACTION ISOLATION LEVEL " + isolationLevelName;
        execSQLUpdate(isolationLevelSQL); // nb: no BEGIN triggered
    }

    protected String getIsolationLevelName(int level)
    {
        boolean pg80 = haveMinimumServerVersion("8.0");

        if (level == Connection.TRANSACTION_READ_COMMITTED)
        {
            return "READ COMMITTED";
        }
        else if (level == Connection.TRANSACTION_SERIALIZABLE)
        {
            return "SERIALIZABLE";
        }
        else if (pg80 && level == Connection.TRANSACTION_READ_UNCOMMITTED)
        {
            return "READ UNCOMMITTED";
        }
        else if (pg80 && level == Connection.TRANSACTION_REPEATABLE_READ)
        {
            return "REPEATABLE READ";
        }

        return null;
    }

    /*
     * A sub-space of this Connection's database may be selected by
     * setting a catalog name. If the driver does not support catalogs,
     * it will silently ignore this request
     *
     * @exception SQLException if a database access error occurs
     */
    public void setCatalog(String catalog) throws SQLException
    {
        checkClosed();
        //no-op
    }

    /*
     * Return the connections current catalog name, or null if no
     * catalog name is set, or we dont support catalogs.
     *
     * @return the current catalog name or null
     * @exception SQLException if a database access error occurs
     */
    public String getCatalog() throws SQLException
    {
        checkClosed();
        return protoConnection.getDatabase();
    }

    /*
     * Overides finalize(). If called, it closes the connection.
     *
     * This was done at the request of Rachel Greenham
     * <rachel@enlarion.demon.co.uk> who hit a problem where multiple
     * clients didn't close the connection, and once a fortnight enough
     * clients were open to kill the postgres server.
     */
    protected void finalize() throws Throwable
    {
        if (openStackTrace != null)
            logger.log(GT.tr("Finalizing a Connection that was never closed:"), openStackTrace);

        close();
    }

    /*
     * Get server version number
     */
    public String getDBVersionNumber()
    {
        return dbVersionNumber;
    }

    // Parse a "dirty" integer surrounded by non-numeric characters
    private static int integerPart(String dirtyString)
    {
        int start, end;

        for (start = 0; start < dirtyString.length() && !Character.isDigit(dirtyString.charAt(start)); ++start)
            ;

        for (end = start; end < dirtyString.length() && Character.isDigit(dirtyString.charAt(end)); ++end)
            ;

        if (start == end)
            return 0;

        return Integer.parseInt(dirtyString.substring(start, end));
    }

    /*
     * Get server major version
     */
    public int getServerMajorVersion()
    {
        try
        {
            StringTokenizer versionTokens = new StringTokenizer(dbVersionNumber, ".");  // aaXbb.ccYdd
            return integerPart(versionTokens.nextToken()); // return X
        }
        catch (NoSuchElementException e)
        {
            return 0;
        }
    }

    /*
     * Get server minor version
     */
    public int getServerMinorVersion()
    {
        try
        {
            StringTokenizer versionTokens = new StringTokenizer(dbVersionNumber, ".");  // aaXbb.ccYdd
            versionTokens.nextToken(); // Skip aaXbb
            return integerPart(versionTokens.nextToken()); // return Y
        }
        catch (NoSuchElementException e)
        {
            return 0;
        }
    }

    /**
     * Is the server we are connected to running at least this version?
     * This comparison method will fail whenever a major or minor version
     * goes to two digits (10.3.0) or (7.10.1).
     */
    public boolean haveMinimumServerVersion(String ver)
    {
        return (dbVersionNumber.compareTo(ver) >= 0);
    }

    /*
     * This method returns true if the compatible level set in the connection
     * (which can be passed into the connection or specified in the URL)
     * is at least the value passed to this method.  This is used to toggle
     * between different functionality as it changes across different releases
     * of the jdbc driver code.  The values here are versions of the jdbc client
     * and not server versions.  For example in 7.1 get/setBytes worked on
     * LargeObject values, in 7.2 these methods were changed to work on bytea
     * values. This change in functionality could be disabled by setting the
     * "compatible" level to be 7.1, in which case the driver will revert to
     * the 7.1 functionality.
     */
    public boolean haveMinimumCompatibleVersion(String ver)
    {
        return (compatible.compareTo(ver) >= 0);
    }


    public Encoding getEncoding() {
        return protoConnection.getEncoding();
    }

    public byte[] encodeString(String str) throws SQLException {
        try
        {
            return getEncoding().encode(str);
        }
        catch (IOException ioe)
        {
            throw new PSQLException(GT.tr("Unable to translate data into the desired encoding."), PSQLState.DATA_ERROR, ioe);
        }
    }

    public String escapeString(String str) throws SQLException {
        return Utils.appendEscapedLiteral(null, str,
                protoConnection.getStandardConformingStrings()).toString();
    }

    public boolean getStandardConformingStrings() {
        return protoConnection.getStandardConformingStrings();
    }

    // This is a cache of the DatabaseMetaData instance for this connection
    protected java.sql.DatabaseMetaData metadata;

    /*
     * Tests to see if a Connection is closed
     *
     * @return the status of the connection
     * @exception SQLException (why?)
     */
    public boolean isClosed() throws SQLException
    {
        return protoConnection.isClosed();
    }

    public void cancelQuery() throws SQLException
    {
        checkClosed();
        protoConnection.sendQueryCancel();
    }

    public PGNotification[] getNotifications() throws SQLException
    {
        checkClosed();
        getQueryExecutor().processNotifies();
        // Backwards-compatibility hand-holding.
        PGNotification[] notifications = protoConnection.getNotifications();
        return (notifications.length == 0 ? null : notifications);
    }

    //
    // Handler for transaction queries
    //
    private class TransactionCommandHandler implements ResultHandler {
        private SQLException error;

        public void handleResultRows(Query fromQuery, Field[] fields, List tuples, ResultCursor cursor) {
        }
        public void handleCommandStatus(String status, int updateCount, long insertOID) {
        }

        public void handleWarning(SQLWarning warning) {
            AbstractJdbc2Connection.this.addWarning(warning);
        }

        public void handleError(SQLException newError) {
            if (error == null)
                error = newError;
            else
                error.setNextException(newError);
        }

        public void handleCompletion() throws SQLException {
            if (error != null)
                throw error;
        }
    }

    public int getPrepareThreshold() {
        return prepareThreshold;
    }

    public void setPrepareThreshold(int newThreshold) {
        this.prepareThreshold = (newThreshold <= 0 ? 0 : newThreshold);
    }


    public void setTypeMapImpl(java.util.Map map) throws SQLException
    {
        typemap = map;
    }

    public Logger getLogger()
    {
        return logger;
    }


    //Because the get/setLogStream methods are deprecated in JDBC2
    //we use the get/setLogWriter methods here for JDBC2 by overriding
    //the base version of this method
    protected void enableDriverManagerLogging()
    {
        if (DriverManager.getLogWriter() == null)
        {
            DriverManager.setLogWriter(new PrintWriter(System.out, true));
        }
    }

    public int getProtocolVersion()
    {
        return protoConnection.getProtocolVersion();
    }

    public boolean getStringVarcharFlag()
    {
        return bindStringAsVarchar;
    }

    private CopyManager copyManager = null;
    public CopyManager getCopyAPI() throws SQLException
    {
        checkClosed();
        if (copyManager == null)
            copyManager = new CopyManager(this);
        return copyManager;
    }

    public boolean binaryTransferSend(int oid) {
        return useBinarySendForOids.contains(oid);
    }
}
