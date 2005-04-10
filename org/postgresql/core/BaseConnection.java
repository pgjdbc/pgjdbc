/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2005, PostgreSQL Global Development Group
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/BaseConnection.java,v 1.13 2005/01/11 08:25:43 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.sql.*;
import org.postgresql.PGConnection;

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
     * @return an appropriate object; never null.
     * @throws SQLException if something goes wrong
     */
    public Object getObject(String type, String value) throws SQLException;


    public String getJavaClass(int oid) throws SQLException;

    /**
     * Look up the postgresql type name for a given oid. This is the
     * inverse of {@link #getPGType(String)}.
     *
     * @param oid the type's OID
     * @return the server type name for that OID, or null if unknown
     * @throws SQLException if something goes wrong
     */
    public String getPGType(int oid) throws SQLException;

    /**
     * Look up the oid for a given postgresql type name. This is the
     * inverse of {@link #getPGType(int)}.
     *
     * @param pgTypeName the server type name to look up
     * @return oid the type's OID, or 0 if unknown
     * @throws SQLException if something goes wrong
     */
    public int getPGType(String pgTypeName) throws SQLException;

    /**
     * Look up the SQL typecode for a given type oid.
     *
     * @param oid the type's OID
     * @return the SQL typecode (a constant from {@link java.sql.Types}) for the type
     * @throws SQLException if something goes wrong
     */
    public int getSQLType(int oid) throws SQLException;

    /**
     * Look up the SQL typecode for a given postgresql type name.
     *
     * @param pgTypeName the server type name to look up
     * @return the SQL typecode (a constant from {@link java.sql.Types}) for the type
     * @throws SQLException if something goes wrong
     */
    public int getSQLType(String pgTypeName) throws SQLException;

    /**
     * Check if we should use driver behaviour introduced in a particular
     * driver version. This defaults to behaving as the actual driver's version
     * but can be overridden by the "compatible" URL parameter.
     *
     * @param ver the driver version to check
     * @return true if the driver's behavioural version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    public boolean haveMinimumCompatibleVersion(String ver);

    /**
     * Check if we have at least a particular server version.
     *
     * @param ver the server version to check
     * @return true if the server version is at least "ver".
     * @throws SQLException if something goes wrong
     */
    public boolean haveMinimumServerVersion(String ver);

    /**
     * Encode a string using the database's client_encoding
     * (usually UNICODE, but can vary on older server versions).
     * This is used when constructing synthetic resultsets (for
     * example, in metadata methods).
     *
     * @param str the string to encode
     * @return an encoded representation of the string
     * @throws SQException if something goes wrong.
     */
    public byte[] encodeString(String str) throws SQLException;
}
