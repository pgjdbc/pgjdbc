/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Connects a real driver to a backend that declares a message length and then sends nothing, over
 * a loopback socket. No PostgreSQL is involved, because these are the messages a hostile
 * server can send before anything is authenticated.
 */
@Isolated("Uses Locale.setDefault")
class MaliciousBackendTest {

  // The assertions match on message text, which GT.tr translates once these strings are
  // localized.
  private static Locale defaultLocale;

  @BeforeAll
  static void useRootLocale() {
    defaultLocale = Locale.getDefault();
    Locale.setDefault(Locale.ROOT);
  }

  @AfterAll
  static void restoreLocale() {
    Locale.setDefault(defaultLocale);
  }

  private static final int SSL_REQUEST = 80877103;
  private static final int GSS_ENC_REQUEST = 80877104;

  /**
   * Refuses SSL and GSS encryption, consumes the startup packet, declares a message of the given
   * type and length, then holds the socket open. A driver that waits for the body it will never
   * get hangs until its socket timeout. One that rejects the length fails at once.
   */
  private static class Backend implements Closeable, Runnable {
    private final ServerSocket serverSocket;
    private final int messageType;
    private final int declaredLength;
    private final byte[] body;
    private volatile boolean closed;

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
          // The driver hanging up mid-write is the expected outcome.
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

  private static void assertConnectionRefused(int messageType, int declaredLength)
      throws IOException {
    assertConnectionRefused(messageType, declaredLength, new byte[0], "message length");
  }

  /** For the cases that surface as PROTOCOL_VIOLATION rather than as an IOException. */
  private static void assertProtocolViolation(int messageType, int declaredLength, byte[] body,
      String expectedMessage) throws IOException {
    try (Backend backend = new Backend(messageType, declaredLength, body)) {
      long start = System.nanoTime();
      SQLException e = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      long elapsedMs = (System.nanoTime() - start) / 1000000;
      assertTrue(elapsedMs < 5000, "took " + elapsedMs + "ms, so it waited for the body");
      PSQLException violation = null;
      for (Throwable c = e; c != null && c != c.getCause(); c = c.getCause()) {
        if (c instanceof PSQLException
            && PSQLState.PROTOCOL_VIOLATION.getState().equals(((PSQLException) c).getSQLState())) {
          violation = (PSQLException) c;
          break;
        }
      }
      assertNotNull(violation, "expected a PROTOCOL_VIOLATION in the chain, got: " + e);
      assertTrue(violation.getMessage().contains(expectedMessage),
          "unexpected failure: " + violation);
    }
  }

  private static void assertConnectionRefused(int messageType, int declaredLength, byte[] body,
      String expectedMessage) throws IOException {
    try (Backend backend = new Backend(messageType, declaredLength, body)) {
      long start = System.nanoTime();
      SQLException e = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      long elapsedMs = (System.nanoTime() - start) / 1000000;

      // The socket timeout is ten seconds, so failing well inside it means the length was
      // rejected rather than the body awaited.
      assertTrue(elapsedMs < 5000, "took " + elapsedMs + "ms, so it waited for the body");
      // Timing alone would pass for a prompt timeout, so pin the cause.
      Throwable cause = rootCause(e);
      assertTrue(cause instanceof IOException, "expected an IOException, got: " + cause);
      assertTrue(cause.getMessage().contains(expectedMessage), "unexpected failure: " + cause);
    }
  }

  private static Throwable rootCause(Throwable t) {
    Throwable cause = t;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  /** An ErrorResponse declares a huge length before authentication and no body follows. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAHugePreAuthenticationErrorResponse() throws IOException {
    assertConnectionRefused(PgMessageType.ERROR_RESPONSE, Integer.MAX_VALUE);
  }

  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsANegativeErrorResponseLength() throws IOException {
    assertConnectionRefused(PgMessageType.ERROR_RESPONSE, Integer.MIN_VALUE);
  }

  /** An option count driving a loop with a string concatenation per iteration. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnUnrecognizedOptionCountTooLargeForTheMessage() throws IOException {
    // Protocol version, then a count of options that a twelve byte message cannot hold.
    byte[] body = {0, 3, 0, 0, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    assertProtocolViolation(PgMessageType.NEGOTIATE_PROTOCOL_RESPONSE, 12, body,
        "unrecognized options");
  }

  /** A negative count skips the loop and leaves the declared body unread. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsANegativeUnrecognizedOptionCount() throws IOException {
    byte[] body = {0, 3, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    assertProtocolViolation(PgMessageType.NEGOTIATE_PROTOCOL_RESPONSE, 12, body,
        "unrecognized options");
  }

  /** With no unrecognized options the message is exactly its twelve byte fixed part. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnOversizedNegotiateProtocolVersionWithNoOptions() throws IOException {
    byte[] body = {0, 3, 0, 0, 0, 0, 0, 0};
    assertProtocolViolation(PgMessageType.NEGOTIATE_PROTOCOL_RESPONSE, 40, body,
        "NegotiateProtocolVersion");
  }

  /** The length that would otherwise reach the SASL and SSPI handlers as a payload size. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAHugeAuthenticationMessage() throws IOException {
    assertConnectionRefused(PgMessageType.AUTHENTICATION_RESPONSE, PGStream.MAX_MESSAGE_LENGTH);
  }

  /** One byte above the pre-authentication cap, which is well below the buffered one. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void rejectsAnErrorResponseAboveThePreAuthenticationCap() throws IOException {
    assertConnectionRefused(PgMessageType.ERROR_RESPONSE,
        PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH + 1);
  }

  /** An ErrorResponse at the cap exactly is sent in full and must reach the caller. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void acceptsAnErrorResponseAtThePreAuthenticationCap() throws IOException {
    int bodyLength = PGStream.MAX_PRE_AUTH_MESSAGE_LENGTH - 4;
    // The body is one 'M' field made of its tag, the text, the string terminator and the
    // field list terminator.
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
          "expected the server error message, got: " + e.getMessage());
    }
  }

  /** Every refused length reaches the caller as a protocol violation, not as a transport error. */
  @Test
  @Timeout(value = 30, unit = TimeUnit.SECONDS)
  void reportsARefusedLengthAsAProtocolViolation() throws IOException {
    try (Backend backend = new Backend(PgMessageType.ERROR_RESPONSE, Integer.MAX_VALUE,
        new byte[0])) {
      SQLException e = assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), e.toString());
    }
  }

  /**
   * A server that keeps asking for a password keeps the client answering. Counting what the
   * driver sent pins the cap, where merely observing that the loop ended would not.
   */
  @Test
  @Timeout(value = 60, unit = TimeUnit.SECONDS)
  void stopsAnsweringAfterTheAuthenticationMessageCap() throws IOException {
    try (PasswordProbingBackend backend = new PasswordProbingBackend()) {
      assertThrows(SQLException.class,
          () -> DriverManager.getConnection(backend.getUrl()).close());
      assertEquals(PGStream.MAX_AUTH_ROUND_TRIPS, backend.passwordsReceived(),
          "the driver must answer exactly the capped number of requests");
    }
  }

  /**
   * Answers every password with another AuthenticationCleartextPassword, so the authentication
   * loop only ends when the driver stops it.
   */
  private static class PasswordProbingBackend implements Closeable, Runnable {
    private final ServerSocket serverSocket;
    private final AtomicInteger passwords = new AtomicInteger();
    private volatile boolean closed;

    PasswordProbingBackend() throws IOException {
      this.serverSocket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
      this.serverSocket.setSoTimeout(30000);
      Thread thread = new Thread(this, "password-probing-backend");
      thread.setDaemon(true);
      thread.start();
    }

    String getUrl() {
      return "jdbc:postgresql://127.0.0.1:" + serverSocket.getLocalPort() + "/test"
          + "?user=test&password=test&connectTimeout=10&socketTimeout=10&loginTimeout=10";
    }

    int passwordsReceived() {
      return passwords.get();
    }

    @Override
    public void run() {
      Socket socket = null;
      try {
        socket = serverSocket.accept();
        socket.setSoTimeout(30000);
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();
        Backend.consumeStartup(in, out);
        while (!closed) {
          // AuthenticationCleartextPassword
          out.write(PgMessageType.AUTHENTICATION_RESPONSE);
          out.write(new byte[]{0, 0, 0, 8, 0, 0, 0, 3});
          out.flush();
          if (in.read() < 0) {
            return;
          }
          int length = Backend.readInt4(in);
          for (int i = 4; i < length; i++) {
            if (in.read() < 0) {
              return;
            }
          }
          passwords.incrementAndGet();
        }
      } catch (Exception e) {
        // The driver hanging up is the expected outcome.
      } finally {
        Backend.closeQuietly(socket);
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
}
