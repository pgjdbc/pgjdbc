/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.core.v3;

import legacy.org.postgresql.core.*;
import legacy.org.postgresql.gss.MakeGSS;
import legacy.org.postgresql.ssl.MakeSSL;
import legacy.org.postgresql.util.*;

import java.io.IOException;
import java.net.ConnectException;
import java.sql.SQLException;
import java.util.Properties;

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
    private static final int AUTH_REQ_GSS = 7;
    private static final int AUTH_REQ_GSS_CONTINUE = 8;
    private static final int AUTH_REQ_SSPI = 9;

    /** Marker exception; thrown when we want to fall back to using V2. */
    private static class UnsupportedProtocolException extends IOException {
    }

    public ProtocolConnection openConnectionImpl(String host, int port, String user, String database, Properties info, Logger logger) throws SQLException {
        // Extract interesting values from the info properties:
        //  - the SSL setting
        boolean requireSSL = (info.getProperty("ssl") != null);
        boolean trySSL = requireSSL; // XXX temporary until we revisit the ssl property values

        //  - the TCP keep alive setting
        boolean requireTCPKeepAlive = (Boolean.valueOf(info.getProperty("tcpKeepAlive")).booleanValue());

        // NOTE: To simplify this code, it is assumed that if we are
        // using the V3 protocol, then the database is at least 7.4.  That
        // eliminates the need to check database versions and maintain
        // backward-compatible code here.
        //
        // Change by Chris Smith <cdsmith@twu.net>

        if (logger.logDebug())
            logger.debug("Trying to establish a protocol version 3 connection to " + host + ":" + port);

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
            
            // Set the socket timeout if the "socketTimeout" property has been set.
            String socketTimeoutProperty = info.getProperty("socketTimeout", "0");
            try {
                int socketTimeout = Integer.parseInt(socketTimeoutProperty);
                if (socketTimeout > 0) {
                    newStream.getSocket().setSoTimeout(socketTimeout*1000);
                }
            } catch (NumberFormatException nfe) {
                logger.info("Couldn't parse socketTimeout value:" + socketTimeoutProperty);
            }

            // Enable TCP keep-alive probe if required.
            newStream.getSocket().setKeepAlive(requireTCPKeepAlive);

            // Construct and send a startup packet.
            String[][] params = {
                                    { "user", user },
                                    { "database", database },
                                    { "client_encoding", "UTF8" },
                                    { "DateStyle", "ISO" },
                                    { "extra_float_digits", "2" }
                                };

            sendStartupPacket(newStream, params, logger);

            // Do authentication (until AuthenticationOk).
            doAuthentication(newStream, host, user, info, logger);

            // Do final startup.
            ProtocolConnectionImpl protoConnection = new ProtocolConnectionImpl(newStream, user, database, info, logger);
            readStartupMessages(newStream, protoConnection, logger);

            runInitialQueries(protoConnection, info, logger);

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
            throw new PSQLException(GT.tr("Connection refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections."), PSQLState.CONNECTION_UNABLE_TO_CONNECT, cex);
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
            MakeSSL.convert(pgStream, info, logger);
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
            encodedParams[i*2] = params[i][0].getBytes("UTF-8");
            encodedParams[i*2 + 1] = params[i][1].getBytes("UTF-8");
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

    private void doAuthentication(PGStream pgStream, String host, String user, Properties info, Logger logger) throws IOException, SQLException
    {
        // Now get the response from the backend, either an error message
        // or an authentication request

        String password = info.getProperty("password");

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
                int l_elen = pgStream.ReceiveInteger4();
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
                int l_msgLen = pgStream.ReceiveInteger4();

                // Get the type of request
                int areq = pgStream.ReceiveInteger4();

                // Process the request.
                switch (areq)
                {
                case AUTH_REQ_CRYPT:
                    {
                        byte[] salt = pgStream.Receive(2);

                        if (logger.logDebug())
                            logger.debug(" <=BE AuthenticationReqCrypt(salt='" + new String(salt, "US-ASCII") + "')");

                        if (password == null)
                            throw new PSQLException(GT.tr("The server requested password-based authentication, but no password was provided."), PSQLState.CONNECTION_REJECTED);

                        byte[] encodedResult = UnixCrypt.crypt(salt, password.getBytes("UTF-8"));

                        if (logger.logDebug())
                            logger.debug(" FE=> Password(crypt='" + new String(encodedResult, "US-ASCII") + "')");

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

                        byte[] digest = MD5Digest.encode(user.getBytes("UTF-8"), password.getBytes("UTF-8"), md5Salt);

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

                        byte[] encodedPassword = password.getBytes("UTF-8");

                        pgStream.SendChar('p');
                        pgStream.SendInteger4(4 + encodedPassword.length + 1);
                        pgStream.Send(encodedPassword);
                        pgStream.SendChar(0);
                        pgStream.flush();

                        break;
                    }

                case AUTH_REQ_GSS:
                    MakeGSS.authenticate(pgStream, host,
                            user, password, 
                            info.getProperty("jaasApplicationName"),
                            info.getProperty("kerberosServerName"),
                            logger);
                    break;

                case AUTH_REQ_SSPI:
                    if (logger.logDebug())
                        logger.debug(" <=BE AuthenticationReqSSPI");

                    throw new PSQLException(GT.tr("SSPI authentication is not supported because it is not portable.  Try configuring the server to use GSSAPI instead."), PSQLState.CONNECTION_REJECTED);

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
                if (pgStream.ReceiveInteger4() != 5)
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
                int l_msgLen = pgStream.ReceiveInteger4();
                if (l_msgLen != 12)
                    throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.PROTOCOL_VIOLATION);

                int pid = pgStream.ReceiveInteger4();
                int ckey = pgStream.ReceiveInteger4();

                if (logger.logDebug())
                    logger.debug(" <=BE BackendKeyData(pid=" + pid + ",ckey=" + ckey + ")");

                protoConnection.setBackendKeyData(pid, ckey);
                break;

            case 'E':
                // Error
                int l_elen = pgStream.ReceiveInteger4();
                ServerErrorMessage l_errorMsg = new ServerErrorMessage(pgStream.ReceiveString(l_elen - 4), logger.getLogLevel());

                if (logger.logDebug())
                    logger.debug(" <=BE ErrorMessage(" + l_errorMsg + ")");

                throw new PSQLException(l_errorMsg);

            case 'N':
                // Warning
                int l_nlen = pgStream.ReceiveInteger4();
                ServerErrorMessage l_warnMsg = new ServerErrorMessage(pgStream.ReceiveString(l_nlen - 4), logger.getLogLevel());

                if (logger.logDebug())
                    logger.debug(" <=BE NoticeResponse(" + l_warnMsg + ")");

                protoConnection.addWarning(new PSQLWarning(l_warnMsg));
                break;

            case 'S':
                // ParameterStatus
                int l_len = pgStream.ReceiveInteger4();
                String name = pgStream.ReceiveString();
                String value = pgStream.ReceiveString();

                if (logger.logDebug())
                    logger.debug(" <=BE ParameterStatus(" + name + " = " + value + ")");

                if (name.equals("server_version"))
                    protoConnection.setServerVersion(value);
                else if (name.equals("client_encoding"))
                {
                    if (!value.equals("UTF8"))
                        throw new PSQLException(GT.tr("Protocol error.  Session setup failed."), PSQLState.PROTOCOL_VIOLATION);
                    pgStream.setEncoding(Encoding.getDatabaseEncoding("UTF8"));
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

    private void runInitialQueries(ProtocolConnection protoConnection, Properties info, Logger logger) throws SQLException
    {
        String dbVersion = protoConnection.getServerVersion();

        if (dbVersion.compareTo("9.0") >= 0) {
            SetupQueryRunner.run(protoConnection, "SET extra_float_digits = 3", false);
        }

        String appName = info.getProperty("ApplicationName");
        if (appName != null && dbVersion.compareTo("9.0") >= 0) {
            StringBuffer sql = new StringBuffer();
            sql.append("SET application_name = '");
            Utils.appendEscapedLiteral(sql, appName, protoConnection.getStandardConformingStrings());
            sql.append("'");
            SetupQueryRunner.run(protoConnection, sql.toString(), false);
        }

    }

}
