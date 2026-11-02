/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.jdbc.SslMode;
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
 * Writes the client side of the OAUTHBEARER SASL exchange (RFC 7628) onto a {@link PGStream}.
 * One instance serves one authentication attempt, and the constructor enforces the encryption
 * requirement, so a caller holding an instance may send the token.
 */
final class OAuthAuthenticator {
  private static final Logger LOGGER = Logger.getLogger(OAuthAuthenticator.class.getName());
  static final String SASL_MECHANISM = "OAUTHBEARER";

  /** SOH byte (0x01) used as field separator in RFC 7628 SASL messages. */
  private static final byte SOH = 0x01;

  /**
   * OAuth bearer token characters per the grammar of RFC 6750 §2.1.
   */
  private static final Pattern TOKEN_REGEX = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

  private final PGStream pgStream;
  private boolean serverError;

  OAuthAuthenticator(PGStream pgStream, SslMode sslMode, boolean allowInsecure)
      throws PSQLException {
    checkTransportSecurity(pgStream, sslMode, allowInsecure);
    this.pgStream = pgStream;
  }

  /**
   * Checks that connection to the server is secure, or that the user has
   * opted out of that requirement.
   */
  private static void checkTransportSecurity(PGStream pgStream, SslMode sslMode,
      boolean allowInsecure) throws PSQLException {

    if (pgStream.isGssEncrypted()) {
      return;
    }

    boolean encrypted = pgStream.getSocket() instanceof SSLSocket;
    if (encrypted && sslMode.verifyCertificate()) {
      return;
    }

    if (!allowInsecure) {
      throw new PSQLException(
          encrypted
              ? GT.tr("OAUTHBEARER authentication requires the server certificate to be verified "
                  + "(RFC 7628 §3), and sslmode={0} does not verify it. Use sslmode=verify-ca or "
                  + "sslmode=verify-full, or set oauthAllowInsecureConnection=true to override "
                  + "(testing only).", sslMode.value)
              : GT.tr("OAUTHBEARER authentication requires a connection to a verified server "
                  + "(TLS with sslmode=verify-ca or higher, or GSS encryption) (RFC 7628 §3). "
                  + "Set oauthAllowInsecureConnection=true to override (testing only)."),
          PSQLState.CONNECTION_REJECTED);
    }

    if (encrypted) {
      LOGGER.log(Level.WARNING,
          "oauthAllowInsecureConnection=true: OAUTHBEARER is being used over a TLS connection "
              + "whose server certificate is not verified (sslmode={0}), so the bearer token may "
              + "be disclosed to an impostor. RFC 7628 §3 requires a secure channel. "
              + "Do not use this configuration in production.", sslMode.value);
    } else {
      LOGGER.log(Level.WARNING,
          "oauthAllowInsecureConnection=true: OAUTHBEARER is being used over an unencrypted "
              + "connection, so the bearer token is sent in the clear. RFC 7628 §3 requires a "
              + "secure channel. Do not use this configuration in production.");
    }
  }

  /**
   * Sends SASLInitialResponse with the bearer token.
   */
  void handleAuthenticationSASL(char @Nullable [] token) throws IOException, PSQLException {
    checkServerError();

    LOGGER.log(Level.FINEST, " FE=> SASLInitialResponse(OAUTHBEARER, auth=Bearer <token>)");
    sendSaslInitialResponse(buildTokenMessage(token));
  }

  /**
   * Reads the AuthenticationSASLContinue payload, which RFC 7628 §3.2.2 sends only to convey an
   * OAuth error result, and answers with the dummy response that completes the error message
   * sequence (§3.2.3).
   */
  void handleAuthenticationSASLContinue(int length) throws IOException, PSQLException {
    checkServerError();

    String json = pgStream.receiveString(length);
    LOGGER.log(Level.WARNING, "Server rejected OAuth bearer token: {0}", json);
    serverError = true;

    sendDummyResponse();
  }

  /**
   * Handles successful authentication.
   * Just checks that the authentication has not failed on previous steps.
   */
  void handleAuthenticationOk() throws PSQLException {
    checkServerError();
  }

  /**
   * Checks authentication state.
   */
  private void checkServerError() throws PSQLException {
    if (serverError) {
      throw new PSQLException(
          GT.tr("Server sent additional OAuth data after error."),
          PSQLState.PROTOCOL_VIOLATION);
    }
  }

  /**
   * Sends a SASLResponse with a single 0x01 byte, required by RFC 7628 §3.2.3 to complete the
   * error message sequence so the server can finish the exchange.
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
    try {
      pgStream.sendChar(PgMessageType.SASL_INITIAL_RESPONSE);
      pgStream.sendInteger4(Integer.BYTES + bodyLength);
      pgStream.send(mechanismBytes);
      pgStream.sendChar(0);
      pgStream.sendInteger4(clientMessage.length);
      pgStream.send(clientMessage);
    } finally {
      /* Cleanup client message as it contains token, even when a send fails. */
      Arrays.fill(clientMessage, (byte) 0);
    }

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
