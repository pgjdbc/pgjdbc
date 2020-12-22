/*
 * Copyright (c) 2003, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */
// Copyright (c) 2004, Open Cloud Limited.

package org.postgresql.core.v3;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.PGProperty;
import org.postgresql.core.ConnectionFactory;
import org.postgresql.core.PGStream;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ServerVersion;
import org.postgresql.core.SetupQueryRunner;
import org.postgresql.core.SocketFactoryFactory;
import org.postgresql.core.Tuple;
import org.postgresql.core.Utils;
import org.postgresql.core.Version;
import org.postgresql.hostchooser.CandidateHost;
import org.postgresql.hostchooser.GlobalHostStatusTracker;
import org.postgresql.hostchooser.HostChooser;
import org.postgresql.hostchooser.HostChooserFactory;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.hostchooser.HostStatus;
import org.postgresql.jdbc.GSSEncMode;
import org.postgresql.jdbc.SslMode;
import org.postgresql.sspi.ISSPIClient;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.MD5Digest;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.SocketFactory;

/**
 * ConnectionFactory implementation for version 3 (7.4+) connections.
 *
 * @author Oliver Jowett (oliver@opencloud.com), based on the previous implementation
 */
public class ConnectionFactoryImpl extends ConnectionFactory {

  private static final Logger LOGGER = Logger.getLogger(ConnectionFactoryImpl.class.getName());
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
  private static final int AUTH_REQ_SASL = 10;
  private static final int AUTH_REQ_SASL_CONTINUE = 11;
  private static final int AUTH_REQ_SASL_FINAL = 12;

  private ISSPIClient createSSPI(PGStream pgStream,
      @Nullable String spnServiceClass,
      boolean enableNegotiate) {
    try {
      @SuppressWarnings("unchecked")
      Class<ISSPIClient> c = (Class<ISSPIClient>) Class.forName("org.postgresql.sspi.SSPIClient");
      return c.getDeclaredConstructor(PGStream.class, String.class, boolean.class)
          .newInstance(pgStream, spnServiceClass, enableNegotiate);
    } catch (Exception e) {
      // This catched quite a lot exceptions, but until Java 7 there is no ReflectiveOperationException
      throw new IllegalStateException("Unable to load org.postgresql.sspi.SSPIClient."
          + " Please check that SSPIClient is included in your pgjdbc distribution.", e);
    }
  }

  private PGStream tryConnect(String user, String database,
      Properties info, SocketFactory socketFactory, HostSpec hostSpec,
      SslMode sslMode, GSSEncMode gssEncMode)
      throws SQLException, IOException {
    int connectTimeout = PGProperty.CONNECT_TIMEOUT.getInt(info) * 1000;

    PGStream newStream = new PGStream(socketFactory, hostSpec, connectTimeout);

    // Set the socket timeout if the "socketTimeout" property has been set.
    int socketTimeout = PGProperty.SOCKET_TIMEOUT.getInt(info);
    if (socketTimeout > 0) {
      newStream.setNetworkTimeout(socketTimeout * 1000);
    }

    String maxResultBuffer = PGProperty.MAX_RESULT_BUFFER.get(info);
    newStream.setMaxResultBuffer(maxResultBuffer);

    // Enable TCP keep-alive probe if required.
    boolean requireTCPKeepAlive = PGProperty.TCP_KEEP_ALIVE.getBoolean(info);
    newStream.getSocket().setKeepAlive(requireTCPKeepAlive);

    // Try to set SO_SNDBUF and SO_RECVBUF socket options, if requested.
    // If receiveBufferSize and send_buffer_size are set to a value greater
    // than 0, adjust. -1 means use the system default, 0 is ignored since not
    // supported.

    // Set SO_RECVBUF read buffer size
    int receiveBufferSize = PGProperty.RECEIVE_BUFFER_SIZE.getInt(info);
    if (receiveBufferSize > -1) {
      // value of 0 not a valid buffer size value
      if (receiveBufferSize > 0) {
        newStream.getSocket().setReceiveBufferSize(receiveBufferSize);
      } else {
        LOGGER.log(Level.WARNING, "Ignore invalid value for receiveBufferSize: {0}", receiveBufferSize);
      }
    }

    // Set SO_SNDBUF write buffer size
    int sendBufferSize = PGProperty.SEND_BUFFER_SIZE.getInt(info);
    if (sendBufferSize > -1) {
      if (sendBufferSize > 0) {
        newStream.getSocket().setSendBufferSize(sendBufferSize);
      } else {
        LOGGER.log(Level.WARNING, "Ignore invalid value for sendBufferSize: {0}", sendBufferSize);
      }
    }

    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.log(Level.FINE, "Receive Buffer Size is {0}", newStream.getSocket().getReceiveBufferSize());
      LOGGER.log(Level.FINE, "Send Buffer Size is {0}", newStream.getSocket().getSendBufferSize());
    }

    newStream = enableGSSEncrypted(newStream, gssEncMode, hostSpec.getHost(), user, info, connectTimeout);

    // if we have a security context then gss negotiation succeeded. Do not attempt SSL negotiation
    if (!newStream.isGssEncrypted()) {
      // Construct and send an ssl startup packet if requested.
      newStream = enableSSL(newStream, sslMode, info, connectTimeout);
    }

    // Make sure to set network timeout again, in case the stream changed due to GSS or SSL
    if (socketTimeout > 0) {
      newStream.setNetworkTimeout(socketTimeout * 1000);
    }

    List<String[]> paramList = getParametersForStartup(user, database, info);
    sendStartupPacket(newStream, paramList);

    // Do authentication (until AuthenticationOk).
    doAuthentication(newStream, hostSpec.getHost(), user, info);

    return newStream;
  }

  @Override
  public QueryExecutor openConnectionImpl(HostSpec[] hostSpecs, String user, String database,
      Properties info) throws SQLException {
    SslMode sslMode = SslMode.of(info);
    GSSEncMode gssEncMode = GSSEncMode.of(info);

    HostRequirement targetServerType;
    String targetServerTypeStr = castNonNull(PGProperty.TARGET_SERVER_TYPE.get(info));
    try {
      targetServerType = HostRequirement.getTargetServerType(targetServerTypeStr);
    } catch (IllegalArgumentException ex) {
      throw new PSQLException(
          GT.tr("Invalid targetServerType value: {0}", targetServerTypeStr),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT);
    }

    SocketFactory socketFactory = SocketFactoryFactory.getSocketFactory(info);

    HostChooser hostChooser =
        HostChooserFactory.createHostChooser(hostSpecs, targetServerType, info);
    Iterator<CandidateHost> hostIter = hostChooser.iterator();
    Map<HostSpec, HostStatus> knownStates = new HashMap<HostSpec, HostStatus>();
    while (hostIter.hasNext()) {
      CandidateHost candidateHost = hostIter.next();
      HostSpec hostSpec = candidateHost.hostSpec;
      LOGGER.log(Level.FINE, "Trying to establish a protocol version 3 connection to {0}", hostSpec);

      // Note: per-connect-attempt status map is used here instead of GlobalHostStatusTracker
      // for the case when "no good hosts" match (e.g. all the hosts are known as "connectfail")
      // In that case, the system tries to connect to each host in order, thus it should not look into
      // GlobalHostStatusTracker
      HostStatus knownStatus = knownStates.get(hostSpec);
      if (knownStatus != null && !candidateHost.targetServerType.allowConnectingTo(knownStatus)) {
        if (LOGGER.isLoggable(Level.FINER)) {
          LOGGER.log(Level.FINER, "Known status of host {0} is {1}, and required status was {2}. Will try next host",
                     new Object[]{hostSpec, knownStatus, candidateHost.targetServerType});
        }
        continue;
      }

      //
      // Establish a connection.
      //

      PGStream newStream = null;
      try {
        try {
          newStream = tryConnect(user, database, info, socketFactory, hostSpec, sslMode, gssEncMode);
        } catch (SQLException e) {
          if (sslMode == SslMode.PREFER
              && PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState().equals(e.getSQLState())) {
            // Try non-SSL connection to cover case like "non-ssl only db"
            // Note: PREFER allows loss of encryption, so no significant harm is made
            Throwable ex = null;
            try {
              newStream =
                  tryConnect(user, database, info, socketFactory, hostSpec, SslMode.DISABLE,gssEncMode);
              LOGGER.log(Level.FINE, "Downgraded to non-encrypted connection for host {0}",
                  hostSpec);
            } catch (SQLException ee) {
              ex = ee;
            } catch (IOException ee) {
              ex = ee; // Can't use multi-catch in Java 6 :(
            }
            if (ex != null) {
              log(Level.FINE, "sslMode==PREFER, however non-SSL connection failed as well", ex);
              // non-SSL failed as well, so re-throw original exception
              // Add non-SSL exception as suppressed
              e.addSuppressed(ex);
              throw e;
            }
          } else if (sslMode == SslMode.ALLOW
              && PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState().equals(e.getSQLState())) {
            // Try using SSL
            Throwable ex = null;
            try {
              newStream =
                  tryConnect(user, database, info, socketFactory, hostSpec, SslMode.REQUIRE, gssEncMode);
              LOGGER.log(Level.FINE, "Upgraded to encrypted connection for host {0}",
                  hostSpec);
            } catch (SQLException ee) {
              ex = ee;
            } catch (IOException ee) {
              ex = ee; // Can't use multi-catch in Java 6 :(
            }
            if (ex != null) {
              log(Level.FINE, "sslMode==ALLOW, however SSL connection failed as well", ex);
              // non-SSL failed as well, so re-throw original exception
              // Add SSL exception as suppressed
              e.addSuppressed(ex);
              throw e;
            }

          } else {
            throw e;
          }
        }

        int cancelSignalTimeout = PGProperty.CANCEL_SIGNAL_TIMEOUT.getInt(info) * 1000;

        // CheckerFramework can't infer newStream is non-nullable
        castNonNull(newStream);
        // Do final startup.
        QueryExecutor queryExecutor = new QueryExecutorImpl(newStream, user, database,
            cancelSignalTimeout, info);

        // Check Primary or Secondary
        HostStatus hostStatus = HostStatus.ConnectOK;
        if (candidateHost.targetServerType != HostRequirement.any) {
          hostStatus = isPrimary(queryExecutor) ? HostStatus.Primary : HostStatus.Secondary;
        }
        GlobalHostStatusTracker.reportHostStatus(hostSpec, hostStatus);
        knownStates.put(hostSpec, hostStatus);
        if (!candidateHost.targetServerType.allowConnectingTo(hostStatus)) {
          queryExecutor.close();
          continue;
        }

        runInitialQueries(queryExecutor, info);

        // And we're done.
        return queryExecutor;
      } catch (ConnectException cex) {
        // Added by Peter Mount <peter@retep.org.uk>
        // ConnectException is thrown when the connection cannot be made.
        // we trap this an return a more meaningful message for the end user
        GlobalHostStatusTracker.reportHostStatus(hostSpec, HostStatus.ConnectFail);
        knownStates.put(hostSpec, HostStatus.ConnectFail);
        if (hostIter.hasNext()) {
          log(Level.FINE, "ConnectException occurred while connecting to {0}", cex, hostSpec);
          // still more addresses to try
          continue;
        }
        throw new PSQLException(GT.tr(
            "Connection to {0} refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections.",
            hostSpec), PSQLState.CONNECTION_UNABLE_TO_CONNECT, cex);
      } catch (IOException ioe) {
        closeStream(newStream);
        GlobalHostStatusTracker.reportHostStatus(hostSpec, HostStatus.ConnectFail);
        knownStates.put(hostSpec, HostStatus.ConnectFail);
        if (hostIter.hasNext()) {
          log(Level.FINE, "IOException occurred while connecting to {0}", ioe, hostSpec);
          // still more addresses to try
          continue;
        }
        throw new PSQLException(GT.tr("The connection attempt failed."),
            PSQLState.CONNECTION_UNABLE_TO_CONNECT, ioe);
      } catch (SQLException se) {
        closeStream(newStream);
        GlobalHostStatusTracker.reportHostStatus(hostSpec, HostStatus.ConnectFail);
        knownStates.put(hostSpec, HostStatus.ConnectFail);
        if (hostIter.hasNext()) {
          log(Level.FINE, "SQLException occurred while connecting to {0}", se, hostSpec);
          // still more addresses to try
          continue;
        }
        throw se;
      }
    }
    throw new PSQLException(GT
        .tr("Could not find a server with specified targetServerType: {0}", targetServerType),
        PSQLState.CONNECTION_UNABLE_TO_CONNECT);
  }

  private List<String[]> getParametersForStartup(String user, String database, Properties info) {
    List<String[]> paramList = new ArrayList<String[]>();
    paramList.add(new String[]{"user", user});
    paramList.add(new String[]{"database", database});
    paramList.add(new String[]{"client_encoding", "UTF8"});
    paramList.add(new String[]{"DateStyle", "ISO"});
    paramList.add(new String[]{"TimeZone", createPostgresTimeZone()});

    Version assumeVersion = ServerVersion.from(PGProperty.ASSUME_MIN_SERVER_VERSION.get(info));

    if (assumeVersion.getVersionNum() >= ServerVersion.v9_0.getVersionNum()) {
      // User is explicitly telling us this is a 9.0+ server so set properties here:
      paramList.add(new String[]{"extra_float_digits", "3"});
      String appName = PGProperty.APPLICATION_NAME.get(info);
      if (appName != null) {
        paramList.add(new String[]{"application_name", appName});
      }
    } else {
      // User has not explicitly told us that this is a 9.0+ server so stick to old default:
      paramList.add(new String[]{"extra_float_digits", "2"});
    }

    String replication = PGProperty.REPLICATION.get(info);
    if (replication != null && assumeVersion.getVersionNum() >= ServerVersion.v9_4.getVersionNum()) {
      paramList.add(new String[]{"replication", replication});
    }

    String currentSchema = PGProperty.CURRENT_SCHEMA.get(info);
    if (currentSchema != null) {
      paramList.add(new String[]{"search_path", currentSchema});
    }

    String options = PGProperty.OPTIONS.get(info);
    if (options != null) {
      paramList.add(new String[]{"options", options});
    }

    return paramList;
  }

  private static void log(Level level, String msg, Throwable thrown, Object... params) {
    if (!LOGGER.isLoggable(level)) {
      return;
    }
    LogRecord rec = new LogRecord(level, msg);
    // Set the loggerName of the LogRecord with the current logger
    rec.setLoggerName(LOGGER.getName());
    rec.setParameters(params);
    rec.setThrown(thrown);
    LOGGER.log(rec);
  }

  /**
   * Convert Java time zone to postgres time zone. All others stay the same except that GMT+nn
   * changes to GMT-nn and vise versa.
   *
   * @return The current JVM time zone in postgresql format.
   */
  private static String createPostgresTimeZone() {
    String tz = TimeZone.getDefault().getID();
    if (tz.length() <= 3 || !tz.startsWith("GMT")) {
      return tz;
    }
    char sign = tz.charAt(3);
    String start;
    switch (sign) {
      case '+':
        start = "GMT-";
        break;
      case '-':
        start = "GMT+";
        break;
      default:
        // unknown type
        return tz;
    }

    return start + tz.substring(4);
  }

  private boolean credentialCacheExists() {
    try {
      @SuppressWarnings({"nullness"})
      sun.security.krb5.Credentials credentials =
          sun.security.krb5.Credentials.acquireTGTFromCache(null, null);
      return credentials != null;
    } catch ( Exception ex ) {
      return false;
    }
  }

  private PGStream enableGSSEncrypted(PGStream pgStream, GSSEncMode gssEncMode, String host, String user, Properties info,
                                    int connectTimeout)
      throws IOException, PSQLException {

    if ( gssEncMode == GSSEncMode.DISABLE ) {
      return pgStream;
    }

    if (gssEncMode == GSSEncMode.ALLOW ) {
      // start with plain text and let the server request it
      return pgStream;
    }

    // If there is not credential cache there is little point in attempting this
    if (!credentialCacheExists()) {
      if ( gssEncMode == GSSEncMode.REQUIRE ) {
        throw new PSQLException("GSSAPI encryption required but was impossible (possibly no credential cache)", PSQLState.CONNECTION_REJECTED);
      } else {
        return pgStream;
      }
    }

    // attempt to acquire a GSS encrypted connection
    String password = PGProperty.PASSWORD.get(info);
    LOGGER.log(Level.FINEST, " FE=> GSSENCRequest");

    // Send GSSEncryption request packet
    pgStream.sendInteger4(8);
    pgStream.sendInteger2(1234);
    pgStream.sendInteger2(5680);
    pgStream.flush();
    // Now get the response from the backend, one of N, E, S.
    int beresp = pgStream.receiveChar();
    switch (beresp) {
      case 'E':
        LOGGER.log(Level.FINEST, " <=BE GSSEncrypted Error");

        // Server doesn't even know about the SSL handshake protocol
        if (gssEncMode.requireEncryption()) {
          throw new PSQLException(GT.tr("The server does not support GSS Encoding."),
              PSQLState.CONNECTION_REJECTED);
        }

        // We have to reconnect to continue.
        pgStream.close();
        return new PGStream(pgStream.getSocketFactory(), pgStream.getHostSpec(), connectTimeout);

      case 'N':
        LOGGER.log(Level.FINEST, " <=BE GSSEncrypted Refused");

        // Server does not support gss encryption
        if (gssEncMode.requireEncryption()) {
          throw new PSQLException(GT.tr("The server does not support GSS Encryption."),
              PSQLState.CONNECTION_REJECTED);
        }

        return pgStream;

      case 'G':
        LOGGER.log(Level.FINEST, " <=BE GSSEncryptedOk");
        try {
          org.postgresql.gss.MakeGSS.authenticate(true, pgStream, host, user, password,
              PGProperty.JAAS_APPLICATION_NAME.get(info),
              PGProperty.KERBEROS_SERVER_NAME.get(info), false, // TODO: fix this
              PGProperty.JAAS_LOGIN.getBoolean(info),
              PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(info));
          return pgStream;
        } catch (PSQLException ex) {
          // allow the connection to proceed
          if ( gssEncMode == GSSEncMode.PREFER) {
            // we have to reconnect to continue
            return new PGStream(pgStream, connectTimeout);
          }
        }

      default:
        throw new PSQLException(GT.tr("An error occurred while setting up the GSS Encoded connection."),
            PSQLState.PROTOCOL_VIOLATION);
    }
  }

  private PGStream enableSSL(PGStream pgStream, SslMode sslMode, Properties info,
      int connectTimeout)
      throws IOException, PSQLException {
    if (sslMode == SslMode.DISABLE) {
      return pgStream;
    }
    if (sslMode == SslMode.ALLOW) {
      // Allow ==> start with plaintext, use encryption if required by server
      return pgStream;
    }

    LOGGER.log(Level.FINEST, " FE=> SSLRequest");

    // Send SSL request packet
    pgStream.sendInteger4(8);
    pgStream.sendInteger2(1234);
    pgStream.sendInteger2(5679);
    pgStream.flush();

    // Now get the response from the backend, one of N, E, S.
    int beresp = pgStream.receiveChar();
    switch (beresp) {
      case 'E':
        LOGGER.log(Level.FINEST, " <=BE SSLError");

        // Server doesn't even know about the SSL handshake protocol
        if (sslMode.requireEncryption()) {
          throw new PSQLException(GT.tr("The server does not support SSL."),
              PSQLState.CONNECTION_REJECTED);
        }

        // We have to reconnect to continue.
        return new PGStream(pgStream, connectTimeout);

      case 'N':
        LOGGER.log(Level.FINEST, " <=BE SSLRefused");

        // Server does not support ssl
        if (sslMode.requireEncryption()) {
          throw new PSQLException(GT.tr("The server does not support SSL."),
              PSQLState.CONNECTION_REJECTED);
        }

        return pgStream;

      case 'S':
        LOGGER.log(Level.FINEST, " <=BE SSLOk");

        // Server supports ssl
        org.postgresql.ssl.MakeSSL.convert(pgStream, info);
        return pgStream;

      default:
        throw new PSQLException(GT.tr("An error occurred while setting up the SSL connection."),
            PSQLState.PROTOCOL_VIOLATION);
    }
  }

  private void sendStartupPacket(PGStream pgStream, List<String[]> params)
      throws IOException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      StringBuilder details = new StringBuilder();
      for (int i = 0; i < params.size(); ++i) {
        if (i != 0) {
          details.append(", ");
        }
        details.append(params.get(i)[0]);
        details.append("=");
        details.append(params.get(i)[1]);
      }
      LOGGER.log(Level.FINEST, " FE=> StartupPacket({0})", details);
    }

    // Precalculate message length and encode params.
    int length = 4 + 4;
    byte[][] encodedParams = new byte[params.size() * 2][];
    for (int i = 0; i < params.size(); ++i) {
      encodedParams[i * 2] = params.get(i)[0].getBytes(StandardCharsets.UTF_8);
      encodedParams[i * 2 + 1] = params.get(i)[1].getBytes(StandardCharsets.UTF_8);
      length += encodedParams[i * 2].length + 1 + encodedParams[i * 2 + 1].length + 1;
    }

    length += 1; // Terminating \0

    // Send the startup message.
    pgStream.sendInteger4(length);
    pgStream.sendInteger2(3); // protocol major
    pgStream.sendInteger2(0); // protocol minor
    for (byte[] encodedParam : encodedParams) {
      pgStream.send(encodedParam);
      pgStream.sendChar(0);
    }

    pgStream.sendChar(0);
    pgStream.flush();
  }

  private void doAuthentication(PGStream pgStream, String host, String user, Properties info) throws IOException, SQLException {
    // Now get the response from the backend, either an error message
    // or an authentication request

    String password = PGProperty.PASSWORD.get(info);

    /* SSPI negotiation state, if used */
    ISSPIClient sspiClient = null;

    /* SCRAM authentication state, if used */
    org.postgresql.jre7.sasl.ScramAuthenticator scramAuthenticator = null;

    try {
      authloop: while (true) {
        int beresp = pgStream.receiveChar();

        switch (beresp) {
          case 'E':
            // An error occurred, so pass the error message to the
            // user.
            //
            // The most common one to be thrown here is:
            // "User authentication failed"
            //
            int elen = pgStream.receiveInteger4();

            ServerErrorMessage errorMsg =
                new ServerErrorMessage(pgStream.receiveErrorString(elen - 4));
            LOGGER.log(Level.FINEST, " <=BE ErrorMessage({0})", errorMsg);
            throw new PSQLException(errorMsg, PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(info));

          case 'R':
            // Authentication request.
            // Get the message length
            int msgLen = pgStream.receiveInteger4();

            // Get the type of request
            int areq = pgStream.receiveInteger4();

            // Process the request.
            switch (areq) {
              case AUTH_REQ_MD5: {
                byte[] md5Salt = pgStream.receive(4);
                if (LOGGER.isLoggable(Level.FINEST)) {
                  LOGGER.log(Level.FINEST, " <=BE AuthenticationReqMD5(salt={0})", Utils.toHexString(md5Salt));
                }

                if (password == null) {
                  throw new PSQLException(
                      GT.tr(
                          "The server requested password-based authentication, but no password was provided."),
                      PSQLState.CONNECTION_REJECTED);
                }

                byte[] digest =
                    MD5Digest.encode(user.getBytes(StandardCharsets.UTF_8), password.getBytes(StandardCharsets.UTF_8), md5Salt);

                if (LOGGER.isLoggable(Level.FINEST)) {
                  LOGGER.log(Level.FINEST, " FE=> Password(md5digest={0})", new String(digest, StandardCharsets.US_ASCII));
                }

                pgStream.sendChar('p');
                pgStream.sendInteger4(4 + digest.length + 1);
                pgStream.send(digest);
                pgStream.sendChar(0);
                pgStream.flush();

                break;
              }

              case AUTH_REQ_PASSWORD: {
                LOGGER.log(Level.FINEST, "<=BE AuthenticationReqPassword");
                LOGGER.log(Level.FINEST, " FE=> Password(password=<not shown>)");

                if (password == null) {
                  throw new PSQLException(
                      GT.tr(
                          "The server requested password-based authentication, but no password was provided."),
                      PSQLState.CONNECTION_REJECTED);
                }

                byte[] encodedPassword = password.getBytes(StandardCharsets.UTF_8);

                pgStream.sendChar('p');
                pgStream.sendInteger4(4 + encodedPassword.length + 1);
                pgStream.send(encodedPassword);
                pgStream.sendChar(0);
                pgStream.flush();

                break;
              }

              case AUTH_REQ_GSS:
              case AUTH_REQ_SSPI:
                /*
                 * Use GSSAPI if requested on all platforms, via JSSE.
                 *
                 * For SSPI auth requests, if we're on Windows attempt native SSPI authentication if
                 * available, and if not disabled by setting a kerberosServerName. On other
                 * platforms, attempt JSSE GSSAPI negotiation with the SSPI server.
                 *
                 * Note that this is slightly different to libpq, which uses SSPI for GSSAPI where
                 * supported. We prefer to use the existing Java JSSE Kerberos support rather than
                 * going to native (via JNA) calls where possible, so that JSSE system properties
                 * etc continue to work normally.
                 *
                 * Note that while SSPI is often Kerberos-based there's no guarantee it will be; it
                 * may be NTLM or anything else. If the client responds to an SSPI request via
                 * GSSAPI and the other end isn't using Kerberos for SSPI then authentication will
                 * fail.
                 */
                final String gsslib = PGProperty.GSS_LIB.get(info);
                final boolean usespnego = PGProperty.USE_SPNEGO.getBoolean(info);

                boolean useSSPI = false;

                /*
                 * Use SSPI if we're in auto mode on windows and have a request for SSPI auth, or if
                 * it's forced. Otherwise use gssapi. If the user has specified a Kerberos server
                 * name we'll always use JSSE GSSAPI.
                 */
                if ("gssapi".equals(gsslib)) {
                  LOGGER.log(Level.FINE, "Using JSSE GSSAPI, param gsslib=gssapi");
                } else if (areq == AUTH_REQ_GSS && !"sspi".equals(gsslib)) {
                  LOGGER.log(Level.FINE,
                      "Using JSSE GSSAPI, gssapi requested by server and gsslib=sspi not forced");
                } else {
                  /* Determine if SSPI is supported by the client */
                  sspiClient = createSSPI(pgStream, PGProperty.SSPI_SERVICE_CLASS.get(info),
                      /* Use negotiation for SSPI, or if explicitly requested for GSS */
                      areq == AUTH_REQ_SSPI || (areq == AUTH_REQ_GSS && usespnego));

                  useSSPI = sspiClient.isSSPISupported();
                  LOGGER.log(Level.FINE, "SSPI support detected: {0}", useSSPI);

                  if (!useSSPI) {
                    /* No need to dispose() if no SSPI used */
                    sspiClient = null;

                    if ("sspi".equals(gsslib)) {
                      throw new PSQLException(
                          "SSPI forced with gsslib=sspi, but SSPI not available; set loglevel=2 for details",
                          PSQLState.CONNECTION_UNABLE_TO_CONNECT);
                    }
                  }

                  if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.log(Level.FINE, "Using SSPI: {0}, gsslib={1} and SSPI support detected", new Object[]{useSSPI, gsslib});
                  }
                }

                if (useSSPI) {
                  /* SSPI requested and detected as available */
                  castNonNull(sspiClient).startSSPI();
                } else {
                  /* Use JGSS's GSSAPI for this request */
                  org.postgresql.gss.MakeGSS.authenticate(false, pgStream, host, user, password,
                      PGProperty.JAAS_APPLICATION_NAME.get(info),
                      PGProperty.KERBEROS_SERVER_NAME.get(info), usespnego,
                      PGProperty.JAAS_LOGIN.getBoolean(info),
                      PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(info));
                }
                break;

              case AUTH_REQ_GSS_CONTINUE:
                /*
                 * Only called for SSPI, as GSS is handled by an inner loop in MakeGSS.
                 */
                castNonNull(sspiClient).continueSSPI(msgLen - 8);
                break;

              case AUTH_REQ_SASL:
                LOGGER.log(Level.FINEST, " <=BE AuthenticationSASL");

                scramAuthenticator = new org.postgresql.jre7.sasl.ScramAuthenticator(user, castNonNull(password), pgStream);
                scramAuthenticator.processServerMechanismsAndInit();
                scramAuthenticator.sendScramClientFirstMessage();
                // This works as follows:
                // 1. When tests is run from IDE, it is assumed SCRAM library is on the classpath
                // 2. In regular build for Java < 8 this `if` is deactivated and the code always throws
                if (false) {
                  throw new PSQLException(GT.tr(
                          "SCRAM authentication is not supported by this driver. You need JDK >= 8 and pgjdbc >= 42.2.0 (not \".jre\" versions)",
                          areq), PSQLState.CONNECTION_REJECTED);
                }
                break;

              case AUTH_REQ_SASL_CONTINUE:
                castNonNull(scramAuthenticator).processServerFirstMessage(msgLen - 4 - 4);
                break;

              case AUTH_REQ_SASL_FINAL:
                castNonNull(scramAuthenticator).verifyServerSignature(msgLen - 4 - 4);
                break;

              case AUTH_REQ_OK:
                /* Cleanup after successful authentication */
                LOGGER.log(Level.FINEST, " <=BE AuthenticationOk");
                break authloop; // We're done.

              default:
                LOGGER.log(Level.FINEST, " <=BE AuthenticationReq (unsupported type {0})", areq);
                throw new PSQLException(GT.tr(
                    "The authentication type {0} is not supported. Check that you have configured the pg_hba.conf file to include the client''s IP address or subnet, and that it is using an authentication scheme supported by the driver.",
                    areq), PSQLState.CONNECTION_REJECTED);
            }

            break;

          default:
            throw new PSQLException(GT.tr("Protocol error.  Session setup failed."),
                PSQLState.PROTOCOL_VIOLATION);
        }
      }
    } finally {
      /* Cleanup after successful or failed authentication attempts */
      if (sspiClient != null) {
        try {
          sspiClient.dispose();
        } catch (RuntimeException ex) {
          LOGGER.log(Level.FINE, "Unexpected error during SSPI context disposal", ex);
        }

      }
    }

  }

  private void runInitialQueries(QueryExecutor queryExecutor, Properties info)
      throws SQLException {
    String assumeMinServerVersion = PGProperty.ASSUME_MIN_SERVER_VERSION.get(info);
    if (Utils.parseServerVersionStr(assumeMinServerVersion) >= ServerVersion.v9_0.getVersionNum()) {
      // We already sent the parameter values in the StartupMessage so skip this
      return;
    }

    final int dbVersion = queryExecutor.getServerVersionNum();

    if (dbVersion >= ServerVersion.v9_0.getVersionNum()) {
      SetupQueryRunner.run(queryExecutor, "SET extra_float_digits = 3", false);
    }

    String appName = PGProperty.APPLICATION_NAME.get(info);
    if (appName != null && dbVersion >= ServerVersion.v9_0.getVersionNum()) {
      StringBuilder sql = new StringBuilder();
      sql.append("SET application_name = '");
      Utils.escapeLiteral(sql, appName, queryExecutor.getStandardConformingStrings());
      sql.append("'");
      SetupQueryRunner.run(queryExecutor, sql.toString(), false);
    }

  }

  private boolean isPrimary(QueryExecutor queryExecutor) throws SQLException, IOException {
    Tuple results = SetupQueryRunner.run(queryExecutor, "show transaction_read_only", true);
    Tuple nonNullResults = castNonNull(results);
    String value = queryExecutor.getEncoding().decode(castNonNull(nonNullResults.get(0)));
    return value.equalsIgnoreCase("off");
  }
}
