/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2014, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.util.TimerTask;
import java.sql.*;
import org.postgresql.PGConnection;
import org.postgresql.jdbc2.TimestampUtils;

/**
 * Driver-internal connection interface. Application code should not use
 * this interface.
 */
public interface BaseConnection extends PGConnection, Connection
{
    /**
     * Cancel the current query executing on this connection.
     *
     * @throws SQLException if something goes wrong.
     */
    public void cancelQuery() throws SQLException;

    /**
     * Execute a SQL query that returns a single resultset.
     * Never causes a new transaction to be started regardless of the autocommit setting.
     *
     * @param s the query to execute
     * @return the (non-null) returned resultset
     * @throws SQLException if something goes wrong.
     */
    public ResultSet execSQLQuery(String s) throws SQLException;

    public ResultSet execSQLQuery(String s, int resultSetType, int resultSetConcurrency) throws SQLException;

    /**
     * Execute a SQL query that does not return results.
     * Never causes a new transaction to be started regardless of the autocommit setting.
     *
     * @param s the query to execute
     * @throws SQLException if something goes wrong.
     */
    public void execSQLUpdate(String s) throws SQLException;

    /**
     * Get the QueryExecutor implementation for this connection.
     *
     * @return the (non-null) executor
     */
    public QueryExecutor getQueryExecutor();

    /**
     * Construct and return an appropriate object for the given
     * type and value. This only considers the types registered via
     * {@link org.postgresql.PGConnection#addDataType(String,Class)} and
     * {@link org.postgresql.PGConnection#addDataType(String,String)}.
     *<p>
     * If no class is registered as handling the given type, then a generic
     * {@link org.postgresql.util.PGobject} instance is returned.
     *
     * @param type the backend typename
     * @param value the type-specific string representation of the value
     * @param byteValue the type-specific binary representation of the value
     * @return an appropriate object; never null.
     * @throws SQLException if something goes wrong
     */
    public Object getObject(String type, String value, byte[] byteValue) throws SQLException;

    public Encoding getEncoding() throws SQLException;

    public TypeInfo getTypeInfo();

    /**
     * Check if we should use driver behaviour introduced in a particular
     * driver version. This defaults to behaving as the actual driver's version
     * but can be overridden by the "compatible" URL parameter.
     *
     * If possible you should use the integer version of this method instead.
     * It was introduced with the 9.4 driver release.
     *
     * @deprecated Avoid using this in new code that can require PgJDBC
     * 9.4.
     *
     * @param ver the driver version to check
     * @return true if the driver's behavioural version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    @Deprecated
    public boolean haveMinimumCompatibleVersion(String ver);

    /**
     * Check if we should use driver behaviour introduced in a particular
     * driver version.
     *
     * This defaults to behaving as the actual driver's version but can be
     * overridden by the "compatible" URL parameter.
     *
     * The version is of the form xxyyzz, e.g. 90401 for PgJDBC 9.4.1.
     *
     * @param ver the driver version to check, eg 90401 for 9.4.1
     * @return true if the driver's behavioural version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    public boolean haveMinimumCompatibleVersion(int ver);

    /**
     * Check if we should use driver behaviour introduced in a particular
     * driver version.
     *
     * This defaults to behaving as the actual driver's version but can be
     * overridden by the "compatible" URL parameter.
     *
     * @param ver the driver version to check
     * @return true if the driver's behavioural version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    public boolean haveMinimumCompatibleVersion(Version ver);

    /**
     * Check if we have at least a particular server version.
     *
     * @deprecated Use haveMinimumServerVersion(int) instead
     *
     * @param ver the server version to check
     * @return true if the server version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    @Deprecated
    public boolean haveMinimumServerVersion(String ver);

    /**
     * Check if we have at least a particular server version.
     *
     * The input version is of the form xxyyzz, matching a PostgreSQL
     * version like xx.yy.zz. So 9.0.12 is 90012 .
     *
     * @param ver the server version to check, of the form xxyyzz eg 90401
     * @return true if the server version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    public boolean haveMinimumServerVersion(int ver);

    /**
     * Check if we have at least a particular server version.
     *
     * The input version is of the form xxyyzz, matching a PostgreSQL
     * version like xx.yy.zz. So 9.0.12 is 90012 .
     *
     * @param ver the server version to check
     * @return true if the server version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    public boolean haveMinimumServerVersion(Version ver);

    /**
     * Encode a string using the database's client_encoding
     * (usually UTF8, but can vary on older server versions).
     * This is used when constructing synthetic resultsets (for
     * example, in metadata methods).
     *
     * @param str the string to encode
     * @return an encoded representation of the string
     * @throws SQLException if something goes wrong.
     */
    public byte[] encodeString(String str) throws SQLException;

    /**
     * Escapes a string for use as string-literal within an SQL command. The
     * method chooses the applicable escaping rules based on the value of
     * {@link #getStandardConformingStrings()}.
     * 
     * @param str a string value
     * @return the escaped representation of the string
     * @throws SQLException if the string contains a <tt>\0</tt> character
     */
    public String escapeString(String str) throws SQLException;

    /**
     * Returns whether the server treats string-literals according to the SQL
     * standard or if it uses traditional PostgreSQL escaping rules. Versions
     * up to 8.1 always treated backslashes as escape characters in
     * string-literals. Since 8.2, this depends on the value of the
     * <tt>standard_conforming_strings<tt> server variable.
     * 
     * @return true if the server treats string literals according to the SQL
     *   standard
     * 
     * @see ProtocolConnection#getStandardConformingStrings()
     */
    public boolean getStandardConformingStrings();

    // Ew. Quick hack to give access to the connection-specific utils implementation.
    public TimestampUtils getTimestampUtils();

    // Get the per-connection logger.
    public Logger getLogger();

    // Get the bind-string-as-varchar config flag
    public boolean getStringVarcharFlag();

    /**
     * Get the current transaction state of this connection.
     * 
     * @return a ProtocolConnection.TRANSACTION_* constant.
     */
    public int getTransactionState();

    /**
     * Returns true if value for the given oid should be sent using binary
     * transfer. False if value should be sent using text transfer.
     *
     * @param oid The oid to check.
     * @return True for binary transfer, false for text transfer.
     */
    public boolean binaryTransferSend(int oid);
    
    /**
     *  Return whether to disable column name sanitization. 
     */
    public boolean isColumnSanitiserDisabled();

    /**
     *  Schedule a TimerTask for later execution.
     *  The task will be scheduled with the shared Timer for this connection.
     */
    public void addTimerTask(TimerTask timerTask, long milliSeconds);

    /**
     *  Invoke purge() on the underlying shared Timer so that internal resources will be released.
     */
    public void purgeTimerTasks();
}
