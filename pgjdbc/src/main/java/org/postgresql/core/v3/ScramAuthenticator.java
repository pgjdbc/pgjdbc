/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import com.ongres.scram.client.ScramClient;
import com.ongres.scram.common.ClientFinalMessage;
import com.ongres.scram.common.ClientFirstMessage;
import com.ongres.scram.common.StringPreparation;
import com.ongres.scram.common.exception.ScramException;
import com.ongres.scram.common.util.TlsServerEndpoint;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

final class ScramAuthenticator {
  private static final Logger LOGGER = Logger.getLogger(ScramAuthenticator.class.getName());

  private final PGStream pgStream;
  private final ScramClient scramClient;

  ScramAuthenticator(char[] password, PGStream pgStream, ChannelBinding channelBinding) throws PSQLException {
    this.pgStream = pgStream;
    this.scramClient = initializeScramClient(password, pgStream, channelBinding);
  }

  private static ScramClient initializeScramClient(char[] password, PGStream stream, ChannelBinding channelBinding) throws PSQLException {
    try {
      LOGGER.log(Level.FINEST, "channelBinding( {0} )", channelBinding);
      final byte[] cbindData = getChannelBindingData(stream, channelBinding);
      final List<String> advertisedMechanisms = advertisedMechanisms(stream, channelBinding);
      ScramClient client = ScramClient.builder()
          .advertisedMechanisms(advertisedMechanisms)
          .username("*") // username is ignored by server, startup message is used instead
          .password(password)
          .channelBinding(TlsServerEndpoint.TLS_SERVER_END_POINT, cbindData)
          .stringPreparation(StringPreparation.POSTGRESQL_PREPARATION)
          .build();

      LOGGER.log(Level.FINEST, () -> " Using SCRAM mechanism: "
          + client.getScramMechanism().getName());
      return client;
    } catch (IllegalArgumentException | IOException e) {
      throw new PSQLException(
          GT.tr("Invalid SCRAM client initialization", e),
          PSQLState.CONNECTION_REJECTED);
    }
  }

  private static List<String> advertisedMechanisms(PGStream stream, ChannelBinding channelBinding)
      throws PSQLException, IOException {
    List<String> mechanisms = new ArrayList<>();
    do {
      mechanisms.add(stream.receiveString());
    } while (stream.peekChar() != 0);
    int c = stream.receiveChar();
    assert c == 0;
    if (mechanisms.isEmpty()) {
      throw new PSQLException(
          GT.tr("Received AuthenticationSASL message with 0 mechanisms!"),
          PSQLState.CONNECTION_REJECTED);
    }
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASL( {0} )", mechanisms);
    if (channelBinding == ChannelBinding.REQUIRE
        && !mechanisms.stream().anyMatch(m -> m.endsWith("-PLUS"))) {
      throw new PSQLException(
          GT.tr("Channel Binding is required, but server did not offer an "
              + "authentication method that supports channel binding"),
          PSQLState.CONNECTION_REJECTED);
    }
    return mechanisms;
  }

  private static byte[] getChannelBindingData(PGStream stream, ChannelBinding channelBinding)
      throws PSQLException {
    if (channelBinding == ChannelBinding.DISABLE) {
      return new byte[0];
    }

    Socket socket = stream.getSocket();
    if (socket instanceof SSLSocket) {
      SSLSession session = ((SSLSocket) socket).getSession();
      try {
        Certificate[] certificates = session.getPeerCertificates();
        if (certificates != null && certificates.length > 0) {
          Certificate peerCert = certificates[0]; // First certificate is the peer's certificate
          if (peerCert instanceof X509Certificate) {
            X509Certificate cert = (X509Certificate) peerCert;
            return TlsServerEndpoint.getChannelBindingData(cert);
          }
        }
      } catch (CertificateEncodingException | SSLPeerUnverifiedException e) {
        LOGGER.log(Level.FINEST, "Error extracting channel binding data", e);
        if (channelBinding == ChannelBinding.REQUIRE) {
          throw new PSQLException(
              GT.tr("Channel Binding is required, but could not extract "
                  + "channel binding data from SSL session"),
              PSQLState.CONNECTION_REJECTED);
        }
      }
    } else if (channelBinding == ChannelBinding.REQUIRE) {
      throw new PSQLException(
          GT.tr("Channel Binding is required, but SSL is not in use"),
          PSQLState.CONNECTION_REJECTED);
    }
    return new byte[0];
  }

  void handleAuthenticationSASL() throws IOException {
    ClientFirstMessage clientFirstMessage = scramClient.clientFirstMessage();
    LOGGER.log(Level.FINEST, " FE=> SASLInitialResponse( {0} )", clientFirstMessage);
    String scramMechanismName = scramClient.getScramMechanism().getName();
    final byte[] scramMechanismNameBytes = scramMechanismName.getBytes(StandardCharsets.UTF_8);
    final byte[] clientFirstMessageBytes =
        clientFirstMessage.toString().getBytes(StandardCharsets.UTF_8);
    sendAuthenticationMessage(
        (scramMechanismNameBytes.length + 1) + 4 + clientFirstMessageBytes.length,
        pgStream -> {
          pgStream.send(scramMechanismNameBytes);
          pgStream.sendChar(0); // List terminated in '\0'
          pgStream.sendInteger4(clientFirstMessageBytes.length);
          pgStream.send(clientFirstMessageBytes);
        });
  }

  void handleAuthenticationSASLContinue(int length) throws IOException, PSQLException {
    String receivedServerFirstMessage = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLContinue( {0} )", receivedServerFirstMessage);
    try {
      scramClient.serverFirstMessage(receivedServerFirstMessage);
    } catch (ScramException | IllegalStateException | IllegalArgumentException e) {
      throw new PSQLException(
          GT.tr("SCRAM authentication failed: {0}", e.getMessage()),
          PSQLState.CONNECTION_REJECTED,
          e);
    }

    ClientFinalMessage clientFinalMessage = scramClient.clientFinalMessage();
    LOGGER.log(Level.FINEST, " FE=> SASLResponse( {0} )", clientFinalMessage);
    final byte[] clientFinalMessageBytes =
        clientFinalMessage.toString().getBytes(StandardCharsets.UTF_8);
    sendAuthenticationMessage(
        clientFinalMessageBytes.length,
        pgStream -> pgStream.send(clientFinalMessageBytes)
    );
  }

  void handleAuthenticationSASLFinal(int length) throws IOException, PSQLException {
    String serverFinalMessage = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLFinal( {0} )", serverFinalMessage);
    try {
      scramClient.serverFinalMessage(serverFinalMessage);
    } catch (ScramException | IllegalStateException | IllegalArgumentException e) {
      throw new PSQLException(
          GT.tr("SCRAM authentication failed: {0}", e.getMessage()),
          PSQLState.CONNECTION_REJECTED,
          e);
    }
  }

  private interface BodySender {
    void sendBody(PGStream pgStream) throws IOException;
  }

  private void sendAuthenticationMessage(int bodyLength, BodySender bodySender)
      throws IOException {
    pgStream.sendChar(PgMessageType.SASL_INITIAL_RESPONSE);
    pgStream.sendInteger4(Integer.BYTES + bodyLength);
    bodySender.sendBody(pgStream);
    pgStream.flush();
  }
}
