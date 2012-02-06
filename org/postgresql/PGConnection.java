/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql;

import java.sql.*;
import org.postgresql.copy.CopyManager;
import org.postgresql.fastpath.Fastpath;
import org.postgresql.largeobject.LargeObjectManager;

/**
 *  This interface defines the public PostgreSQL extensions to
 *  java.sql.Connection. All Connections returned by the PostgreSQL driver
 *  implement PGConnection.
 */
public interface PGConnection
{
    /**
     * This method returns any notifications that have been received
     * since the last call to this method.
     * Returns null if there have been no notifications.
     * @since 7.3
     */
    public PGNotification[] getNotifications() throws SQLException;

    /**
     * This returns the COPY API for the current connection.
     * @since 8.4
     */
    public CopyManager getCopyAPI() throws SQLException;

    /**
     * This returns the LargeObject API for the current connection.
     * @since 7.3
     */
    public LargeObjectManager getLargeObjectAPI() throws SQLException;

    /**
     * This returns the Fastpath API for the current connection.
     * @since 7.3
     */
    public Fastpath getFastpathAPI() throws SQLException;

    /**
     * This allows client code to add a handler for one of org.postgresql's
     * more unique data types. It is approximately equivalent to
     * <code>addDataType(type, Class.forName(name))</code>.
     *
     * @deprecated As of 8.0, replaced by
     *   {@link #addDataType(String,Class)}. This deprecated method does not
     *   work correctly for registering classes that cannot be directly loaded
     *   by the JDBC driver's classloader.
     * @throws RuntimeException if the type cannot be registered (class not
     *   found, etc).
     */
    public void addDataType(String type, String name);

    /**
     * This allows client code to add a handler for one of org.postgresql's
     * more unique data types. 
     *
     * <p><b>NOTE:</b> This is not part of JDBC, but an extension.
     *
     * <p>The best way to use this is as follows:
     *
     * <p><pre>
     * ...
     * ((org.postgresql.PGConnection)myconn).addDataType("mytype", my.class.name.class);
     * ...
     * </pre>
     *
     * <p>where myconn is an open Connection to org.postgresql.
     *
     * <p>The handling class must extend org.postgresql.util.PGobject
     *
     * @since 8.0 
     *
     * @param type the PostgreSQL type to register
     * @param klass the class implementing the Java representation of the type;
     *    this class must implement {@link org.postgresql.util.PGobject}).
     *
     * @throws SQLException if <code>klass</code> does not implement
     *    {@link org.postgresql.util.PGobject}).
     *
     * @see org.postgresql.util.PGobject
     */
    public void addDataType(String type, Class klass)
    throws SQLException;

    /**
     * Set the default statement reuse threshold before enabling server-side
     * prepare. See {@link org.postgresql.PGStatement#setPrepareThreshold(int)} for 
     * details.
     *
     * @since build 302
     * @param threshold the new threshold
     */
    public void setPrepareThreshold(int threshold);

    /**
     * Get the default server-side prepare reuse threshold for statements created
     * from this connection.
     *
     * @since build 302
     * @return the current threshold
     */
    public int getPrepareThreshold();

}

