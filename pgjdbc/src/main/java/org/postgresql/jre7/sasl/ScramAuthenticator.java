/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jre7.sasl;

import static org.postgresql.util.internal.Nullness.castNonNull;

import org.postgresql.core.PGStream;
import org.postgresql.exception.PgSqlState;
import org.postgresql.util.GT;

import com.ongres.scram.client.ScramClient;
import com.ongres.scram.client.ScramSession;
import com.ongres.scram.common.exception.ScramException;
import com.ongres.scram.common.exception.ScramInvalidServerSignatureException;
import com.ongres.scram.common.exception.ScramParseException;
import com.ongres.scram.common.exception.ScramServerErrorException;
import com.ongres.scram.common.stringprep.StringPreparations;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ScramAuthenticator {
  private static final Logger LOGGER = Logger.getLogger(ScramAuthenticator.class.getName());

  private final String user;
  private final String password;
  private final PGStream pgStream;
  private @Nullable ScramClient scramClient;
  private @Nullable ScramSession scramSession;
  private @Nullable ScramSession.ClientFinalProcessor clientFinalProcessor;

  private interface BodySender {
    void sendBody(PGStream pgStream) throws IOException;
  }

  private void sendAuthenticationMessage(int bodyLength, BodySender bodySender)
      throws IOException {
    pgStream.sendChar('p');
    pgStream.sendInteger4(Integer.SIZE / Byte.SIZE + bodyLength);
    bodySender.sendBody(pgStream);
    pgStream.flush();
  }

  public ScramAuthenticator(String user, String password, PGStream pgStream) {
    this.user = user;
    this.password = password;
    this.pgStream = pgStream;
  }

  public void processServerMechanismsAndInit() throws IOException, SQLNonTransientConnectionException {
    List<String> mechanisms = new ArrayList<>();
    do {
      mechanisms.add(pgStream.receiveString());
    } while (pgStream.peekChar() != 0);
    int c = pgStream.receiveChar();
    assert c == 0;
    if (mechanisms.isEmpty()) {
      throw new SQLNonTransientConnectionException(
          GT.tr("No SCRAM mechanism(s) advertised by the server"),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION
      );
    }

    ScramClient scramClient;
    try {
      scramClient = ScramClient
          .channelBinding(ScramClient.ChannelBinding.NO)
          .stringPreparation(StringPreparations.NO_PREPARATION)
          .selectMechanismBasedOnServerAdvertised(mechanisms.toArray(new String[]{}))
          .setup();
    } catch (IllegalArgumentException e) {
      throw new SQLNonTransientConnectionException(
          GT.tr("Invalid or unsupported by client SCRAM mechanisms"),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION, e
      );
    }
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " Using SCRAM mechanism {0}", scramClient.getScramMechanism().getName());
    }

    this.scramClient = scramClient;
    scramSession =
        scramClient.scramSession("*");   // Real username is ignored by server, uses startup one
  }

  public void sendScramClientFirstMessage() throws IOException {
    ScramSession scramSession = this.scramSession;
    String clientFirstMessage = castNonNull(scramSession).clientFirstMessage();
    LOGGER.log(Level.FINEST, " FE=> SASLInitialResponse( {0} )", clientFirstMessage);

    ScramClient scramClient = this.scramClient;
    String scramMechanismName = castNonNull(scramClient).getScramMechanism().getName();
    final byte[] scramMechanismNameBytes = scramMechanismName.getBytes(StandardCharsets.UTF_8);
    final byte[] clientFirstMessageBytes = clientFirstMessage.getBytes(StandardCharsets.UTF_8);
    sendAuthenticationMessage(
        (scramMechanismNameBytes.length + 1) + 4 + clientFirstMessageBytes.length,
        new BodySender() {
          @Override
          public void sendBody(PGStream pgStream) throws IOException {
            pgStream.send(scramMechanismNameBytes);
            pgStream.sendChar(0); // List terminated in '\0'
            pgStream.sendInteger4(clientFirstMessageBytes.length);
            pgStream.send(clientFirstMessageBytes);
          }
        }
    );
  }

  public void processServerFirstMessage(int length) throws IOException, SQLNonTransientConnectionException {
    String serverFirstMessage = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLContinue( {0} )", serverFirstMessage);

    ScramSession scramSession = this.scramSession;
    if (scramSession == null) {
      throw new SQLNonTransientConnectionException(
          GT.tr("SCRAM session does not exist"),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION
      );
    }

    ScramSession.ServerFirstProcessor serverFirstProcessor;
    try {
      serverFirstProcessor = scramSession.receiveServerFirstMessage(serverFirstMessage);
    } catch (ScramException e) {
      throw new SQLNonTransientConnectionException(
          GT.tr("Invalid server-first-message: {0}", serverFirstMessage),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION,
          e
      );
    }
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST,
                 " <=BE AuthenticationSASLContinue(salt={0}, iterations={1})",
                 new Object[] { serverFirstProcessor.getSalt(), serverFirstProcessor.getIteration() }
                 );
    }

    clientFinalProcessor = serverFirstProcessor.clientFinalProcessor(password);

    String clientFinalMessage = clientFinalProcessor.clientFinalMessage();
    LOGGER.log(Level.FINEST, " FE=> SASLResponse( {0} )", clientFinalMessage);

    final byte[] clientFinalMessageBytes = clientFinalMessage.getBytes(StandardCharsets.UTF_8);
    sendAuthenticationMessage(
        clientFinalMessageBytes.length,
        new BodySender() {
          @Override
          public void sendBody(PGStream pgStream) throws IOException {
            pgStream.send(clientFinalMessageBytes);
          }
        }
    );
  }

  public void verifyServerSignature(int length) throws IOException, SQLNonTransientConnectionException {
    String serverFinalMessage = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLFinal( {0} )", serverFinalMessage);

    ScramSession.ClientFinalProcessor clientFinalProcessor = this.clientFinalProcessor;
    if (clientFinalProcessor == null) {
      throw new SQLNonTransientConnectionException(
          GT.tr("SCRAM client final processor does not exist"),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION
      );
    }
    try {
      clientFinalProcessor.receiveServerFinalMessage(serverFinalMessage);
    } catch (ScramParseException e) {
      throw new SQLNonTransientConnectionException(
          GT.tr("Invalid server-final-message: {0}", serverFinalMessage),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION,
          e
      );
    } catch (ScramServerErrorException e) {
      throw new SQLNonTransientConnectionException(
          GT.tr("SCRAM authentication failed, server returned error: {0}",
              e.getError().getErrorMessage()),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION,
          e
      );
    } catch (ScramInvalidServerSignatureException e) {
      throw new SQLNonTransientConnectionException(
          GT.tr("Invalid server SCRAM signature"),
          PgSqlState.SQLCLIENT_UNABLE_TO_ESTABLISH_SQLCONNECTION,
          e
      );
    }
  }
}
