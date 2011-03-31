/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2005, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v3/ConnectionFactoryImpl.java,v 1.12 2006/12/01 08:53:45 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import java.util.Properties;

import java.sql.*;
import java.io.IOException;
import java.net.ConnectException;

import org.postgresql.Driver;
import org.postgresql.core.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLWarning;
import org.postgresql.util.ServerErrorMessage;
import org.postgresql.util.UnixCrypt;
import org.postgresql.util.MD5Digest;
import org.postgresql.util.GT;

/**
 * ConnectionFactory implementation for version 3 (7.4+) connections.
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

    /** Marker exception; thrown when we want to fall back to using V2. */
    private static class UnsupportedProtocolException extends IOException {
    }

    public ProtocolConnection openConnectionImpl(String host, int port, String user, String database, Properties info, Logger logger) throws SQLException {
        // Extract interesting values from the info properties:
        //  - the SSL setting
        boolean requireSSL = (info.getProperty("ssl") != null);
        boolean trySSL = requireSSL; // XXX temporary until we revisit the ssl property values

        // NOTE: To simplify this code, it is assumed that if we are
        // using the V3 protocol, then the database is at least 7.4.  That
        // eliminates the need to check database versions and maintain
        // backward-compatible code here.
        //
        // Change by Chris Smith <cdsmith@twu.net>

        if (logger.logDebug())
            logger.debug("Trying to establish a protocol version 3 connection to " + host + ":" + port);

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
                newStream = enableSSL(newStream, requireSSL, info, logger);

            // Construct and send a startup packet.
            String[][] params = {
                                    { "user", user },
                                    { "database", database },
                                    { "client_encoding", "UNICODE" },
                                    { "DateStyle", "ISO" }
                                };

            sendStartupPacket(newStream, params, logger);

            // Do authentication (until AuthenticationOk).
            doAuthentication(newStream, user, info.getProperty("password"), logger);

            // Do final startup.
            ProtocolConnectionImpl protoConnection = new ProtocolConnectionImpl(newStream, user, database, info, logger);
            readStartupMessages(newStream, protoConnection, logger);

            // And we're done.
            return protoConnection;
        }
        catch (UnsupportedProtocolException upe)
        {
            // Swallow this and return null so ConnectionFactory tries the next protocol.
            if (logger.logDebug())
                logger.debug("Protocol not supported, abandoning connection.");
            try
            {
                newStream.close();
            }
            catch (IOException e)
            {
            }
            return null;
        }
        catch (ConnectException cex)
        {
            // Added by Peter Mount <peter@retep.org.uk>
            // ConnectException is thrown when the connection cannot be made.
            // we trap this an return a more meaningful message for the end user
            throw new PSQLException (GT.tr("Connection refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections."), PSQLState.CONNECTION_UNABLE_TO_CONNECT, cex);
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

    private PGStream enableSSL(PGStream pgStream, boolean requireSSL, Properties info, Logger logger) throws IOException, SQLException {
        if (logger.logDebug())
            logger.debug(" FE=> SSLRequest");

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
            if (logger.logDebug())
                logger.debug(" <=BE SSLError");

            // Server doesn't even know about the SSL handshake protocol
            if (requireSSL)
                throw new PSQLException(GT.tr("The server does not support SSL."), PSQLState.CONNECTION_REJECTED);

            // We have to reconnect to continue.
            pgStream.close();
            return new PGStream(pgStream.getHost(), pgStream.getPort());

        case 'N':
            if (logger.logDebug())
                logger.debug(" <=BE SSLRefused");

            // Server does not support ssl
            if (requireSSL)
                throw new PSQLException(GT.tr("The server does not support SSL."), PSQLState.CONNECTION_REJECTED);

            return pgStream;

        case 'S':
            if (logger.logDebug())
                logger.debug(" <=BE SSLOk");

            // Server supports ssl
            Driver.makeSSL(pgStream, info, logger);
            return pgStream;

        default:
            throw new PSQLException(GT.tr("An error occured while setting up the SSL connection."), PSQLState.PROTOCOL_VIOLATION);
        }
    }

    private void sendStartupPacket(PGStream pgStream, String[][] params, Logger logger) throws IOException {
        if (logger.logDebug())
        {
            String details = "";
            for (int i = 0; i < params.length; ++i)
            {
                if (i != 0)
                    details += ", ";
                details += params[i][0] + "=" + params[i][1];
            }
            logger.debug(" FE=> StartupPacket(" + details + ")");
        }

        /*
         * Precalculate message length and encode params.
         */
        int length = 4 + 4;
        byte[][] encodedParams = new byte[params.length * 2][];
        for (int i = 0; i < params.length; ++i)
        {
            encodedParams[i*2] = params[i][0].getBytes("US-ASCII");
            encodedParams[i*2 + 1] = params[i][1].getBytes("US-ASCII");
            length += encodedParams[i * 2].length + 1 + encodedParams[i * 2 + 1].length + 1;
        }

        length += 1; // Terminating \0

        /*
         * Send the startup message.
         */
        pgStream.SendInteger4(length);
        pgStream.SendInteger2(3); // protocol major
        pgStream.SendInteger2(0); // protocol minor
        for (int i = 0; i < encodedParams.length; ++i)
        {
            pgStream.Send(encodedParams[i]);
            pgStream.SendChar(0);
        }

        pgStream.SendChar(0);
        pgStream.flush();
    }

    private void doAuthentication(PGStream pgStream, String user, String password, Logger logger) throws IOException, SQLException
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
                int l_elen = pgStream.ReceiveIntegerR(4);
                if (l_elen > 30000)
                {
                    // if the error length is > than 30000 we assume this is really a v2 protocol
                    // server, so trigger fallback.
                    throw new UnsupportedProtocolException();
                }

                ServerErrorMessage errorMsg = new ServerErrorMessage(pgStream.ReceiveString(l_elen - 4), logger.getLogLevel());
                if (logger.logDebug())
                    logger.debug(" <=BE ErrorMessage(" + errorMsg + ")");
                throw new PSQLException(errorMsg);

            case 'R':
                // Authentication request.
                // Get the message length
                int l_msgLen = pgStream.ReceiveIntegerR(4);

                // Get the type of request
                int areq = pgStream.ReceiveIntegerR(4);

                // Process the request.
                switch (areq)
                {
                case AUTH_REQ_CRYPT:
                    {
                        byte[] rst = new byte[2];
                        rst[0] = (byte)pgStream.ReceiveChar();
                        rst[1] = (byte)pgStream.ReceiveChar();
                        String salt = new String(rst, 0, 2, "US-ASCII");

                        if (logger.logDebug())
                            logger.debug(" <=BE AuthenticationReqCrypt(salt='" + salt + "')");

                        if (password == null)
                            throw new PSQLException(GT.tr("The server requested password-based authentication, but no password was provided."), PSQLState.CONNECTION_REJECTED);

                        String result = UnixCrypt.crypt(salt, password);
                        byte[] encodedResult = result.getBytes("US-ASCII");

                        if (logger.logDebug())
                            logger.debug(" FE=> Password(crypt='" + result + "')");

                        pgStream.SendChar('p');
                        pgStream.SendInteger4(4 + encodedResult.length + 1);
                        pgStream.Send(encodedResult);
                        pgStream.SendChar(0);
                        pgStream.flush();

                        break;
                    }

                case AUTH_REQ_MD5:
                    {
                        byte[] md5Salt = pgStream.Receive(4);
                        if (logger.logDebug())
                        {
                            logger.debug(" <=BE AuthenticationReqMD5(salt=" + Utils.toHexString(md5Salt) + ")");
                        }

                        if (password == null)
                            throw new PSQLException(GT.tr("The server requested password-based authentication, but no password was provided."), PSQLState.CONNECTION_REJECTED);

                        byte[] digest = MD5Digest.encode(user, password, md5Salt);

                        if (logger.logDebug())
                        {
                            logger.debug(" FE=> Password(md5digest=" + new String(digest, "US-ASCII") + ")");
                        }

                        pgStream.SendChar('p');
                        pgStream.SendInteger4(4 + digest.length + 1);
                        pgStream.Send(digest);
                        pgStream.SendChar(0);
                        pgStream.flush();

                        break;
                    }

                case AUTH_REQ_PASSWORD:
                    {
                        if (logger.logDebug())
                        {
                            logger.debug(" <=BE AuthenticationReqPassword");
                            logger.debug(" FE=> Password(password=<not shown>)");
                        }

                        if (password == null)
                            throw new PSQLException(GT.tr("The server requested password-based authentication, but no password was provided."), PSQLState.CONNECTION_REJECTED);

                        byte[] encodedPassword = password.getBytes("US-ASCII");

                        pgStream.SendChar('p');
                        pgStream.SendInteger4(4 + encodedPassword.length + 1);
                        pgStream.Send(encodedPassword);
                        pgStream.SendChar(0);
                        pgStream.flush();

                        break;
                    }

                case AUTH_REQ_OK:
                    if (logger.logDebug())
                        logger.debug(" <=BE AuthenticationOk");

                    return ; // We're done.

                default:
                    if (logger.logDebug())
                        logger.debug(" <=BE AuthenticationReq (unsupported type " + ((int)areq) + ")");

                    throw new PSQLException(GT.tr("The authentication type {0} is not supported. Check that you have configured the pg_hba.conf file to include the client''s IP address or subnet, and that it is using an authentication scheme supported by the driver.", new Integer(areq)), PSQLState.CONNECTION_REJECTED);
                }

                break;

            default:
                throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.PROTOCOL_VIOLATION);
            }
        }
    }

    private void readStartupMessages(PGStream pgStream, ProtocolConnectionImpl protoConnection, Logger logger) throws IOException, SQLException {
        while (true)
        {
            int beresp = pgStream.ReceiveChar();
            switch (beresp)
            {
            case 'Z':
                // Ready For Query; we're done.
                if (pgStream.ReceiveIntegerR(4) != 5)
                    throw new IOException("unexpected length of ReadyForQuery packet");

                char tStatus = (char)pgStream.ReceiveChar();
                if (logger.logDebug())
                    logger.debug(" <=BE ReadyForQuery(" + tStatus + ")");

                // Update connection state.
                switch (tStatus)
                {
                case 'I':
                    protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_IDLE);
                    break;
                case 'T':
                    protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_OPEN);
                    break;
                case 'E':
                    protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_FAILED);
                    break;
                default:
                    // Huh?
                    break;
                }

                return ;

            case 'K':
                // BackendKeyData
                int l_msgLen = pgStream.ReceiveIntegerR(4);
                if (l_msgLen != 12)
                    throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.PROTOCOL_VIOLATION);

                int pid = pgStream.ReceiveIntegerR(4);
                int ckey = pgStream.ReceiveIntegerR(4);

                if (logger.logDebug())
                    logger.debug(" <=BE BackendKeyData(pid=" + pid + ",ckey=" + ckey + ")");

                protoConnection.setBackendKeyData(pid, ckey);
                break;

            case 'E':
                // Error
                int l_elen = pgStream.ReceiveIntegerR(4);
                ServerErrorMessage l_errorMsg = new ServerErrorMessage(pgStream.ReceiveString(l_elen - 4), logger.getLogLevel());

                if (logger.logDebug())
                    logger.debug(" <=BE ErrorMessage(" + l_errorMsg + ")");

                throw new PSQLException(l_errorMsg);

            case 'N':
                // Warning
                int l_nlen = pgStream.ReceiveIntegerR(4);
                ServerErrorMessage l_warnMsg = new ServerErrorMessage(pgStream.ReceiveString(l_nlen - 4), logger.getLogLevel());

                if (logger.logDebug())
                    logger.debug(" <=BE NoticeResponse(" + l_warnMsg + ")");

                protoConnection.addWarning(new PSQLWarning(l_warnMsg));
                break;

            case 'S':
                // ParameterStatus
                int l_len = pgStream.ReceiveIntegerR(4);
                String name = pgStream.ReceiveString();
                String value = pgStream.ReceiveString();

                if (logger.logDebug())
                    logger.debug(" <=BE ParameterStatus(" + name + " = " + value + ")");

                if (name.equals("server_version"))
                    protoConnection.setServerVersion(value);
                else if (name.equals("client_encoding"))
                {
                    if (!value.equals("UNICODE"))
                        throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.PROTOCOL_VIOLATION);
                    pgStream.setEncoding(Encoding.getDatabaseEncoding("UNICODE"));
                }
                else if (name.equals("standard_conforming_strings"))
                {
                    if (value.equals("on"))
                        protoConnection.setStandardConformingStrings(true);
                    else if (value.equals("off"))
                        protoConnection.setStandardConformingStrings(false);
                    else
                        throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.PROTOCOL_VIOLATION);
                }

                break;

            default:
                if (logger.logDebug())
                    logger.debug("invalid message type=" + (char)beresp);
                throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.PROTOCOL_VIOLATION);
            }
        }
    }
}
