/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2004, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v2/ConnectionFactoryImpl.java,v 1.5 2004/11/07 22:15:34 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v2;

import java.util.Vector;
import java.util.Properties;
import java.util.StringTokenizer;

import java.sql.*;
import java.io.IOException;
import java.net.ConnectException;

import org.postgresql.Driver;
import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.UnixCrypt;
import org.postgresql.util.MD5Digest;
import org.postgresql.util.GT;

/**
 * ConnectionFactory implementation for version 2 (pre-7.4) connections.
 *
 * @author Oliver Jowett (oliver@opencloud.com), based on the previous implementation
 */
public class ConnectionFactoryImpl extends ConnectionFactory {
    private static final int AUTH_REQ_OK = 0;
    private static final int AUTH_REQ_KRB4 = 1;
    private static final int AUTH_REQ_KRB5 = 2;
    private static final int AUTH_REQ_PASSWORD = 3;
    private static final int AUTH_REQ_CRYPT = 4;
    private static final int AUTH_REQ_MD5 = 5;
    private static final int AUTH_REQ_SCM = 6;

    public ProtocolConnection openConnectionImpl(String host, int port, String user, String database, Properties info) throws SQLException {
        // Extract interesting values from the info properties:
        //  - the SSL setting
        boolean requireSSL = (info.getProperty("ssl") != null);
        boolean trySSL = requireSSL; // XXX temporary until we revisit the ssl property values

        if (Driver.logDebug)
            Driver.debug("Trying to establish a protocol version 2 connection to " + host + ":" + port);

        if (!Driver.sslEnabled())
        {
            if (requireSSL)
                throw new PSQLException(GT.tr("The driver does not support SSL."), PSQLState.CONNECTION_FAILURE);
            trySSL = false;
        }

        //
        // Establish a connection.
        //

        PGStream newStream = null;
        try
        {
            newStream = new PGStream(host, port);

            // Construct and send an ssl startup packet if requested.
            if (trySSL)
                newStream = enableSSL(newStream, requireSSL, info);

            // Construct and send a startup packet.
            sendStartupPacket(newStream, user, database);

            // Do authentication (until AuthenticationOk).
            doAuthentication(newStream, user, info.getProperty("password"));

            // Do final startup.
            ProtocolConnectionImpl protoConnection = new ProtocolConnectionImpl(newStream, user, database);
            readStartupMessages(newStream, protoConnection);

            // Run some initial queries
            runInitialQueries(protoConnection, info.getProperty("charSet"));

            // And we're done.
            return protoConnection;
        }
        catch (ConnectException cex)
        {
            // Added by Peter Mount <peter@retep.org.uk>
            // ConnectException is thrown when the connection cannot be made.
            // we trap this an return a more meaningful message for the end user
            throw new PSQLException (GT.tr("Connection refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections."), PSQLState.CONNECTION_REJECTED, cex);
        }
        catch (IOException ioe)
        {
            if (newStream != null)
            {
                try
                {
                    newStream.close();
                }
                catch (IOException e)
                {
                }
            }

            throw new PSQLException (GT.tr("The connection attempt failed."), PSQLState.CONNECTION_UNABLE_TO_CONNECT, ioe);
        }
        catch (SQLException se)
        {
            if (newStream != null)
            {
                try
                {
                    newStream.close();
                }
                catch (IOException e)
                {
                }
            }

            throw se;
        }
    }

    private PGStream enableSSL(PGStream pgStream, boolean requireSSL, Properties info) throws IOException, SQLException {
        if (Driver.logDebug)
            Driver.debug(" FE=> SSLRequest");

        // Send SSL request packet
        pgStream.SendInteger4(8);
        pgStream.SendInteger2(1234);
        pgStream.SendInteger2(5679);
        pgStream.flush();

        // Now get the response from the backend, one of N, E, S.
        int beresp = pgStream.ReceiveChar();
        switch (beresp)
        {
        case 'E':
            if (Driver.logDebug)
                Driver.debug(" <=BE SSLError");

            // Server doesn't even know about the SSL handshake protocol
            if (requireSSL)
                throw new PSQLException(GT.tr("The server does not support SSL."), PSQLState.CONNECTION_FAILURE);

            // We have to reconnect to continue.
            pgStream.close();
            return new PGStream(pgStream.getHost(), pgStream.getPort());

        case 'N':
            if (Driver.logDebug)
                Driver.debug(" <=BE SSLRefused");

            // Server does not support ssl
            if (requireSSL)
                throw new PSQLException(GT.tr("The server does not support SSL."), PSQLState.CONNECTION_FAILURE);

            return pgStream;

        case 'S':
            if (Driver.logDebug)
                Driver.debug(" <=BE SSLOk");

            // Server supports ssl
            Driver.makeSSL(pgStream, info);
            return pgStream;

        default:
            throw new PSQLException(GT.tr("An error occured while setting up the SSL connection."), PSQLState.CONNECTION_FAILURE);
        }
    }

    private void sendStartupPacket(PGStream pgStream, String user, String database) throws IOException {
        //  4: total size including self
        //  2: protocol major
        //  2: protocol minor
        // 64: database name
        // 32: user name
        // 64: options
        // 64: unused
        // 64: tty

        if (Driver.logDebug)
            Driver.debug(" FE=> StartupPacket(user=" + user + ",database=" + database + ")");

        pgStream.SendInteger4(4 + 4 + 64 + 32 + 64 + 64 + 64);
        pgStream.SendInteger2(2); // protocol major
        pgStream.SendInteger2(0); // protocol minor
        pgStream.Send(database.getBytes("US-ASCII"), 64);
        pgStream.Send(user.getBytes("US-ASCII"), 32);
        pgStream.Send(new byte[64]);  // options
        pgStream.Send(new byte[64]);  // unused
        pgStream.Send(new byte[64]);  // tty
        pgStream.flush();
    }

    private void doAuthentication(PGStream pgStream, String user, String password) throws IOException, SQLException
    {
        // Now get the response from the backend, either an error message
        // or an authentication request

        while (true)
        {
            int beresp = pgStream.ReceiveChar();

            switch (beresp)
            {
            case 'E':
                // An error occured, so pass the error message to the
                // user.
                //
                // The most common one to be thrown here is:
                // "User authentication failed"
                //
                String errorMsg = pgStream.ReceiveString();
                if (Driver.logDebug)
                    Driver.debug(" <=BE ErrorMessage(" + errorMsg + ")");
                throw new PSQLException(GT.tr("Connection rejected: {0}.", errorMsg), PSQLState.CONNECTION_REJECTED);

            case 'R':
                // Authentication request.
                // Get the type of request
                int areq = pgStream.ReceiveIntegerR(4);

                // Process the request.
                switch (areq)
                {
                case AUTH_REQ_CRYPT:
                    {
                        String salt = pgStream.ReceiveString(2);

                        if (Driver.logDebug)
                            Driver.debug(" <=BE AuthenticationReqCrypt(salt='" + salt + "')");

                        if (password == null)
                            throw new PSQLException(GT.tr("The server requested password-based authentication, but no password was provided."), PSQLState.CONNECTION_REJECTED);

                        String result = UnixCrypt.crypt(salt, password);
                        byte[] encodedResult = result.getBytes("US-ASCII");

                        if (Driver.logDebug)
                            Driver.debug(" FE=> Password(crypt='" + result + "')");

                        pgStream.SendInteger4(4 + encodedResult.length + 1);
                        pgStream.Send(encodedResult);
                        pgStream.SendChar(0);
                        pgStream.flush();

                        break;
                    }

                case AUTH_REQ_MD5:
                    {
                        byte[] md5Salt = pgStream.Receive(4);
                        if (Driver.logDebug)
                            Driver.debug(" <=BE AuthenticationReqMD5(salt=" + Utils.toHexString(md5Salt) + ")");

                        if (password == null)
                            throw new PSQLException(GT.tr("The server requested password-based authentication, but no password was provided."), PSQLState.CONNECTION_REJECTED);

                        byte[] digest = MD5Digest.encode(user, password, md5Salt);
                        if (Driver.logDebug)
                            Driver.debug(" FE=> Password(md5digest=" + new String(digest, "US-ASCII") + ")");

                        pgStream.SendInteger4(4 + digest.length + 1);
                        pgStream.Send(digest);
                        pgStream.SendChar(0);
                        pgStream.flush();

                        break;
                    }

                case AUTH_REQ_PASSWORD:
                    {
                        if (Driver.logDebug)
                            Driver.debug(" <=BE AuthenticationReqPassword");

                        if (password == null)
                            throw new PSQLException(GT.tr("The server requested password-based authentication, but no password was provided."), PSQLState.CONNECTION_REJECTED);

                        if (Driver.logDebug)
                            Driver.debug(" FE=> Password(password=<not shown>)");

                        byte[] encodedPassword = password.getBytes("US-ASCII");
                        pgStream.SendInteger4(4 + encodedPassword.length + 1);
                        pgStream.Send(encodedPassword);
                        pgStream.SendChar(0);
                        pgStream.flush();

                        break;
                    }

                case AUTH_REQ_OK:
                    if (Driver.logDebug)
                        Driver.debug(" <=BE AuthenticationOk");

                    return ; // We're done.

                default:
                    if (Driver.logDebug)
                        Driver.debug(" <=BE AuthenticationReq (unsupported type " + ((int)areq) + ")");

                    throw new PSQLException(GT.tr("The authentication type {0} is not supported. Check that you have configured the pg_hba.conf file to include the client's IP address or Subnet, and that it is using an authentication scheme supported by the driver.", new Integer(areq)), PSQLState.CONNECTION_REJECTED);
                }

                break;

            default:
                throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.CONNECTION_UNABLE_TO_CONNECT);
            }
        }
    }

    private void readStartupMessages(PGStream pgStream, ProtocolConnectionImpl protoConnection) throws IOException, SQLException {
        while (true)
        {
            int beresp = pgStream.ReceiveChar();
            switch (beresp)
            {
            case 'Z':  // ReadyForQuery
                if (Driver.logDebug)
                    Driver.debug(" <=BE ReadyForQuery");
                return ;

            case 'K':  // BackendKeyData
                int pid = pgStream.ReceiveIntegerR(4);
                int ckey = pgStream.ReceiveIntegerR(4);
                if (Driver.logDebug)
                    Driver.debug(" <=BE BackendKeyData(pid=" + pid + ",ckey=" + ckey + ")");
                protoConnection.setBackendKeyData(pid, ckey);
                break;

            case 'E':  // ErrorResponse
                String errorMsg = pgStream.ReceiveString();
                if (Driver.logDebug)
                    Driver.debug(" <=BE ErrorResponse(" + errorMsg + ")");
                throw new PSQLException(GT.tr("Backend start-up failed: {0}.", errorMsg), PSQLState.CONNECTION_UNABLE_TO_CONNECT);

            case 'N':  // NoticeResponse
                String warnMsg = pgStream.ReceiveString();
                if (Driver.logDebug)
                    Driver.debug(" <=BE NoticeResponse(" + warnMsg + ")");
                protoConnection.addWarning(new SQLWarning(warnMsg));
                break;

            default:
                throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.CONNECTION_UNABLE_TO_CONNECT);
            }
        }
    }

    private class SimpleResultHandler implements ResultHandler {
        private SQLException error;
        private Vector tuples;
        private final ProtocolConnectionImpl protoConnection;

        SimpleResultHandler(ProtocolConnectionImpl protoConnection) {
            this.protoConnection = protoConnection;
        }

        Vector getResults() {
            return tuples;
        }

        public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
            this.tuples = tuples;
        }

        public void handleCommandStatus(String status, int updateCount, long insertOID) {
        }

        public void handleWarning(SQLWarning warning) {
            protoConnection.addWarning(warning);
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

    // Poor man's Statement & ResultSet, used for initial queries while we're
    // still initializing the system.
    private byte[][] runSetupQuery(ProtocolConnectionImpl protoConnection, String queryString, boolean wantResults) throws SQLException {
        QueryExecutor executor = protoConnection.getQueryExecutor();
        Query query = executor.createSimpleQuery(queryString);
        SimpleResultHandler handler = new SimpleResultHandler(protoConnection);

        int flags = QueryExecutor.QUERY_ONESHOT | QueryExecutor.QUERY_SUPPRESS_BEGIN;
        if (!wantResults)
            flags |= QueryExecutor.QUERY_NO_RESULTS | QueryExecutor.QUERY_NO_METADATA;

        try
        {
            executor.execute(query, null, handler, 0, 0, flags);
        }
        finally
        {
            query.close();
        }

        if (!wantResults)
            return null;

        Vector tuples = handler.getResults();
        if (tuples == null || tuples.size() != 1)
            throw new PSQLException(GT.tr("An unexpected result was returned by a query."), PSQLState.CONNECTION_UNABLE_TO_CONNECT);

        return (byte[][]) tuples.elementAt(0);
    }

    private void runInitialQueries(ProtocolConnectionImpl protoConnection, String charSet) throws SQLException, IOException {
        byte[][] results = runSetupQuery(protoConnection, "set datestyle = 'ISO'; select version(), case when pg_encoding_to_char(1) = 'SQL_ASCII' then 'UNKNOWN' else getdatabaseencoding() end", true);

        String rawDbVersion = protoConnection.getEncoding().decode(results[0]);
        StringTokenizer versionParts = new StringTokenizer(rawDbVersion);
        versionParts.nextToken(); /* "PostgreSQL" */
        String dbVersion = versionParts.nextToken(); /* "X.Y.Z" */

        protoConnection.setServerVersion(dbVersion);

        if (dbVersion.compareTo("7.3") >= 0)
        {
            // set encoding to be unicode; set datestyle; ensure autocommit is on
            // (no-op on 7.4, but might be needed under 7.3)
            // The begin/commit is to avoid leaving a transaction open if we're talking to a
            // 7.3 server that defaults to autocommit = off.

            if (Driver.logDebug)
                Driver.debug("Switching to UNICODE client_encoding");

            runSetupQuery(protoConnection, "begin; set autocommit = on; set client_encoding = 'UNICODE'; commit", false);
            protoConnection.setEncoding(Encoding.getDatabaseEncoding("UNICODE"));
        }
        else
        {
            String dbEncoding = (results[1] == null ? null : protoConnection.getEncoding().decode(results[1]));
            if (Driver.logDebug)
            {
                Driver.debug("Specified charset:  " + charSet);
                Driver.debug("Database encoding: " + dbEncoding);
            }

            if (charSet != null)
            {
                // Explicitly specified encoding.
                protoConnection.setEncoding(Encoding.getJVMEncoding(charSet));
            }
            else if (dbEncoding != null)
            {
                // Use database-supplied encoding.
                protoConnection.setEncoding(Encoding.getDatabaseEncoding(dbEncoding));
            }
            else
            {
                // Fall back to defaults.
                // XXX is this ever reached?
                protoConnection.setEncoding(Encoding.defaultEncoding());
            }
        }

        if (Driver.logDebug)
            Driver.debug("Connection encoding (using JVM's nomenclature): " + protoConnection.getEncoding());
    }
}
