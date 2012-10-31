/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core;

import java.util.Properties;
import java.sql.SQLException;

import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

/**
 * Handles protocol-specific connection setup.
 *
 * @author Oliver Jowett (oliver@opencloud.com)
 */
public abstract class ConnectionFactory {
    /**
     * Protocol version to implementation instance map.
     * If no protocol version is specified, instances are
     * tried in order until an exception is thrown or a non-null
     * connection is returned.
     */
    private static final Object[][] versions = {
                { "3", new org.postgresql.core.v3.ConnectionFactoryImpl() },
                { "2", new org.postgresql.core.v2.ConnectionFactoryImpl() },
            };

    /**
     * Establishes and initializes a new connection.
     *<p>
     * If the "protocolVersion" property is specified, only that protocol
     * version is tried. Otherwise, all protocols are tried in order, falling
     * back to older protocols as necessary.
     *<p>
     * Currently, protocol versions 3 (7.4+) and 2 (pre-7.4) are supported.
     *
     * @param hostSpecs at least one host and port to connect to; multiple elements for round-robin failover
     * @param user the username to authenticate with; may not be null.
     * @param database the database on the server to connect to; may not be null.
     * @param info extra properties controlling the connection;
     *    notably, "password" if present supplies the password to authenticate with.
     * @param logger the logger to use for this connection
     * @return the new, initialized, connection
     * @throws SQLException if the connection could not be established.
     */
    public static ProtocolConnection openConnection(HostSpec[] hostSpecs, String user, String database, Properties info, Logger logger) throws SQLException {
        String protoName = info.getProperty("protocolVersion");

        for (int i = 0; i < versions.length; ++i)
        {
            String versionProtoName = (String) versions[i][0];
            if (protoName != null && !protoName.equals(versionProtoName))
                continue;

            ConnectionFactory factory = (ConnectionFactory) versions[i][1];
            ProtocolConnection connection = factory.openConnectionImpl(hostSpecs, user, database, info, logger);
            if (connection != null)
                return connection;
        }

        throw new PSQLException(GT.tr("A connection could not be made using the requested protocol {0}.", protoName),
                                PSQLState.CONNECTION_UNABLE_TO_CONNECT);
    }

    /**
     * Implementation of {@link #openConnection} for a particular protocol version.
     * Implemented by subclasses of {@link ConnectionFactory}.
     *
     * @param hostSpecs at least one host and port to connect to; multiple elements for round-robin failover
     * @param user the username to authenticate with; may not be null.
     * @param database the database on the server to connect to; may not be null.
     * @param info extra properties controlling the connection;
     *    notably, "password" if present supplies the password to authenticate with.
     * @param logger the logger to use for this connection
     * @return the new, initialized, connection, or <code>null</code> if this protocol
     *    version is not supported by the server.
     * @throws SQLException if the connection could not be established for a reason other
     *    than protocol version incompatibility.
     */
    public abstract ProtocolConnection openConnectionImpl(HostSpec[] hostSpecs, String user, String database, Properties info, Logger logger) throws SQLException;
}
