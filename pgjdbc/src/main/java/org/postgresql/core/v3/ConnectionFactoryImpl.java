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
import org.postgresql.gss.MakeGSS;
import org.postgresql.hostchooser.CandidateHost;
import org.postgresql.hostchooser.CustomHostChooserManager;
import org.postgresql.hostchooser.GlobalHostStatusTracker;
import org.postgresql.hostchooser.HostChooser;
import org.postgresql.hostchooser.HostChooserFactory;
import org.postgresql.hostchooser.HostRequirement;
import org.postgresql.hostchooser.HostStatus;
import org.postgresql.jdbc.GSSEncMode;
import org.postgresql.jdbc.SslMode;
import org.postgresql.jdbc.SslNegotiation;
import org.postgresql.plugin.AuthenticationRequestType;
import org.postgresql.ssl.MakeSSL;
import org.postgresql.sspi.ISSPIClient;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.MD5Digest;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.postgresql.util.internal.Nullness;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
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

  private static class StartupParam {
    private final String key;
    private final String value;

    StartupParam(String key, String value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public String toString() {
      return this.key + "=" + this.value;
    }

    public byte[] getEncodedKey() {
      return this.key.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] getEncodedValue() {
      return this.value.getBytes(StandardCharsets.UTF_8);
    }
  }

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

  private static final String IN_HOT_STANDBY = "in_hot_standby";

  private ISSPIClient createSSPI(PGStream pgStream,
      @Nullable String spnServiceClass,
      boolean enableNegotiate) {
    try {
      @SuppressWarnings("unchecked")
      Class<ISSPIClient> c = (Class<ISSPIClient>) Class.forName("org.postgresql.sspi.SSPIClient");
      return c.getDeclaredConstructor(PGStream.class, String.class, boolean.class)
          .newInstance(pgStream, spnServiceClass, enableNegotiate);
    } catch (Exception e) {
      // This caught quite a lot of exceptions, but until Java 7 there is no ReflectiveOperationException
      throw new IllegalStateException("Unable to load org.postgresql.sspi.SSPIClient."
          + " Please check that SSPIClient is included in your pgjdbc distribution.", e);
    }
  }

  private PGStream tryConnect(Properties info, SocketFactory socketFactory, HostSpec hostSpec,
      SslMode sslMode, GSSEncMode gssEncMode)
      throws SQLException, IOException {
    int connectTimeout = PGProperty.CONNECT_TIMEOUT.getInt(info) * 1000;
    String user = PGProperty.USER.getOrDefault(info);
    String database = PGProperty.PG_DBNAME.getOrDefault(info);
    SslNegotiation sslNegotiation = SslNegotiation.of(Nullness.castNonNull(PGProperty.SSL_NEGOTIATION.getOrDefault(info)));

    if (user == null) {
      throw new PSQLException(GT.tr("User cannot be null"), PSQLState.INVALID_NAME);
    }
    if (database == null) {
      throw new PSQLException(GT.tr("Database cannot be null"), PSQLState.INVALID_NAME);
    }

    int maxSendBufferSize = PGProperty.MAX_SEND_BUFFER_SIZE.getInt(info);
    PGStream newStream = new PGStream(socketFactory, hostSpec, connectTimeout, maxSendBufferSize);
    try {
      // Set the socket timeout if the "socketTimeout" property has been set.
      int socketTimeout = PGProperty.SOCKET_TIMEOUT.getInt(info);
      if (socketTimeout > 0) {
        newStream.setNetworkTimeout(socketTimeout * 1000);
      }

      String maxResultBuffer = PGProperty.MAX_RESULT_BUFFER.getOrDefault(info);
      newStream.setMaxResultBuffer(maxResultBuffer);

      // Enable TCP keep-alive probe if required.
      boolean requireTCPKeepAlive = PGProperty.TCP_KEEP_ALIVE.getBoolean(info);
      newStream.getSocket().setKeepAlive(requireTCPKeepAlive);

      // Enable TCP no delay if required
      boolean requireTCPNoDelay = PGProperty.TCP_NO_DELAY.getBoolean(info);
      newStream.getSocket().setTcpNoDelay(requireTCPNoDelay);

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
          LOGGER.log(Level.WARNING, "Ignore invalid value for receiveBufferSize: {0}",
              receiveBufferSize);
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
        LOGGER.log(Level.FINE, "Receive Buffer Size is {0}",
            newStream.getSocket().getReceiveBufferSize());
        LOGGER.log(Level.FINE, "Send Buffer Size is {0}",
            newStream.getSocket().getSendBufferSize());
      }

      if (sslNegotiation != SslNegotiation.DIRECT) {
        newStream =
            enableGSSEncrypted(newStream, gssEncMode, hostSpec.getHost(), info, connectTimeout);
      }
      // if we have a security context then gss negotiation succeeded. Do not attempt SSL
      // negotiation
      if (!newStream.isGssEncrypted()) {
        // Construct and send an SSL startup packet if requested.
        newStream = enableSSL(newStream, sslMode, info, connectTimeout);
      }

      // Make sure to set network timeout again, in case the stream changed due to GSS or SSL
      if (socketTimeout > 0) {
        newStream.setNetworkTimeout(socketTimeout * 1000);
      }

      List<StartupParam> paramList = getParametersForStartup(user, database, info);
      sendStartupPacket(newStream, paramList);

      // Do authentication (until AuthenticationOk).
      doAuthentication(newStream, hostSpec.getHost(), user, info);

      return newStream;
    } catch (Exception e) {
      closeStream(newStream);
      throw e;
    }
  }

  @Override
  public QueryExecutor openConnectionImpl(HostSpec[] hostSpecs, Properties info, String url) throws SQLException {
    SslMode sslMode = SslMode.of(info);
    GSSEncMode gssEncMode = GSSEncMode.of(info);

    HostRequirement targetServerType;
    String targetServerTypeStr = castNonNull(PGProperty.TARGET_SERVER_TYPE.getOrDefault(info));
    try {
      targetServerType = HostRequirement.getTargetServerType(targetServerTypeStr);
    } catch (IllegalArgumentException ex) {
      throw new PSQLException(
          GT.tr("Invalid targetServerType value: {0}", targetServerTypeStr),
          PSQLState.CONNECTION_UNABLE_TO_CONNECT);
    }

    SocketFactory socketFactory = SocketFactoryFactory.getSocketFactory(info);

    String customImplClass = info.getProperty(PGProperty.HOST_CHOOSER_IMPL.getName());
    CustomHostChooserManager.HostChooserUrlProperty key = null;
    if (customImplClass != null) {
      key = new CustomHostChooserManager.HostChooserUrlProperty(url, info, customImplClass);
    }
    HostChooser hostChooser;
    hostChooser = HostChooserFactory.createHostChooser(key, hostSpecs, targetServerType, info);
    Iterator<CandidateHost> hostIter = hostChooser.iterator();
    Map<HostSpec, HostStatus> knownStates = new HashMap<>();
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
          newStream = tryConnect(info, socketFactory, hostSpec, sslMode, gssEncMode);
        } catch (SQLException e) {
          if (sslMode == SslMode.PREFER
              && PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState().equals(e.getSQLState())) {
            // Try non-SSL connection to cover case like "non-ssl only db"
            // Note: PREFER allows loss of encryption, so no significant harm is made
            Throwable ex = null;
            try {
              newStream =
                  tryConnect(info, socketFactory, hostSpec, SslMode.DISABLE, gssEncMode);
              LOGGER.log(Level.FINE, "Downgraded to non-encrypted connection for host {0}",
                  hostSpec);
            } catch (SQLException | IOException ee) {
              ex = ee;
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
                  tryConnect(info, socketFactory, hostSpec, SslMode.REQUIRE, gssEncMode);
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
        QueryExecutor queryExecutor = new QueryExecutorImpl(newStream, cancelSignalTimeout, info, hostChooser);

        // Check Primary or Secondary
        HostStatus hostStatus = HostStatus.ConnectOK;
        if (candidateHost.targetServerType != HostRequirement.any) {
          hostStatus = isPrimary(queryExecutor) ? HostStatus.Primary : HostStatus.Secondary;
        }
        GlobalHostStatusTracker.reportHostStatus(hostSpec, hostStatus);
        knownStates.put(hostSpec, hostStatus);
        if (!candidateHost.targetServerType.allowConnectingTo(hostStatus)) {
          hostChooser.registerFailure(hostSpec.getHost(), null);
          queryExecutor.close();
          continue;
        }

        runInitialQueries(queryExecutor, info);

        // And we're done.
        hostChooser.registerSuccess(hostSpec.getHost());
        return queryExecutor;
      } catch (ConnectException cex) {
        // Added by Peter Mount <peter@retep.org.uk>
        // ConnectException is thrown when the connection cannot be made.
        // we trap this an return a more meaningful message for the end user
        GlobalHostStatusTracker.reportHostStatus(hostSpec, HostStatus.ConnectFail);
        knownStates.put(hostSpec, HostStatus.ConnectFail);
        hostChooser.registerFailure(hostSpec.getHost(), cex);
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
        hostChooser.registerFailure(hostSpec.getHost(), ioe);
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
        hostChooser.registerFailure(hostSpec.getHost(), se);
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

  private List<StartupParam> getParametersForStartup(String user, String database, Properties info) {
    List<StartupParam> paramList = new ArrayList<>();
    paramList.add(new StartupParam("user", user));
    paramList.add(new StartupParam("database", database));
    paramList.add(new StartupParam("client_encoding", "UTF8"));
    paramList.add(new StartupParam("DateStyle", "ISO"));
    paramList.add(new StartupParam("TimeZone", createPostgresTimeZone()));

    Version assumeVersion = ServerVersion.from(PGProperty.ASSUME_MIN_SERVER_VERSION.getOrDefault(info));

    if (assumeVersion.getVersionNum() >= ServerVersion.v9_0.getVersionNum()) {
      // User is explicitly telling us this is a 9.0+ server so set properties here:
      paramList.add(new StartupParam("extra_float_digits", "3"));
      String appName = PGProperty.APPLICATION_NAME.getOrDefault(info);
      if (appName != null) {
        paramList.add(new StartupParam("application_name", appName));
      }
    } else {
      // User has not explicitly told us that this is a 9.0+ server so stick to old default:
      paramList.add(new StartupParam("extra_float_digits", "2"));
    }

    String replication = PGProperty.REPLICATION.getOrDefault(info);
    if (replication != null && assumeVersion.getVersionNum() >= ServerVersion.v9_4.getVersionNum()) {
      paramList.add(new StartupParam("replication", replication));
    }

    String currentSchema = PGProperty.CURRENT_SCHEMA.getOrDefault(info);
    if (currentSchema != null) {
      paramList.add(new StartupParam("search_path", currentSchema));
    }

    String options = PGProperty.OPTIONS.getOrDefault(info);
    if (options != null) {
      paramList.add(new StartupParam("options", options));
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
   * If you provide GMT+/-nn postgres uses POSIX rules which has a positive sign for west of Greenwich
   * JAVA uses ISO rules which the positive sign is east of Greenwich
   * To make matters more interesting postgres will always report in ISO
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

  private PGStream enableGSSEncrypted(PGStream pgStream, GSSEncMode gssEncMode, String host, Properties info,
                                    int connectTimeout)
      throws IOException, PSQLException {

    if ( gssEncMode == GSSEncMode.DISABLE ) {
      return pgStream;
    }

    if (gssEncMode == GSSEncMode.ALLOW ) {
      // start with plain text and let the server request it
      return pgStream;
    }

    /*
     at this point gssEncMode is either PREFER or REQUIRE
     libpq looks to see if there is a ticket in the cache before asking
     the server if it supports encrypted GSS connections or not.
     since the user has specifically asked or either prefer or require we can
     assume they want it.
     */
    /*
    let's see if the server will allow a GSS encrypted connection
     */
    String user = PGProperty.USER.getOrDefault(info);
    if (user == null) {
      throw new PSQLException("GSSAPI encryption required but was impossible user is null", PSQLState.CONNECTION_REJECTED);
    }

    // attempt to acquire a GSS encrypted connection
    LOGGER.log(Level.FINEST, " FE=> GSSENCRequest");

    int gssTimeout = PGProperty.SSL_RESPONSE_TIMEOUT.getInt(info);
    int currentTimeout = pgStream.getNetworkTimeout();

    // if the current timeout is less than sslTimeout then
    // use the smaller timeout. We could do something tricky
    // here to not set it in that case but this is pretty readable
    if (currentTimeout > 0 && currentTimeout < gssTimeout) {
      gssTimeout = currentTimeout;
    }

    pgStream.setNetworkTimeout(gssTimeout);

    // Send GSSEncryption request packet
    pgStream.sendInteger4(8);
    pgStream.sendInteger2(1234);
    pgStream.sendInteger2(5680);
    pgStream.flush();
    // Now get the response from the backend, one of N, E, S.
    int beresp = pgStream.receiveChar();
    pgStream.setNetworkTimeout(currentTimeout);
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
        int maxSendBufferSize = PGProperty.MAX_SEND_BUFFER_SIZE.getInt(info);
        return new PGStream(pgStream.getSocketFactory(), pgStream.getHostSpec(), connectTimeout,
            maxSendBufferSize);

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
          AuthenticationPluginManager.withPassword(AuthenticationRequestType.GSS, info, password -> {
            MakeGSS.authenticate(true, pgStream, host, user, password,
                PGProperty.JAAS_APPLICATION_NAME.getOrDefault(info),
                PGProperty.KERBEROS_SERVER_NAME.getOrDefault(info), false, // TODO: fix this
                PGProperty.JAAS_LOGIN.getBoolean(info),
                PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(info));
            return void.class;
          });
          return pgStream;
        } catch (PSQLException ex) {
          // allow the connection to proceed
          if (gssEncMode == GSSEncMode.PREFER) {
            // we have to reconnect to continue
            return new PGStream(pgStream, connectTimeout);
          }
        }
        // fallthrough

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
    SslNegotiation sslNegotiation = SslNegotiation.of(Nullness.castNonNull(PGProperty.SSL_NEGOTIATION.getOrDefault(info)));

    LOGGER.log(Level.FINEST, () -> String.format(" FE=> SSLRequest %s", sslNegotiation.value()));

    int sslTimeout = PGProperty.SSL_RESPONSE_TIMEOUT.getInt(info);
    int currentTimeout = pgStream.getNetworkTimeout();

    // if the current timeout is less than sslTimeout then
    // use the smaller timeout. We could do something tricky
    // here to not set it in that case but this is pretty readable
    if (currentTimeout > 0 && currentTimeout < sslTimeout) {
      sslTimeout = currentTimeout;
    }

    pgStream.setNetworkTimeout(sslTimeout);
    if (sslNegotiation == SslNegotiation.DIRECT) {
      MakeSSL.convert(pgStream, info);
      return pgStream;
    }
    // Send SSL request packet
    pgStream.sendInteger4(8);
    pgStream.sendInteger2(1234);
    pgStream.sendInteger2(5679);
    pgStream.flush();

    // Now get the response from the backend, one of N, E, S.
    int beresp = pgStream.receiveChar();
    pgStream.setNetworkTimeout(currentTimeout);

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
        MakeSSL.convert(pgStream, info);
        return pgStream;

      default:
        throw new PSQLException(GT.tr("An error occurred while setting up the SSL connection."),
            PSQLState.PROTOCOL_VIOLATION);
    }
  }

  private void sendStartupPacket(PGStream pgStream, List<StartupParam> params)
      throws IOException {
    if (LOGGER.isLoggable(Level.FINEST)) {
      StringBuilder details = new StringBuilder();
      for (int i = 0; i < params.size(); i++) {
        if (i != 0) {
          details.append(", ");
        }
        details.append(params.get(i).toString());
      }
      LOGGER.log(Level.FINEST, " FE=> StartupPacket({0})", details);
    }

    // Precalculate message length and encode params.
    int length = 4 + 4;
    byte[][] encodedParams = new byte[params.size() * 2][];
    for (int i = 0; i < params.size(); i++) {
      encodedParams[i * 2] = params.get(i).getEncodedKey();
      encodedParams[i * 2 + 1] = params.get(i).getEncodedValue();
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

    /* SSPI negotiation state, if used */
    ISSPIClient sspiClient = null;

    /* SCRAM authentication state, if used */
    ScramAuthenticator scramAuthenticator = null;

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

                byte[] digest = AuthenticationPluginManager.withEncodedPassword(
                    AuthenticationRequestType.MD5_PASSWORD, info,
                    encodedPassword -> MD5Digest.encode(user.getBytes(StandardCharsets.UTF_8),
                        encodedPassword, md5Salt)
                );

                if (LOGGER.isLoggable(Level.FINEST)) {
                  LOGGER.log(Level.FINEST, " FE=> Password(md5digest={0})", new String(digest, StandardCharsets.US_ASCII));
                }

                try {
                  pgStream.sendChar('p');
                  pgStream.sendInteger4(4 + digest.length + 1);
                  pgStream.send(digest);
                } finally {
                  Arrays.fill(digest, (byte) 0);
                }
                pgStream.sendChar(0);
                pgStream.flush();

                break;
              }

              case AUTH_REQ_PASSWORD: {
                LOGGER.log(Level.FINEST, "<=BE AuthenticationReqPassword");
                LOGGER.log(Level.FINEST, " FE=> Password(password=<not shown>)");

                AuthenticationPluginManager.withEncodedPassword(AuthenticationRequestType.CLEARTEXT_PASSWORD, info, encodedPassword -> {
                  pgStream.sendChar('p');
                  pgStream.sendInteger4(4 + encodedPassword.length + 1);
                  pgStream.send(encodedPassword);
                  return void.class;
                });
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
                final String gsslib = PGProperty.GSS_LIB.getOrDefault(info);
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
                  sspiClient = createSSPI(pgStream, PGProperty.SSPI_SERVICE_CLASS.getOrDefault(info),
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
                  AuthenticationPluginManager.withPassword(AuthenticationRequestType.GSS, info, password -> {
                    MakeGSS.authenticate(false, pgStream, host, user, password,
                        PGProperty.JAAS_APPLICATION_NAME.getOrDefault(info),
                        PGProperty.KERBEROS_SERVER_NAME.getOrDefault(info), usespnego,
                        PGProperty.JAAS_LOGIN.getBoolean(info),
                        PGProperty.LOG_SERVER_ERROR_DETAIL.getBoolean(info));
                    return void.class;
                  });
                }
                break;

              case AUTH_REQ_GSS_CONTINUE:
                /*
                 * Only called for SSPI, as GSS is handled by an inner loop in MakeGSS.
                 */
                castNonNull(sspiClient).continueSSPI(msgLen - 8);
                break;

              case AUTH_REQ_SASL:
                scramAuthenticator = AuthenticationPluginManager.withPassword(AuthenticationRequestType.SASL, info, password -> {
                  if (password == null) {
                    throw new PSQLException(
                        GT.tr(
                            "The server requested SCRAM-based authentication, but no password was provided."),
                        PSQLState.CONNECTION_REJECTED);
                  }
                  if (password.length == 0) {
                    throw new PSQLException(
                        GT.tr(
                            "The server requested SCRAM-based authentication, but the password is an empty string."),
                        PSQLState.CONNECTION_REJECTED);
                  }
                  return new ScramAuthenticator(password, pgStream, info);
                });
                scramAuthenticator.handleAuthenticationSASL();
                break;

              case AUTH_REQ_SASL_CONTINUE:
                castNonNull(scramAuthenticator).handleAuthenticationSASLContinue(msgLen - 4 - 4);
                break;

              case AUTH_REQ_SASL_FINAL:
                castNonNull(scramAuthenticator).handleAuthenticationSASLFinal(msgLen - 4 - 4);
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
    String assumeMinServerVersion = PGProperty.ASSUME_MIN_SERVER_VERSION.getOrDefault(info);
    if (Utils.parseServerVersionStr(assumeMinServerVersion) >= ServerVersion.v9_0.getVersionNum()) {
      // We already sent the parameter values in the StartupMessage so skip this
      return;
    }

    final int dbVersion = queryExecutor.getServerVersionNum();

    if (PGProperty.GROUP_STARTUP_PARAMETERS.getBoolean(info) && dbVersion >= ServerVersion.v9_0.getVersionNum()) {
      SetupQueryRunner.run(queryExecutor, "BEGIN", false);
    }

    if (dbVersion >= ServerVersion.v9_0.getVersionNum()) {
      SetupQueryRunner.run(queryExecutor, "SET extra_float_digits = 3", false);
    }

    String appName = PGProperty.APPLICATION_NAME.getOrDefault(info);
    if (appName != null && dbVersion >= ServerVersion.v9_0.getVersionNum()) {
      StringBuilder sql = new StringBuilder();
      sql.append("SET application_name = '");
      Utils.escapeLiteral(sql, appName, queryExecutor.getStandardConformingStrings());
      sql.append("'");
      SetupQueryRunner.run(queryExecutor, sql.toString(), false);
    }

    if (PGProperty.GROUP_STARTUP_PARAMETERS.getBoolean(info) && dbVersion >= ServerVersion.v9_0.getVersionNum()) {
      SetupQueryRunner.run(queryExecutor, "COMMIT", false);
    }
  }

  /**
   * Since PG14 there is GUC_REPORT ParamStatus {@code in_hot_standby} which is set to "on"
   * when the server is in archive recovery or standby mode. In driver's lingo such server is called
   * {@link org.postgresql.hostchooser.HostRequirement#secondary}.
   * Previously {@code transaction_read_only} was used as a workable substitute.
   * However {@code transaction_read_only} could have been manually overridden on the primary server
   * by database user leading to a false positives: ie server is effectively read-only but
   * technically is "primary" (not in a recovery/standby mode).
   *
   * <p>This method checks whether {@code in_hot_standby} GUC was reported by the server
   * during initial connection:</p>
   *
   * <ul>
   * <li>{@code in_hot_standby} was reported and the value was "on" then the server is a replica
   * and database is read-only by definition, false is returned.</li>
   * <li>{@code in_hot_standby} was reported and the value was "off"
   * then the server is indeed primary but database may be in
   * read-only mode nevertheless. We proceed to conservatively {@code show transaction_read_only}
   * since users may not be expecting a readonly connection for {@code targetServerType=primary}</li>
   * <li>If {@code in_hot_standby} has not been reported we fallback to pre v14 behavior.</li>
   * </ul>
   *
   * <p>Do not confuse {@code hot_standby} and {@code in_hot_standby} ParamStatuses</p>
   *
   * @see <a href="https://www.postgresql.org/docs/current/protocol-flow.html#PROTOCOL-ASYNC">GUC_REPORT documentation</a>
   * @see <a href="https://www.postgresql.org/docs/current/hot-standby.html">Hot standby documentation</a>
   * @see <a href="https://www.postgresql.org/message-id/flat/1700970.cRWpxnom9y@hammer.magicstack.net">in_hot_standby patch thread v10</a>
   * @see <a href="https://www.postgresql.org/message-id/flat/CAF3%2BxM%2B8-ztOkaV9gHiJ3wfgENTq97QcjXQt%2BrbFQ6F7oNzt9A%40mail.gmail.com">in_hot_standby patch thread v14</a>
   *
   */
  private boolean isPrimary(QueryExecutor queryExecutor) throws SQLException, IOException {
    String inHotStandby = queryExecutor.getParameterStatus(IN_HOT_STANDBY);
    if ("on".equalsIgnoreCase(inHotStandby)) {
      return false;
    }
    Tuple results = SetupQueryRunner.run(queryExecutor, "show transaction_read_only", true);
    Tuple nonNullResults = castNonNull(results);
    String queriedTransactionReadonly = queryExecutor.getEncoding().decode(castNonNull(nonNullResults.get(0)));
    return "off".equalsIgnoreCase(queriedTransactionReadonly);
  }
}
