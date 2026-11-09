/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import org.postgresql.PGProperty;
import org.postgresql.core.PGStream;
import org.postgresql.core.PgMessageType;
import org.postgresql.jdbc.SslMode;
import org.postgresql.plugin.OauthTokenProvider;
import org.postgresql.plugin.OauthTokenRequest;
import org.postgresql.ssl.NonValidatingFactory;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.ObjectFactory;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocket;

/**
 * Runs the client side of the OAUTHBEARER SASL exchange (RFC 7628) over a {@link PGStream}.
 * One instance serves one authentication attempt, and the constructor enforces the encryption
 * requirement, so a caller holding an instance may send the token.
 */
final class OauthAuthenticator {
  private static final Logger LOGGER = Logger.getLogger(OauthAuthenticator.class.getName());
  static final String SASL_MECHANISM = "OAUTHBEARER";

  /** SOH byte (0x01) used as field separator in RFC 7628 SASL messages. */
  private static final byte SOH = 0x01;

  /**
   * OAuth bearer token characters per the grammar of RFC 6750 section 2.1.
   */
  private static final Pattern TOKEN_REGEX = Pattern.compile("[A-Za-z0-9._~+/-]+=*");

  /** Whether the WARNING for oauthAllowInsecureConnection over unverified TLS was logged. */
  private static final AtomicBoolean UNVERIFIED_TLS_LOGGED = new AtomicBoolean();

  /** Whether the WARNING for oauthAllowInsecureConnection without encryption was logged. */
  private static final AtomicBoolean UNENCRYPTED_LOGGED = new AtomicBoolean();

  private final PGStream pgStream;
  /** The OAuth error result the server sent in AuthenticationSASLContinue, if any. */
  private @Nullable String errorResult;

  OauthAuthenticator(PGStream pgStream, SslMode sslMode, @Nullable String sslFactory,
      boolean allowInsecure) throws PSQLException {
    checkTransportSecurity(pgStream, sslMode, sslFactory, allowInsecure);
    this.pgStream = pgStream;
  }

  /**
   * Checks that the connection is GSS-encrypted, or TLS-encrypted with a verified server
   * certificate. With {@code allowInsecure}, logs a warning instead of failing.
   */
  private static void checkTransportSecurity(PGStream pgStream, SslMode sslMode,
      @Nullable String sslFactory, boolean allowInsecure) throws PSQLException {

    if (pgStream.isGssEncrypted()) {
      return;
    }

    boolean encrypted = pgStream.getSocket() instanceof SSLSocket;
    // Don't consider the connection secure if using a non-validating factory, even if SSL is used.
    boolean nonValidatingFactory = NonValidatingFactory.class.getName().equals(sslFactory);
    if (encrypted && sslMode.verifyCertificate() && !nonValidatingFactory) {
      return;
    }

    if (!allowInsecure) {
      String message;
      if (!encrypted) {
        message = GT.tr("OAUTHBEARER sends the bearer token only over an encrypted connection to a "
            + "verified server, and this connection is not encrypted (sslmode={0}). Set "
            + "sslmode=verify-full or verify-ca, or use GSS encryption (RFC 7628 section 3, "
            + "RFC 6750 section 5.3).",
            sslMode.value);
      } else if (nonValidatingFactory && sslMode.verifyCertificate()) {
        message = GT.tr("OAUTHBEARER sends the bearer token only to a verified server, and "
            + "sslfactory={0} does not verify the server certificate. Remove sslfactory, or set "
            + "it to a factory that verifies the certificate (RFC 6750 section 5.3).",
            sslFactory);
      } else {
        message = GT.tr("OAUTHBEARER sends the bearer token only to a verified server, and "
            + "sslmode={0} does not verify the server certificate. Set sslmode=verify-full "
            + "or verify-ca (RFC 6750 section 5.3).", sslMode.value);
      }
      throw new PSQLException(message, PSQLState.CONNECTION_REJECTED);
    }

    if (encrypted) {
      String unverifiedBy = nonValidatingFactory && sslMode.verifyCertificate()
          ? "sslfactory=" + sslFactory
          : "sslmode=" + sslMode.value;
      LOGGER.log(levelFor(UNVERIFIED_TLS_LOGGED),
          "Sending the OAuth bearer token to {0} over TLS without verifying the server "
              + "certificate ({1}), because oauthAllowInsecureConnection=true",
          new Object[]{pgStream.getHostSpec(), unverifiedBy});
    } else {
      LOGGER.log(levelFor(UNENCRYPTED_LOGGED),
          "Sending the OAuth bearer token to {0} over an unencrypted connection (sslmode={1}), "
              + "because oauthAllowInsecureConnection=true",
          new Object[]{pgStream.getHostSpec(), sslMode.value});
    }
  }

  /** WARNING the first time in this JVM, FINE afterwards. */
  private static Level levelFor(AtomicBoolean logged) {
    return logged.compareAndSet(false, true) ? Level.WARNING : Level.FINE;
  }

  /** Lets a test see the first-time WARNING again. */
  static void resetInsecureConnectionWarnings() {
    UNVERIFIED_TLS_LOGGED.set(false);
    UNENCRYPTED_LOGGED.set(false);
  }

  static boolean isOauthConfigured(Properties info) {
    String staticToken = PGProperty.OAUTH_TOKEN.getOrDefault(info);
    if (staticToken != null && !staticToken.isEmpty()) {
      return true;
    }
    String providerClass = PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.getOrDefault(info);
    if (providerClass != null && !providerClass.isEmpty()) {
      return true;
    }
    return false;
  }

  static char [] resolveOauthToken(HostSpec hostSpec, String user, Properties info)
      throws PSQLException {
    String tokenText = PGProperty.OAUTH_TOKEN.getOrDefault(info);
    if (tokenText != null && !tokenText.isEmpty()) {
      return tokenText.toCharArray();
    }

    String providerClassName = PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.getOrDefault(info);
    if (providerClassName == null || providerClassName.isEmpty()) {
      throw new IllegalStateException("resolveOauthToken called without oauthToken or "
          + "oauthTokenProviderClassName set");
    }

    OauthTokenProvider provider;
    try {
      provider = ObjectFactory.instantiate(OauthTokenProvider.class, providerClassName, info, false,
          null);
    } catch (Exception ex) {
      throw new PSQLException(
          GT.tr("Unable to load OAuth token provider {0}", providerClassName),
          PSQLState.INVALID_PARAMETER_VALUE, ex);
    }

    OauthTokenRequest request = OauthTokenRequest.builder()
        .host(hostSpec.getHost())
        .port(hostSpec.getPort())
        .user(user)
        .database(PGProperty.PG_DBNAME.getOrDefault(info))
        .build();

    char [] token;
    try {
      token = provider.getToken(request);
    } catch (PSQLException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new PSQLException(
          GT.tr("OAuth token provider {0} failed to return a token", providerClassName),
          PSQLState.CONNECTION_REJECTED, ex);
    }
    if (token == null || token.length == 0) {
      throw new PSQLException(
          GT.tr("OAuth token provider {0} returned a null or empty token", providerClassName),
          PSQLState.CONNECTION_REJECTED);
    }
    return token;
  }

  /**
   * Adds a hint to a missing password message when OAuth is configured. Managed services such as
   * RDS, Aurora, Azure and Cloud SQL take an IAM or Entra token as a password, so a user who sets
   * it as oauthToken gets a password request instead of OAUTHBEARER.
   */
  static String withOauthHint(Properties info, String message) {
    if (!isOauthConfigured(info)) {
      return message;
    }
    return message + " " + GT.tr("{0} and {1} serve only OAUTHBEARER, which was not used for this "
        + "connection. A cloud IAM token is a password: set it with {2} or {3}.",
        PGProperty.OAUTH_TOKEN.getName(), PGProperty.OAUTH_TOKEN_PROVIDER_CLASS_NAME.getName(),
        PGProperty.PASSWORD.getName(), PGProperty.AUTHENTICATION_PLUGIN_CLASS_NAME.getName());
  }

  /**
   * Sends SASLInitialResponse with the bearer token.
   *
   * @param token the bearer token, or null for the discovery form, which the driver does not
   *     send yet (see {@link #buildTokenMessage})
   */
  void handleAuthenticationSASL(char @Nullable [] token) throws IOException, PSQLException {
    checkServerError();

    LOGGER.log(Level.FINEST, " FE=> SASLInitialResponse(OAUTHBEARER, auth=Bearer <not shown>)");
    sendSaslInitialResponse(buildTokenMessage(token));
  }

  /**
   * Reads the AuthenticationSASLContinue payload, which the server sends only to convey an OAuth
   * error result (RFC 7628 section 3.2.2), and answers with the dummy response that completes the
   * error message sequence (RFC 7628 section 3.2.3).
   */
  void handleAuthenticationSASLContinue(int length) throws IOException, PSQLException {
    checkServerError();

    String json = pgStream.receiveString(length);
    LOGGER.log(Level.FINEST, " <=BE AuthenticationSASLContinue( {0} )", json);
    errorResult = json;

    sendDummyResponse();
  }

  /**
   * Returns the OAuth error result the server sent, as an exception to chain to the one built
   * from the ErrorResponse that follows it. The ErrorResponse says only that authentication
   * failed; the error result carries the {@code status}, {@code scope} and
   * {@code openid-configuration} that tell the caller why.
   *
   * @return the error result, or null if the server sent none
   */
  @Nullable PSQLException getErrorResult() {
    String json = errorResult;
    if (json == null) {
      return null;
    }
    // The text is server-controlled, so replace control characters such as line breaks.
    StringBuilder printable = new StringBuilder(json.length());
    for (int i = 0; i < json.length(); i++) {
      char c = json.charAt(i);
      printable.append(Character.isISOControl(c) ? ' ' : c);
    }
    return new PSQLException(
        GT.tr("The server rejected the OAuth bearer token with this error result: {0}",
            printable),
        PSQLState.INVALID_AUTHORIZATION_SPECIFICATION);
  }

  /**
   * Rejects an AuthenticationOk that follows an OAuth error result.
   */
  void handleAuthenticationOk() throws PSQLException {
    checkServerError();
  }

  /**
   * Throws if the server already sent an OAuth error result, after which only an ErrorResponse
   * is valid.
   */
  private void checkServerError() throws PSQLException {
    if (errorResult != null) {
      throw new PSQLException(
          GT.tr("The server continued the OAUTHBEARER exchange after it reported an error."),
          PSQLState.PROTOCOL_VIOLATION);
    }
  }

  /**
   * Sends a SASLResponse with a single 0x01 byte, required by RFC 7628 section 3.2.3 to complete the
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
   * Builds the initial client response of RFC 7628 section 3.1.
   *
   * <p>The driver always passes a token. The discovery form is kept for a later discovery
   * exchange and is reached only from tests.</p>
   *
   * @param token the bearer token, or null or empty for the discovery form
   *     {@code n,,\x01auth=\x01\x01}, which asks the server for its OAuth configuration.
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
            GT.tr("The OAuth bearer token contains a character that RFC 6750 section 2.1 does not "
                + "allow. Check {0}, where a + in a URL must be written as %2B, or the value the "
                + "OauthTokenProvider returned.", PGProperty.OAUTH_TOKEN.getName()),
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
