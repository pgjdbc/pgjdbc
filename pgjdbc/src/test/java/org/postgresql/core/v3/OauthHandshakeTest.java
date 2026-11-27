/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.ds.PGSimpleDataSource;
import org.postgresql.plugin.OauthTokenProvider;
import org.postgresql.plugin.OauthTokenRequest;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Drives the OAUTHBEARER exchange against a backend that speaks a scripted conversation over
 * loopback, so the sequences no real server produces can be sent: a rejection followed by
 * {@code AuthenticationOk}, SASL CONTINUE or FINAL arriving before a mechanism was selected, and a
 * second mechanism offered once the exchange is under way.
 * The backend also records what the driver wrote, which pins the bytes of the two client
 * messages RFC 7628 specifies.
 */
class OauthHandshakeTest {

  private static final String TOKEN = "test-token";

  /** The client message of RFC 7628 section 3.1, as the driver should build it for {@link #TOKEN}. */
  private static final byte[] CLIENT_MESSAGE =
      ("n,,\u0001auth=Bearer " + TOKEN + "\u0001\u0001").getBytes(StandardCharsets.UTF_8);

  private static final String ERROR_JSON = "{\"status\":\"invalid_token\",\"scope\":\"openid\"}";

  private static final int SSL_REQUEST = 80877103;
  private static final int GSS_ENC_REQUEST = 80877104;

  private static final int AUTH_REQ_OK = 0;
  private static final int AUTH_REQ_CLEARTEXT_PASSWORD = 3;
  private static final int AUTH_REQ_SASL = 10;
  private static final int AUTH_REQ_SASL_CONTINUE = 11;
  private static final int AUTH_REQ_SASL_FINAL = 12;

  private static final Logger oauthLogger =
      Logger.getLogger("org.postgresql.core.v3.OauthAuthenticator");
  private static boolean previousUseParentHandlers;

  @BeforeAll
  static void setUp() {
    previousUseParentHandlers = oauthLogger.getUseParentHandlers();
    // The insecure-URL tests trigger the once-per-JVM WARNING; keep it off the console.
    oauthLogger.setUseParentHandlers(false);
  }

  @AfterAll
  static void tearDown() {
    oauthLogger.setUseParentHandlers(previousUseParentHandlers);
  }

  /** The part of a conversation that follows the startup packet. */
  @FunctionalInterface
  private interface Conversation {
    void run(Backend backend, InputStream in, OutputStream out) throws IOException;
  }

  /**
   * Answers the startup packet with a scripted conversation and records every message the driver
   * writes in reply.
   */
  private static class Backend implements Closeable, Runnable {
    private final ServerSocket serverSocket;
    private final Conversation conversation;
    private final List<byte[]> clientMessages = Collections.synchronizedList(new ArrayList<>());
    private volatile boolean closed;

    Backend(Conversation conversation) throws IOException {
      this.conversation = conversation;
      this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      this.serverSocket.setSoTimeout(30000);
      Thread thread = new Thread(this, "oauth-backend");
      thread.setDaemon(true);
      thread.start();
    }

    int getPort() {
      return serverSocket.getLocalPort();
    }

    String getInsecureUrl(String oauthProperties) {
      return getUrlWithoutInsecureFlag("oauthAllowInsecureConnection=true&" + oauthProperties);
    }

    String getUrlWithoutInsecureFlag(String oauthProperties) {
      return "jdbc:postgresql://127.0.0.1:" + serverSocket.getLocalPort() + "/test"
          + "?user=test&sslmode=disable&" + oauthProperties
          + "&connectTimeout=10&socketTimeout=10&loginTimeout=10";
    }

    /** The messages the driver wrote after the startup packet, each with its type and length. */
    List<byte[]> getClientMessages() {
      synchronized (clientMessages) {
        return new ArrayList<>(clientMessages);
      }
    }

    @Override
    public void run() {
      while (!closed) {
        Socket socket = null;
        try {
          socket = serverSocket.accept();
          socket.setSoTimeout(30000);
          InputStream in = socket.getInputStream();
          OutputStream out = socket.getOutputStream();
          consumeStartup(in, out);
          conversation.run(this, in, out);
          while (in.read() >= 0) {
            // Wait for the driver to close from its end.
          }
        } catch (Exception e) {
          // The driver hanging up mid-conversation is the expected outcome.
        } finally {
          closeQuietly(socket);
        }
      }
    }

    /** Reads one frontend message and records it whole, type byte and length included. */
    byte[] readClientMessage(InputStream in) throws IOException {
      int type = in.read();
      if (type < 0) {
        throw new IOException("end of stream before a frontend message");
      }
      int length = readInt4(in);
      byte[] message = new byte[1 + length];
      message[0] = (byte) type;
      writeInt4(message, 1, length);
      for (int i = 5; i < message.length; i++) {
        int b = in.read();
        if (b < 0) {
          throw new IOException("end of stream inside a frontend message");
        }
        message[i] = (byte) b;
      }
      clientMessages.add(message);
      return message;
    }

    private static void consumeStartup(InputStream in, OutputStream out) throws IOException {
      while (true) {
        int length = readInt4(in);
        byte[] body = new byte[length - 4];
        for (int i = 0; i < body.length; i++) {
          int b = in.read();
          if (b < 0) {
            throw new IOException("end of stream in startup packet");
          }
          body[i] = (byte) b;
        }
        int code = length == 8 ? ((body[0] & 0xFF) << 24) | ((body[1] & 0xFF) << 16)
            | ((body[2] & 0xFF) << 8) | (body[3] & 0xFF) : 0;
        if (code != SSL_REQUEST && code != GSS_ENC_REQUEST) {
          return;
        }
        out.write('N');
        out.flush();
      }
    }

    private static int readInt4(InputStream in) throws IOException {
      int value = 0;
      for (int i = 0; i < 4; i++) {
        int b = in.read();
        if (b < 0) {
          throw new IOException("end of stream");
        }
        value = (value << 8) | b;
      }
      return value;
    }

    private static void closeQuietly(@Nullable Socket socket) {
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException ignore) {
          // nothing to do
        }
      }
    }

    @Override
    public void close() {
      closed = true;
      try {
        serverSocket.close();
      } catch (IOException ignore) {
        // nothing to do
      }
    }
  }

  private static void writeInt4(byte[] target, int offset, int value) {
    target[offset] = (byte) (value >>> 24);
    target[offset + 1] = (byte) (value >>> 16);
    target[offset + 2] = (byte) (value >>> 8);
    target[offset + 3] = (byte) value;
  }

  private static byte[] message(char type, byte[] body) {
    byte[] out = new byte[5 + body.length];
    out[0] = (byte) type;
    writeInt4(out, 1, body.length + 4);
    System.arraycopy(body, 0, out, 5, body.length);
    return out;
  }

  private static byte[] bytes(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) {
      out.write(part, 0, part.length);
    }
    return out.toByteArray();
  }

  private static byte[] int4(int value) {
    byte[] out = new byte[4];
    writeInt4(out, 0, value);
    return out;
  }

  private static byte[] cstring(String value) {
    return bytes(value.getBytes(StandardCharsets.UTF_8), new byte[]{0});
  }

  /** {@code AuthenticationSASL} offering the given mechanisms, in order. */
  private static byte[] authenticationSasl(String... mechanisms) {
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    byte[] request = int4(AUTH_REQ_SASL);
    body.write(request, 0, request.length);
    for (String mechanism : mechanisms) {
      byte[] name = cstring(mechanism);
      body.write(name, 0, name.length);
    }
    body.write(0);
    return message('R', body.toByteArray());
  }

  /** The OAuth error result (RFC 7628 section 3.2.2), carried in AuthenticationSASLContinue. */
  private static byte[] authenticationSaslContinue() {
    return message('R', bytes(int4(AUTH_REQ_SASL_CONTINUE),
        ERROR_JSON.getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] authenticationSaslFinal() {
    return message('R', bytes(int4(AUTH_REQ_SASL_FINAL),
        "v=irrelevant".getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] authenticationOk() {
    return message('R', int4(AUTH_REQ_OK));
  }

  private static byte[] parameterStatus(String name, String value) {
    return message('S', bytes(cstring(name), cstring(value)));
  }

  private static byte[] readyForQuery() {
    return message('Z', new byte[]{'I'});
  }

  private static byte[] errorResponse() {
    return message('E', bytes(
        cstring("SFATAL"),
        cstring("C28000"),
        cstring("MOAUTHBEARER authentication failed for user \"test\""),
        new byte[]{0}));
  }

  private static void send(OutputStream out, byte[]... messages) throws IOException {
    for (byte[] m : messages) {
      out.write(m);
    }
    out.flush();
  }

  /** Every exception in the chain with its SQLSTATE, so a failure says what actually arrived. */
  private static String describe(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
      if (sb.length() > 0) {
        sb.append(", caused by ");
      }
      sb.append(c);
      if (c instanceof SQLException) {
        sb.append(" [").append(((SQLException) c).getSQLState()).append(']');
      }
    }
    return sb.toString();
  }

  private static PSQLException findViolation(Throwable t) {
    for (Throwable c = t; c != null && c != c.getCause(); c = c.getCause()) {
      if (c instanceof PSQLException
          && PSQLState.PROTOCOL_VIOLATION.getState().equals(((PSQLException) c).getSQLState())) {
        return (PSQLException) c;
      }
    }
    throw new AssertionError("expected a PROTOCOL_VIOLATION in the chain, got: " + describe(t));
  }

  private static SQLException connectExpectingFailure(Backend backend) {
    return assertThrows(SQLException.class,
        () -> DriverManager.getConnection(backend.getInsecureUrl("oauthToken=" + TOKEN)).close());
  }

  /**
   * Offers OAUTHBEARER, records the {@code SASLInitialResponse}, and answers it with an
   * {@code ErrorResponse}, so the connection fails at once instead of waiting out socketTimeout.
   */
  private static Backend tokenRecordingBackend() throws IOException {
    return new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, errorResponse());
    });
  }

  private static void assertSentToken(Backend backend, String token) {
    List<byte[]> written = backend.getClientMessages();
    assertEquals(1, written.size(), "expected the SASLInitialResponse alone");
    assertTrue(new String(written.get(0), StandardCharsets.UTF_8).contains("auth=Bearer " + token),
        "the SASLInitialResponse should carry " + token);
  }

  private static void assertProtocolViolation(Conversation conversation, String expectedMessage)
      throws IOException {
    try (Backend backend = new Backend(conversation)) {
      PSQLException violation = findViolation(connectExpectingFailure(backend));
      assertEquals(expectedMessage, violation.getMessage());
    }
  }

  /**
   * After an error result, RFC 7628 section 3.2.3 has the server fail the exchange once the
   * client answers. The driver must not accept the connection the server offers after it
   * rejected the token.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesAuthenticationOkAfterTheServerRejectedTheToken() throws IOException {
    assertProtocolViolation((backend, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      backend.readClientMessage(in);
      send(out, authenticationSaslContinue());
      backend.readClientMessage(in);
      send(out, authenticationOk(), readyForQuery());
    }, GT.tr("The server continued the OAUTHBEARER exchange after it reported an error."));
  }

  /** The same sequence, with a second error result in place of {@code AuthenticationOk}. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesASecondErrorResult() throws IOException {
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, authenticationSaslContinue());
      b.readClientMessage(in);
      send(out, authenticationSaslContinue());
    })) {
      PSQLException violation = findViolation(connectExpectingFailure(backend));
      assertEquals(
          GT.tr("The server continued the OAUTHBEARER exchange after it reported an error."),
          violation.getMessage());
      assertEquals(2, backend.getClientMessages().size(),
          "the driver should answer the first error result only");
    }
  }

  /** The same sequence, with {@code AuthenticationSASLFinal} in place of {@code AuthenticationOk}. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesSaslFinalAfterTheServerRejectedTheToken() throws IOException {
    assertProtocolViolation((backend, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      backend.readClientMessage(in);
      send(out, authenticationSaslContinue());
      backend.readClientMessage(in);
      send(out, authenticationSaslFinal());
    }, GT.tr("SASL FINAL message received out of order."));
  }

  /**
   * A server that starts an OAUTHBEARER exchange and then offers SCRAM violates the protocol,
   * which only allows one SASL exchange per connection.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesASecondSaslExchange() throws IOException {
    assertProtocolViolation((backend, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      backend.readClientMessage(in);
      send(out, authenticationSasl("SCRAM-SHA-256"));
    }, GT.tr("Server started a second SASL exchange."));
  }

  /** The same refusal when the first exchange is SCRAM. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesASecondSaslExchangeDuringScram() throws IOException {
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("SCRAM-SHA-256"));
      b.readClientMessage(in);
      send(out, authenticationSasl("OAUTHBEARER"));
    })) {
      SQLException failure = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(
              backend.getInsecureUrl("oauthToken=" + TOKEN + "&password=secret")).close());
      assertEquals(GT.tr("Server started a second SASL exchange."),
          findViolation(failure).getMessage());
      assertEquals(1, backend.getClientMessages().size(),
          "the driver should send the SCRAM client-first message and nothing after it");
    }
  }

  /**
   * Without oauthAllowInsecureConnection, the token does not travel over a plaintext connection,
   * and the provider is not asked for one.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesToSendTheTokenOverPlaintext() throws IOException {
    RecordingProvider.lastToken = null;
    try (Backend backend = tokenRecordingBackend()) {
      SQLException failure = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrlWithoutInsecureFlag(
              "oauthTokenProviderClassName=" + RecordingProvider.class.getName())).close());

      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), failure.getSQLState(),
          () -> describe(failure));
      assertEquals(GT.tr("OAUTHBEARER sends the bearer token only over an encrypted connection "
          + "to a verified server, and this connection is not encrypted (sslmode={0}). Set "
          + "sslmode=verify-full or verify-ca, or use GSS encryption (RFC 7628 section 3, "
          + "RFC 6750 section 5.3).",
          "disable"), failure.getMessage());
      assertEquals(0, backend.getClientMessages().size(), "the driver should send no token");
      assertEquals(null, RecordingProvider.lastToken, "the provider should not be called");
    }
  }

  /**
   * The driver decodes {@code +} in a URL to a space, which RFC 6750 does not allow in a token, so
   * a token with a {@code +} written as is in the URL is refused before it is sent.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesATokenWithAPlusDecodedToASpace() throws IOException {
    try (Backend backend = tokenRecordingBackend()) {
      SQLException failure = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getInsecureUrl("oauthToken=abc+def")).close());

      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), failure.getSQLState(),
          () -> describe(failure));
      assertEquals(GT.tr("The OAuth bearer token contains a character that RFC 6750 section 2.1 "
          + "does not allow. Check {0}, where a + in a URL must be written as %2B, or the value "
          + "the OauthTokenProvider returned.", "oauthToken"), failure.getMessage());
      assertEquals(0, backend.getClientMessages().size(), "the driver should send no token");
    }
  }

  /** The same token reaches the server when the URL writes its {@code +} as {@code %2B}. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void sendsATokenWithAPlusWrittenAsPercent2B() throws IOException {
    try (Backend backend = tokenRecordingBackend()) {
      assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getInsecureUrl("oauthToken=abc%2Bdef")).close());

      assertSentToken(backend, "abc+def");
    }
  }

  /**
   * A successful exchange meets requireAuth=oauth. The driver counts the exchange as finished
   * once it sends the token, so the AuthenticationOk that follows is not refused as premature.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void connectsWithRequireAuthOauth() throws Exception {
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, authenticationOk(), parameterStatus("server_version", "18.0"),
          parameterStatus("client_encoding", "UTF8"),
          parameterStatus("standard_conforming_strings", "on"),
          parameterStatus("DateStyle", "ISO, MDY"),
          parameterStatus("integer_datetimes", "on"),
          readyForQuery());
    })) {
      try (Connection connection = DriverManager.getConnection(
          // The server version lets application_name travel in the startup packet, so the
          // backend need not answer a query.
          backend.getInsecureUrl("oauthToken=" + TOKEN + "&requireAuth=oauth&assumeMinServerVersion=18"))) {
        assertNotNull(connection);
      }
      assertArrayEquals(CLIENT_MESSAGE,
          Arrays.copyOfRange(backend.getClientMessages().get(0),
              backend.getClientMessages().get(0).length - CLIENT_MESSAGE.length,
              backend.getClientMessages().get(0).length));
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesSaslContinueBeforeAMechanismWasSelected() throws IOException {
    assertProtocolViolation((backend, in, out) -> send(out, authenticationSaslContinue()),
        GT.tr("SASL CONTINUE message received out of order."));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesSaslFinalBeforeAMechanismWasSelected() throws IOException {
    assertProtocolViolation((backend, in, out) -> send(out, authenticationSaslFinal()),
        GT.tr("SASL FINAL message received out of order."));
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesAMechanismAfterTheEndOfTheList() throws IOException, InterruptedException {
    CountDownLatch conversationDone = new CountDownLatch(1);
    try (Backend backend = new Backend((b, in, out) -> {
      try {
        send(out, authenticationSasl("", "OAUTHBEARER"));
        // The driver should hang up on the bad list, so this read reaches end of stream. If it
        // sends the token instead, the read records it and the ErrorResponse fails the connection
        // at once instead of leaving it to wait out socketTimeout.
        b.readClientMessage(in);
        send(out, errorResponse());
      } finally {
        conversationDone.countDown();
      }
    })) {
      SQLException failure = connectExpectingFailure(backend);
      assertTrue(conversationDone.await(5, TimeUnit.SECONDS),
          "the backend should see the driver send a message or hang up");
      assertEquals(0, backend.getClientMessages().size(), "the driver should send no token");
      assertEquals(
          GT.tr("Received AuthenticationSASL message with invalid authentication mechanism list."),
          findViolation(failure).getMessage());
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void refusesAnEmptyNameInsideTheMechanismList() throws IOException {
    assertProtocolViolation(
        (backend, in, out) -> send(out, authenticationSasl("SCRAM-SHA-256", "", "OAUTHBEARER")),
        GT.tr("Received AuthenticationSASL message with invalid authentication mechanism list."));
  }

  /**
   * The whole {@code SASLInitialResponse}, so an off-by-one in either of its two lengths fails
   * here rather than only against a live server.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void sendsTheTokenInASaslInitialResponse() throws IOException {
    try (Backend backend = tokenRecordingBackend()) {
      connectExpectingFailure(backend);

      List<byte[]> written = backend.getClientMessages();
      assertEquals(1, written.size(), "expected the SASLInitialResponse alone");
      assertArrayEquals(
          bytes(
              new byte[]{'p'},
              int4(4 + "OAUTHBEARER".length() + 1 + 4 + CLIENT_MESSAGE.length),
              cstring("OAUTHBEARER"),
              int4(CLIENT_MESSAGE.length),
              CLIENT_MESSAGE),
          written.get(0));
    }
  }

  /** The response RFC 7628 section 3.2.3 requires: one message carrying a single 0x01 byte. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void answersTheRejectionWithASingleSohByte() throws IOException {
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, authenticationSaslContinue());
      b.readClientMessage(in);
      send(out, errorResponse());
    })) {
      connectExpectingFailure(backend);

      List<byte[]> written = backend.getClientMessages();
      assertEquals(2, written.size(), "expected the SASLInitialResponse and the dummy response");
      assertArrayEquals(new byte[]{'p', 0, 0, 0, 5, 1}, written.get(1));
    }
  }

  /**
   * The ErrorResponse says only that authentication failed, so the error result that says why
   * travels with it as the cause, with the server's line breaks kept out of the message.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void reportsTheErrorResultAsTheCauseOfTheFailure() throws IOException {
    String errorJson = "{\"status\":\"invalid_token\",\n\"scope\":\"openid\"}";
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, message('R', bytes(int4(AUTH_REQ_SASL_CONTINUE),
          errorJson.getBytes(StandardCharsets.UTF_8))));
      b.readClientMessage(in);
      send(out, errorResponse());
    })) {
      SQLException failure = connectExpectingFailure(backend);

      assertEquals(PSQLState.INVALID_AUTHORIZATION_SPECIFICATION.getState(),
          failure.getSQLState(), () -> describe(failure));
      Throwable cause = failure.getCause();
      assertNotNull(cause, () -> "expected the error result as the cause: " + describe(failure));
      assertEquals(
          GT.tr("The server rejected the OAuth bearer token with this error result: {0}",
              errorJson.replace('\n', ' ')),
          cause.getMessage());
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void sendsTheTokenSetOnADataSource() throws IOException {
    try (Backend backend = tokenRecordingBackend()) {
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      dataSource.setServerNames(new String[]{"127.0.0.1"});
      dataSource.setPortNumbers(new int[]{backend.getPort()});
      dataSource.setDatabaseName("test");
      dataSource.setUser("test");
      dataSource.setSslMode("disable");
      dataSource.setOauthAllowInsecureConnection(true);
      dataSource.setOauthToken(TOKEN);
      dataSource.setLoginTimeout(10);
      dataSource.setSocketTimeout(10);

      assertThrows(SQLException.class, () -> dataSource.getConnection().close());

      assertSentToken(backend, TOKEN);
    }
  }

  private static final String OAUTH_PASSWORD_HINT = GT.tr("{0} and {1} serve only OAUTHBEARER, "
      + "which was not used for this connection. A cloud IAM token is a password: "
      + "set it with {2} or {3}.",
      "oauthToken", "oauthTokenProviderClassName", "password", "authenticationPluginClassName");

  /**
   * RDS, Aurora, Azure and Cloud SQL take an IAM or Entra token as a password, so a user who sets
   * one as oauthToken gets a password request. The refusal says to set it as the password.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void hintsAtThePasswordWhenTheServerAsksForOneAndOauthIsConfigured() throws IOException {
    try (Backend backend = new Backend((b, in, out) ->
        send(out, message('R', int4(AUTH_REQ_CLEARTEXT_PASSWORD))))) {
      SQLException failure = connectExpectingFailure(backend);

      assertEquals(PSQLState.CONNECTION_REJECTED.getState(), failure.getSQLState());
      assertTrue(failure.getMessage().endsWith(" " + OAUTH_PASSWORD_HINT), failure.getMessage());
      assertEquals(0, backend.getClientMessages().size(), "the driver should send no password");
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void hintsAtThePasswordWhenTheServerOffersOnlyScramAndOauthIsConfigured() throws IOException {
    try (Backend backend = new Backend((b, in, out) ->
        send(out, authenticationSasl("SCRAM-SHA-256")))) {
      SQLException failure = connectExpectingFailure(backend);

      assertEquals(GT.tr("The server requested SCRAM-based authentication, but no password was "
          + "provided.") + " " + OAUTH_PASSWORD_HINT, failure.getMessage());
      assertEquals(0, backend.getClientMessages().size(), "the driver should send nothing");
    }
  }

  /**
   * Records the request it received and the array it handed the driver, so the test can read
   * them back after the connection attempt. {@link OauthTokenProvider} promises the driver zeroes
   * the array.
   */
  public static class RecordingProvider implements OauthTokenProvider {
    static @Nullable OauthTokenRequest lastRequest;
    static char @Nullable [] lastToken;

    @Override
    public char[] getToken(OauthTokenRequest request) {
      char[] token = "provider-token".toCharArray();
      lastRequest = request;
      lastToken = token;
      return token;
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void wipesTheTokenTheProviderReturned() throws IOException {
    RecordingProvider.lastToken = null;
    try (Backend backend = tokenRecordingBackend()) {
      String url = backend.getInsecureUrl(
          "oauthTokenProviderClassName=" + RecordingProvider.class.getName());
      assertThrows(SQLException.class, () -> DriverManager.getConnection(url).close());

      char[] token = RecordingProvider.lastToken;
      assertNotNull(token, "the provider should have been called");
      char[] wiped = new char[token.length];
      assertArrayEquals(wiped, token,
          () -> "the driver should zero the array the provider returned, but it held: "
              + new String(token));
      // The token still has to have reached the wire, or zeroing it proves nothing.
      assertSentToken(backend, "provider-token");
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void providerReceivesTheHostPortUserAndDatabaseOfTheAttempt() throws IOException {
    RecordingProvider.lastRequest = null;
    try (Backend backend = tokenRecordingBackend()) {
      String url = backend.getInsecureUrl(
          "oauthTokenProviderClassName=" + RecordingProvider.class.getName());
      assertThrows(SQLException.class, () -> DriverManager.getConnection(url).close());

      OauthTokenRequest request = RecordingProvider.lastRequest;
      assertNotNull(request, "the provider should have been called");
      assertEquals("127.0.0.1", request.getHost());
      assertEquals(backend.getPort(), request.getPort());
      assertEquals("test", request.getUser());
      assertEquals("test", request.getDatabase());
    }
  }

  /**
   * A provider configured through a connection property of its own, as the
   * {@link OauthTokenProvider} Javadoc recommends. The driver does not know
   * {@value #AUDIENCE}; the provider reads it from the {@code Properties} its constructor receives,
   * and puts it in the token, so the token on the wire shows the setting arrived.
   */
  public static class AudienceProvider implements OauthTokenProvider {
    static final String AUDIENCE = "com.example.oauth.audience";

    private final String audience;

    // ObjectFactory looks the constructor up with getConstructor, which sees only public ones.
    //CHECKSTYLE: OFF
    public AudienceProvider(Properties info) {
      audience = info.getProperty(AUDIENCE, "none");
    }
    //CHECKSTYLE: ON

    @Override
    public char[] getToken(OauthTokenRequest request) {
      return ("token-for-" + audience).toCharArray();
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void providerReadsItsOwnPropertyFromTheUrl() throws IOException {
    try (Backend backend = tokenRecordingBackend()) {
      String url = backend.getInsecureUrl(
          "oauthTokenProviderClassName=" + AudienceProvider.class.getName()
              + "&" + AudienceProvider.AUDIENCE + "=app");
      assertThrows(SQLException.class, () -> DriverManager.getConnection(url).close());

      assertSentToken(backend, "token-for-app");
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void providerReadsItsOwnPropertyFromTheConnectionProperties() throws IOException {
    try (Backend backend = tokenRecordingBackend()) {
      Properties info = new Properties();
      info.setProperty("oauthTokenProviderClassName", AudienceProvider.class.getName());
      info.setProperty(AudienceProvider.AUDIENCE, "app");
      assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getInsecureUrl(""), info).close());

      assertSentToken(backend, "token-for-app");
    }
  }

  /**
   * A DataSource keeps only the driver's own properties, so a provider used through one has to
   * read its settings from elsewhere, such as system properties, the environment or a file.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void dataSourceDoesNotPassAPropertyOfTheProvider() throws IOException {
    try (Backend backend = tokenRecordingBackend()) {
      PGSimpleDataSource dataSource = new PGSimpleDataSource();
      PSQLException refused = assertThrows(PSQLException.class,
          () -> dataSource.setProperty(AudienceProvider.AUDIENCE, "app"));
      assertEquals(GT.tr("Unsupported property name: {0}", AudienceProvider.AUDIENCE),
          refused.getMessage());

      // setUrl takes the properties the driver knows from the URL and drops the rest.
      dataSource.setUrl(backend.getInsecureUrl(
          "oauthTokenProviderClassName=" + AudienceProvider.class.getName()
              + "&" + AudienceProvider.AUDIENCE + "=app"));
      assertThrows(SQLException.class, () -> dataSource.getConnection().close());

      assertSentToken(backend, "token-for-none");
    }
  }
}
