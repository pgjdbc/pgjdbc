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

import org.postgresql.plugin.OAuthTokenProvider;
import org.postgresql.plugin.OAuthTokenRequest;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Drives the OAUTHBEARER exchange against a backend that speaks a scripted conversation over
 * loopback, so the sequences no real server produces can be sent: a rejection followed by
 * {@code AuthenticationOk}, SASL CONTINUE or FINAL arriving before a mechanism was selected, and a
 * second mechanism offered once the exchange is under way.
 * The backend also records what the driver wrote, which pins the bytes of the two client
 * messages RFC 7628 specifies.
 */
class OAuthHandshakeTest {

  private static final String TOKEN = "test-token";

  /** The client message of RFC 7628 section 3.1, as the driver should build it for {@link #TOKEN}. */
  private static final byte[] CLIENT_MESSAGE =
      ("n,,\u0001auth=Bearer " + TOKEN + "\u0001\u0001").getBytes(StandardCharsets.UTF_8);

  private static final String ERROR_JSON = "{\"status\":\"invalid_token\",\"scope\":\"openid\"}";

  private static final int SSL_REQUEST = 80877103;
  private static final int GSS_ENC_REQUEST = 80877104;

  private static final int AUTH_REQ_OK = 0;
  private static final int AUTH_REQ_SASL = 10;
  private static final int AUTH_REQ_SASL_CONTINUE = 11;
  private static final int AUTH_REQ_SASL_FINAL = 12;

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

    String getUrl(String oauthProperties) {
      return "jdbc:postgresql://127.0.0.1:" + serverSocket.getLocalPort() + "/test"
          + "?user=test&sslmode=disable&oauthAllowInsecureConnection=true&" + oauthProperties
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

  /** {@code AuthenticationSASLContinue}, which RFC 7628 uses to convey the OAuth error result. */
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
        () -> DriverManager.getConnection(backend.getUrl("oauthToken=" + TOKEN)).close());
  }

  private static void assertProtocolViolation(Conversation conversation, String expectedMessage)
      throws IOException {
    try (Backend backend = new Backend(conversation)) {
      PSQLException violation = findViolation(connectExpectingFailure(backend));
      assertEquals(expectedMessage, violation.getMessage());
    }
  }

  /**
   * RFC 4422 section 3.6, which RFC 7628 section 3.2.3 follows, ends the exchange at the error.
   * The driver must not accept the connection the server offers after it rejected the token.
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
    }, GT.tr("Server sent additional OAuth data after error."));
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
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, authenticationSasl("SCRAM-SHA-256"), authenticationSaslContinue());
    })) {
      SQLException failure = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(
              backend.getUrl("oauthToken=" + TOKEN + "&password=secret")).close());
      assertEquals(GT.tr("Server started a second SASL exchange."),
          findViolation(failure).getMessage());
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

  /**
   * The whole {@code SASLInitialResponse}, so an off-by-one in either of its two lengths fails
   * here rather than only against a live server.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void sendsTheTokenInASaslInitialResponse() throws IOException {
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, errorResponse());
    })) {
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
   * Records the array it handed the driver, so the test can read it back after the connection
   * attempt. {@link OAuthTokenProvider} promises the driver zeroes it.
   */
  public static class RecordingProvider implements OAuthTokenProvider {
    static char @Nullable [] lastToken;

    @Override
    public char[] getToken(OAuthTokenRequest request) {
      char[] token = "provider-token".toCharArray();
      lastToken = token;
      return token;
    }
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void wipesTheTokenTheProviderReturned() throws IOException {
    RecordingProvider.lastToken = null;
    try (Backend backend = new Backend((b, in, out) -> {
      send(out, authenticationSasl("OAUTHBEARER"));
      b.readClientMessage(in);
      send(out, errorResponse());
    })) {
      String url = backend.getUrl(
          "oauthTokenProviderClassName=" + RecordingProvider.class.getName());
      assertThrows(SQLException.class, () -> DriverManager.getConnection(url).close());

      char[] token = RecordingProvider.lastToken;
      assertNotNull(token, "the provider should have been called");
      char[] wiped = new char[token.length];
      assertArrayEquals(wiped, token,
          "the driver should zero the array the provider returned, but it held: "
              + new String(token));
      // The token still has to have reached the wire, or zeroing it proves nothing.
      List<byte[]> written = backend.getClientMessages();
      assertEquals(1, written.size());
      assertTrue(new String(written.get(0), StandardCharsets.UTF_8).contains("Bearer provider-token"),
          "the SASLInitialResponse should carry the token the provider returned");
    }
  }
}
