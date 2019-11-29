/*
 * Copyright (c) 2017, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jre7.sasl;

import org.postgresql.core.PGStream;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import com.ongres.scram.client.ScramClient;
import com.ongres.scram.client.ScramSession;
import com.ongres.scram.common.exception.ScramException;
import com.ongres.scram.common.exception.ScramInvalidServerSignatureException;
import com.ongres.scram.common.exception.ScramParseException;
import com.ongres.scram.common.exception.ScramServerErrorException;
import com.ongres.scram.common.stringprep.StringPreparations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ScramAuthenticator {
  private static final Logger LOGGER = Logger.getLogger(ScramAuthenticator.class.getName());

  private final String user;
  private final String password;
  private final PGStream pgStream;
  private ScramClient scramClient;
  private ScramSession scramSession;
  private ScramSession.ServerFirstProcessor serverFirstProcessor;
  private ScramSession.ClientFinalProcessor clientFinalProcessor;

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

  public void processServerMechanismsAndInit() throws IOException, PSQLException {
    List<String> mechanisms = new ArrayList<>();
    do {
      mechanisms.add(pgStream.receiveString());
    } while (pgStream.peekChar() != 0);
    int c = pgStream.receiveChar();
    assert c == 0;
    if (mechanisms.size() < 1) {
      throw new PSQLException(
          GT.tr("No SCRAM mechanism(s) advertised by the server"),
          PSQLState.CONNECTION_REJECTED
      );
    }

    try {
      scramClient = ScramClient
          .channelBinding(ScramClient.ChannelBinding.NO)
          .stringPreparation(StringPreparations.NO_PREPARATION)
          .selectMechanismBasedOnServerAdvertised(mechanisms.toArray(new String[]{}))
          .setup();
    } catch (IllegalArgumentException e) {
      throw new PSQLException(
          GT.tr("Invalid or unsupported by client SCRAM mechanisms", e),
          PSQLState.CONNECTION_REJECTED
      );
    }
    if (LOGGER.isLoggable(Level.FINEST)) {
      LOGGER.log(Level.FINEST, " Using SCRAM mechanism {0}", scramClient.getScramMechanism().getName());
    }

    scramSession =
        scramClient.scramSession("*");   // Real username is ignored by server, uses startup one
  }

  public void sendScramClientFirstMessage() throws IOException {
    String clientFirstMessage = scramSession.clientFirstMessage();
    LOGGER.log(Level.FINEST, " FE=> SASLInitialResponse( {0} )", clientFirstMessage);

    String scramMechanismName = scramClient.getScramMechanism().getName();
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

  public void processServerFirstMessage(int length) throws IOException, PSQLException {
    String serverFirstMessage = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLContinue( {0} )", serverFirstMessage);

    try {
      serverFirstProcessor = scramSession.receiveServerFirstMessage(serverFirstMessage);
    } catch (ScramException e) {
      throw new PSQLException(
          GT.tr("Invalid server-first-message: {0}", serverFirstMessage),
          PSQLState.CONNECTION_REJECTED,
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

  public void verifyServerSignature(int length) throws IOException, PSQLException {
    String serverFinalMessage = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLFinal( {0} )", serverFinalMessage);

    try {
      clientFinalProcessor.receiveServerFinalMessage(serverFinalMessage);
    } catch (ScramParseException e) {
      throw new PSQLException(
          GT.tr("Invalid server-final-message: {0}", serverFinalMessage),
          PSQLState.CONNECTION_REJECTED,
          e
      );
    } catch (ScramServerErrorException e) {
      throw new PSQLException(
          GT.tr("SCRAM authentication failed, server returned error: {0}",
              e.getError().getErrorMessage()),
          PSQLState.CONNECTION_REJECTED,
          e
      );
    } catch (ScramInvalidServerSignatureException e) {
      throw new PSQLException(
          GT.tr("Invalid server SCRAM signature"),
          PSQLState.CONNECTION_REJECTED,
          e
      );
    }
  }
}
