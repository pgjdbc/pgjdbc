/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;

/**
 * Handles the OAUTHBEARER SASL mechanism (RFC 7628) wire protocol.
 */
public final class OAuthAuthenticator {
  private static final Logger LOGGER = Logger.getLogger(OAuthAuthenticator.class.getName());
  static final String SASL_MECHANISM = "OAUTHBEARER";

  /** SOH byte (0x01) used as field separator in RFC 7628 SASL messages. */
  private static final byte SOH = 0x01;

  /**
   * OAuth bearer token characters per the grammar of RFC 6750 §2.1.
   */
  private static final Pattern TOKEN_REGEX = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

  private final PGStream pgStream;

  public OAuthAuthenticator(PGStream pgStream, boolean allowUnencrypted) throws PSQLException {
    checkTransportEncryption(pgStream, allowUnencrypted);
    this.pgStream = pgStream;
  }

  /**
   * Checks that the connection is encrypted by TLS or GSS, or that the user has opted out of the
   * encryption requirement.
   */
  private static void checkTransportEncryption(PGStream pgStream, boolean allowUnencrypted)
      throws PSQLException {
    if (pgStream.getSocket() instanceof SSLSocket || pgStream.isGssEncrypted()) {
      return;
    }
    if (!allowUnencrypted) {
      throw new PSQLException(
          GT.tr("OAUTHBEARER authentication requires an encrypted connection (TLS or GSS) "
              + "(RFC 7628 §4). Set oauthAllowUnencryptedConnection=true to override (testing only)."),
          PSQLState.CONNECTION_REJECTED);
    }

    LOGGER.log(Level.WARNING,
        "OAUTHBEARER is being used over an unencrypted connection. This violates RFC 7628 §4. "
            + "DO NOT USE THIS CONFIGURATION IN PRODUCTION!");
  }

  /**
   * Sends SASLInitialResponse with the bearer token.
   */
  void handleAuthenticationSASL(char @Nullable [] token) throws IOException, PSQLException {
    LOGGER.log(Level.FINEST, " FE=> SASLInitialResponse(OAUTHBEARER, auth=Bearer <token>)");
    sendSaslInitialResponse(buildTokenMessage(token));
  }

  /**
   * Parses the AuthenticationSASLContinue message payload, which per RFC 7628 is sent only to convey
   * an OAuth error result to the client during SASL exchange.
   */
  void handleAuthenticationSASLContinue(int length) throws IOException, PSQLException {
    String json = pgStream.receiveString(length);
    LOGGER.log(Level.WARNING, "Server rejected OAuth bearer token: {0}", json);

    sendDummyResponse();
  }

  /**
   * Sends a SASLResponse with a single 0x01 byte, required by RFC 7628 section 3.2
   * to complete the client's side of the failed discovery exchange.
   */
  void sendDummyResponse() throws IOException {
    LOGGER.log(Level.FINEST, " FE=> SASLResponse(0x01)");
    pgStream.sendChar(PgMessageType.SASL_RESPONSE);
    pgStream.sendInteger4(Integer.BYTES + 1);
    pgStream.sendChar(1);
    pgStream.flush();
  }

  private void sendSaslInitialResponse(byte[] clientMessage) throws IOException {
    byte[] mechanismBytes = SASL_MECHANISM.getBytes(StandardCharsets.UTF_8);
    int bodyLength = mechanismBytes.length + 1 + Integer.BYTES + clientMessage.length;
    pgStream.sendChar(PgMessageType.SASL_INITIAL_RESPONSE);
    pgStream.sendInteger4(Integer.BYTES + bodyLength);
    pgStream.send(mechanismBytes);
    pgStream.sendChar(0);
    pgStream.sendInteger4(clientMessage.length);
    pgStream.send(clientMessage);

    /* Cleanup client message as it contains token. */
    Arrays.fill(clientMessage, (byte) 0);

    pgStream.flush();
  }

  /**
   * Builds the RFC 7628 SASL client message.
   */
  static byte[] buildTokenMessage(char @Nullable [] token) throws PSQLException {
    int tokenLen = token != null ? token.length : 0;
    // "n,,\x01auth=" = 9 bytes;
    // "Bearer " + token = 7 + tokenLen;
    // "\x01\x01" = 2 bytes
    int msgLen = 9 + (tokenLen > 0 ? 7 + tokenLen : 0) + 2;

    ByteBuffer msg = ByteBuffer.allocate(msgLen);
    msg.put("n,,".getBytes(StandardCharsets.UTF_8));
    msg.put(SOH);
    msg.put("auth=".getBytes(StandardCharsets.UTF_8));

    if (token != null && tokenLen > 0) {
      if (!TOKEN_REGEX.matcher(CharBuffer.wrap(token)).matches()) {
        throw new PSQLException(
            GT.tr("Invalid OAuth bearer token format. See RFC 6750 §2.1 for details."),
            PSQLState.CONNECTION_REJECTED);
      }

      msg.put("Bearer ".getBytes(StandardCharsets.UTF_8));
      for (char c : token) {
        msg.put((byte) c);
      }
    }
    msg.put(SOH).put(SOH);

    return msg.array();
  }
}
