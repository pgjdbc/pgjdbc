/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.v3.ConnectionFactoryImpl;
import org.postgresql.util.GT;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/**
 * Connects a real driver to a hostile server. {@link Backend} binds a loopback port and sends one
 * message of the kind a server can send before authentication, with a body that need not match its
 * declared length; the round trip limit is driven through a {@link CannedSocketFactory} instead. No
 * PostgreSQL server is involved.
 *
 * <p>The two limits under test are what an unauthenticated backend cannot exceed: the driver will
 * not allocate more than a fixed number of bytes for one message, and will not answer more than a
 * fixed number of authentication requests. A message past either limit fails the connection with a
 * protocol violation.</p>
 *
 * <p>Each assertion builds its expected text with {@link GT#tr}, the call the driver used, so the
 * tests hold in any locale.</p>
 */
class MaliciousBackendTest {

  /** The refusal {@link PGStream#receiveMessageLength} builds for a length outside its range. */
  private static String lengthRefusal(String messageName, int declared, int min, int max) {
    return GT.tr("Backend declared a {0} message length of {1} bytes, expected {2} to {3} bytes.",
        messageName, String.valueOf(declared), String.valueOf(min), String.valueOf(max));
  }

  /** The refusal for an ErrorResponse read during connection setup, where the limit is 30000. */
  private static String preAuthErrorRefusal(int declared) {
    return lengthRefusal("ErrorResponse", declared, 5, PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH);
  }

  /** The protocol version field of an SSLRequest packet. */
  private static final int SSL_REQUEST = 80877103;
  /** The protocol version field of a GSSENCRequest packet. */
  private static final int GSS_ENC_REQUEST = 80877104;

  /**
   * Refuses SSL and GSS encryption, consumes the startup packet, then sends one message with the
   * given type, declared length and body, and holds the socket open. A driver that waits for the
   * rest of the declared length blocks until its socket timeout, and one that refuses the length
   * fails at once.
   */
  private static class Backend implements Closeable, Runnable {
    private final ServerSocket serverSocket;
    private final int messageType;
    private final int declaredLength;
    private final byte[] body;
    private volatile boolean closed;

    /**
     * Binds an ephemeral port on 127.0.0.1 and serves it from a daemon thread, so the URL from
     * {@link #getUrl()} accepts a connection as soon as the constructor returns.
     */
    Backend(int messageType, int declaredLength, byte[] body) throws IOException {
      this.messageType = messageType;
      this.declaredLength = declaredLength;
      this.body = body;
      this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      this.serverSocket.setSoTimeout(30000);
      Thread thread = new Thread(this, "malicious-backend");
      thread.setDaemon(true);
      thread.start();
    }

    /**
     * Returns a URL whose connect, socket and login timeouts are 10 seconds each, so a driver that
     * waits for the rest of the declared length still fails inside the 30 second test timeout.
     */
    String getUrl() {
      return "jdbc:postgresql://127.0.0.1:" + serverSocket.getLocalPort() + "/test"
          + "?user=test&password=test&connectTimeout=10&socketTimeout=10&loginTimeout=10";
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
          Backend.consumeStartup(in, out);
          out.write(messageType);
          out.write(declaredLength >>> 24);
          out.write(declaredLength >>> 16);
          out.write(declaredLength >>> 8);
          out.write(declaredLength);
          out.write(body);
          out.flush();
          while (in.read() >= 0) {
            // Wait for the driver to close from its end.
          }
        } catch (Exception e) {
          // A driver that refuses the length closes the connection, so a failed write here is
          // expected.
        } finally {
          closeQuietly(socket);
        }
      }
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

    private static void closeQuietly(Socket socket) {
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException ignore) {
          // The socket is being discarded, so a failure to close it makes no difference.
        }
      }
    }

    @Override
    public void close() {
      closed = true;
      try {
        serverSocket.close();
      } catch (IOException ignore) {
        // The test is finished with the port either way.
      }
    }
  }

  /**
   * Asserts that a message of the given type and declared length, sent with no body, is refused
   * with {@code expectedMessage} as the root cause's text.
   */
  private static void assertConnectionRefused(int messageType, int declaredLength,
      String expectedMessage) throws IOException {
    assertConnectionRefused(messageType, declaredLength, new byte[0], expectedMessage);
  }

  /**
   * Asserts that the connection attempt fails within 5 seconds and that some exception in the cause
   * chain carries {@link PSQLState#PROTOCOL_VIOLATION} and {@code expectedMessage} as its text.
   * {@link #assertConnectionRefused(int, int, byte[], String)} covers the refusals that reach the
   * caller as an {@link IOException} instead.
   */
  private static void assertProtocolViolation(int messageType, int declaredLength, byte[] body,
      String expectedMessage) throws IOException {
    try (Backend backend = new Backend(messageType, declaredLength, body)) {
      long start = System.nanoTime();
      SQLException e = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      long elapsedMs = (System.nanoTime() - start) / 1000000;
      assertTrue(elapsedMs < 5000, "took " + elapsedMs + "ms, so the driver waited for the body");
      PSQLException violation = null;
      for (Throwable c = e; c != null && c != c.getCause(); c = c.getCause()) {
        if (c instanceof PSQLException
            && PSQLState.PROTOCOL_VIOLATION.getState().equals(((PSQLException) c).getSQLState())) {
          violation = (PSQLException) c;
          break;
        }
      }
      assertNotNull(violation, "expected a PROTOCOL_VIOLATION in the cause chain, got: " + e);
      assertEquals(expectedMessage, violation.getMessage());
    }
  }

  /**
   * Asserts that the connection attempt fails within 5 seconds with an {@link IOException} at the
   * root of the cause chain, carrying {@code expectedMessage} in its text.
   */
  private static void assertConnectionRefused(int messageType, int declaredLength, byte[] body,
      String expectedMessage) throws IOException {
    try (Backend backend = new Backend(messageType, declaredLength, body)) {
      long start = System.nanoTime();
      SQLException e = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      long elapsedMs = (System.nanoTime() - start) / 1000000;

      // Failing well inside the 10 second socket timeout means the driver refused the length
      // rather than waiting for the body.
      assertTrue(elapsedMs < 5000, "took " + elapsedMs + "ms, so the driver waited for the body");
      // A quick failure of any other kind would pass the timing check too, so check the cause.
      Throwable cause = rootCause(e);
      assertTrue(cause instanceof IOException, "expected an IOException, got: " + describe(e));
      assertEquals(expectedMessage, cause.getMessage());
    }
  }

  /**
   * Returns every exception in the chain, each SQLException followed by its SQLState in brackets.
   */
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

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  /**
   * {@link Integer#MAX_VALUE} is the largest length the 4 length bytes can declare, and no body
   * follows it.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAHugePreAuthenticationErrorResponse() throws IOException {
    assertConnectionRefused(PgMessageType.ERROR_RESPONSE, Integer.MAX_VALUE,
        preAuthErrorRefusal(Integer.MAX_VALUE));
  }

  /**
   * The body size is the declared {@link Integer#MIN_VALUE} less its own 4 bytes, which wraps round
   * to a positive two gigabytes.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsANegativeErrorResponseLength() throws IOException {
    assertConnectionRefused(PgMessageType.ERROR_RESPONSE, Integer.MIN_VALUE,
        preAuthErrorRefusal(Integer.MIN_VALUE));
  }

  /**
   * The option count is 0x7FFFFFFF. Without the check, that count drives a loop which reads an
   * option name and concatenates it on every one of those iterations.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnUnrecognizedOptionCountTooLargeForTheMessage() throws IOException {
    // Protocol version 3.0, then a count of 0x7FFFFFFF options, which the declared 12 bytes
    // cannot hold.
    byte[] body = {0, 3, 0, 0, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    assertProtocolViolation(PgMessageType.NEGOTIATE_PROTOCOL_RESPONSE, 12, body,
        GT.tr("Backend reported {0} unrecognized options in a message of {1} bytes.",
            String.valueOf(Integer.MAX_VALUE), "12"));
  }

  /**
   * A count of -1 is refused by the same check as a count too large for the message, because a
   * negative count cannot describe any options.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsANegativeUnrecognizedOptionCount() throws IOException {
    byte[] body = {0, 3, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    assertProtocolViolation(PgMessageType.NEGOTIATE_PROTOCOL_RESPONSE, 12, body,
        GT.tr("Backend reported {0} unrecognized options in a message of {1} bytes.", "-1", "12"));
  }

  /**
   * With no unrecognized options the message is exactly its 12 byte fixed part, 4 length + 4
   * protocol version + 4 count, so the declared 40 is refused.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnOversizedNegotiateProtocolVersionWithNoOptions() throws IOException {
    byte[] body = {0, 3, 0, 0, 0, 0, 0, 0};
    assertProtocolViolation(PgMessageType.NEGOTIATE_PROTOCOL_RESPONSE, 40, body,
        GT.tr("Backend sent a {0} byte NegotiateProtocolVersion with no unrecognized options.",
            "40"));
  }

  /**
   * The declared {@link PGStream#MAX_MESSAGE_LENGTH} is a length the driver accepts for a DataRow.
   * An AuthenticationRequest is held to {@link PGStream#MAX_SMALL_MESSAGE_LENGTH} instead, because
   * its declared length sizes the SASL and SSPI reads.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAHugeAuthenticationMessage() throws IOException {
    assertConnectionRefused(PgMessageType.AUTHENTICATION_RESPONSE, PGStream.MAX_MESSAGE_LENGTH,
        lengthRefusal("AuthenticationRequest", PGStream.MAX_MESSAGE_LENGTH, 8,
            PGStream.MAX_SMALL_MESSAGE_LENGTH));
  }

  /**
   * One byte above {@link PGStream#MAX_PRE_AUTH_MESSAGE_LENGTH} is refused even though it is well
   * below {@link PGStream#MAX_BUFFERED_MESSAGE_LENGTH}, the limit that applies once the connection
   * is authenticated.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnErrorResponseAboveThePreAuthenticationCap() throws IOException {
    assertConnectionRefused(PgMessageType.ERROR_RESPONSE,
        PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH + 1,
        preAuthErrorRefusal(PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH + 1));
  }

  /**
   * An ErrorResponse of exactly {@link PGStream#MAX_PRE_AUTH_MESSAGE_LENGTH} bytes is sent in full,
   * and its text has to reach the caller.
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void acceptsAnErrorResponseAtThePreAuthenticationCap() throws IOException {
    int bodyLength = PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH - 4;
    // We fill the body with one 'M' field, the primary error message, because that is the field
    // the caller sees: the 'x' bytes below are what the assertion looks for. The layout is the
    // tag, the text, the string terminator, then the zero byte that ends the field list.
    byte[] body = new byte[bodyLength];
    body[0] = 'M';
    Arrays.fill(body, 1, bodyLength - 2, (byte) 'x');
    body[bodyLength - 2] = 0;
    body[bodyLength - 1] = 0;

    try (Backend backend = new Backend(PgMessageType.ERROR_RESPONSE,
        PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH, body)) {
      SQLException e = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      assertTrue(e.getMessage().startsWith("xxx"),
          "expected the server error message, got: " + describe(e));
    }
  }

  /** A refused length reaches the caller as a protocol violation, not as a transport error. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void reportsARefusedLengthAsAProtocolViolation() throws IOException {
    try (Backend backend = new Backend(PgMessageType.ERROR_RESPONSE, Integer.MAX_VALUE,
        new byte[0])) {
      SQLException e = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), describe(e));
    }
  }

  /**
   * Answers ten more password requests than the limit allows and expects the driver to stop at
   * {@link PGStream#MAX_AUTH_ROUND_TRIPS} with a protocol violation, having sent one
   * PasswordMessage per request.
   *
   * <p>The messages are counted from a canned socket, because a peer cannot count them reliably:
   * the driver resets the connection when it gives up and Windows drops unread data on a reset.</p>
   */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void stopsAnsweringAfterTheAuthenticationMessageCap() throws Exception {
    CannedSocketFactory factory =
        new CannedSocketFactory(passwordRequests(PGStream.MAX_AUTH_ROUND_TRIPS + 10));
    PGStream stream = new PGStream(factory, new HostSpec("localhost", 5432), 0, 8192);
    Properties info = new Properties();
    PGProperty.PASSWORD.set(info, "test");

    PSQLException e = assertThrows(PSQLException.class, () -> authenticate(stream, info));

    assertAll(
        () -> assertEquals(GT.tr("Backend sent more than {0} messages without finishing"
            + " authentication.", PGStream.MAX_AUTH_ROUND_TRIPS), e.getMessage()),
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), describe(e)),
        () -> assertTrue(stream.isBroken(), "the stream must be marked broken"),
        () -> assertEquals(PGStream.MAX_AUTH_ROUND_TRIPS,
            countPasswordMessages(factory.getWritten()),
            "the driver must send one PasswordMessage per request and stop at the limit"));
  }

  /**
   * Returns {@code count} AuthenticationCleartextPassword messages, each carrying a declared
   * length of 8 and the authentication code 3.
   */
  private static byte[] passwordRequests(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (int i = 0; i < count; i++) {
      out.write(PgMessageType.AUTHENTICATION_RESPONSE);
      out.write(new byte[]{0, 0, 0, 8, 0, 0, 0, 3}, 0, 8);
    }
    return out.toByteArray();
  }

  /**
   * Counts the PasswordMessages in the driver's output, and fails unless that output is whole
   * PasswordMessages and only those.
   */
  private static int countPasswordMessages(byte[] written) {
    int count = 0;
    int pos = 0;
    while (pos < written.length) {
      assertEquals(PgMessageType.PASSWORD_REQUEST, written[pos], "message type at byte " + pos);
      int length = ((written[pos + 1] & 0xFF) << 24) | ((written[pos + 2] & 0xFF) << 16)
          | ((written[pos + 3] & 0xFF) << 8) | (written[pos + 4] & 0xFF);
      pos += 1 + length;
      count++;
    }
    assertEquals(written.length, pos, "the last message must end where the output ends");
    return count;
  }

  /**
   * Runs the authentication exchange over the given stream as user {@code test} on host
   * {@code localhost}.
   * ConnectionFactoryImpl.doAuthentication is private, so this calls it by reflection and throws
   * what it threw rather than the {@link InvocationTargetException} around it.
   */
  private static void authenticate(PGStream stream, Properties info) throws Exception {
    Method method = ConnectionFactoryImpl.class.getDeclaredMethod("doAuthentication",
        PGStream.class, String.class, String.class, Properties.class);
    method.setAccessible(true);
    try {
      method.invoke(null, stream, "localhost", "test", info);
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof Exception) {
        throw (Exception) e.getCause();
      }
      throw e;
    }
  }
}
