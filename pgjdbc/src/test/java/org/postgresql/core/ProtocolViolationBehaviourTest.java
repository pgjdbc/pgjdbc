/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import javax.net.SocketFactory;

@Isolated("Tests modify System.properties")
class ProtocolViolationBehaviourTest {

  /**
   * Minimal {@code SocketFactory} that returns a connected, in-memory socket so we can
   * construct a {@link PGStream} without touching the network. The PGStream constructor
   * insists on a usable {@link Socket}; nothing in these tests actually drives I/O through
   * it.
   */
  private static SocketFactory inMemorySocketFactory(final byte[] inputBytes) {
    return new SocketFactory() {
      @Override
      public Socket createSocket() {
        return new Socket() {
          private final InputStream in = new ByteArrayInputStream(inputBytes);
          private final OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
              // discard
            }
          };
          private boolean closed;

          @Override
          public boolean isConnected() {
            return !closed;
          }

          @Override
          public boolean isClosed() {
            return closed;
          }

          @Override
          public synchronized void close() {
            closed = true;
          }

          @Override
          public InputStream getInputStream() {
            return in;
          }

          @Override
          public OutputStream getOutputStream() {
            return out;
          }

          @Override
          public void connect(SocketAddress endpoint, int timeout) {
            // already connected
          }
        };
      }

      @Override
      public Socket createSocket(String host, int port) {
        return createSocket();
      }

      @Override
      public Socket createSocket(String host, int port, InetAddress localHost, int localPort) {
        return createSocket();
      }

      @Override
      public Socket createSocket(InetAddress host, int port) {
        return createSocket();
      }

      @Override
      public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) {
        return createSocket();
      }
    };
  }

  /**
   * Builds a {@link PGStream} backed by {@code inputBytes}, ready for read-side tests.
   */
  private static PGStream newStream(byte[] inputBytes) throws IOException {
    return new PGStream(inMemorySocketFactory(inputBytes), new HostSpec("localhost", 1), 0, 8192);
  }

  /** Captures all log records emitted while installed on {@link PGStream}'s logger. */
  private static final class CapturingHandler extends Handler {
    final List<LogRecord> records = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }
  }

  private CapturingHandler capture;
  private Logger pgStreamLogger;
  private Level previousLevel;

  @BeforeEach
  void installLogCapture() {
    pgStreamLogger = Logger.getLogger(PGStream.class.getName());
    previousLevel = pgStreamLogger.getLevel();
    pgStreamLogger.setLevel(Level.ALL);
    capture = new CapturingHandler();
    capture.setLevel(Level.ALL);
    pgStreamLogger.addHandler(capture);
  }

  @AfterEach
  void removeLogCapture() {
    pgStreamLogger.removeHandler(capture);
    pgStreamLogger.setLevel(previousLevel);
  }

  @Test
  void defaultBehaviorFollowsCurrent() throws IOException {
    PGStream pgStream = newStream(new byte[0]);
    assertSame(ProtocolViolationBehaviour.CURRENT, pgStream.getProtocolViolationBehaviour(),
        "Newly constructed PGStream should pick up the JVM-wide ProtocolViolationBehaviour.CURRENT");
  }

  @Test
  void failModeThrowsAndPoisons() throws IOException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolViolationBehaviour(ProtocolViolationBehaviour.FAIL);

    IOException thrown = assertThrows(IOException.class, () ->
        pgStream.failOnDesync(IOException::new, "synthetic test message"));

    assertTrue(thrown.getMessage().contains("synthetic test message"),
        "Thrown exception should carry the original message");
    assertTrue(thrown.getMessage().contains(ProtocolViolationBehaviour.SYSTEM_PROPERTY),
        "FAIL-mode message should mention the silence-knob system property: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains("filing a bug report"),
        "FAIL-mode message should ask the user to file a bug report: " + thrown.getMessage());
    assertTrue(pgStream.isClosed(),
        "FAIL mode must poison the stream so connection pools discard it");
  }

  @Test
  void warnModeLogsAndContinues() throws IOException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolViolationBehaviour(ProtocolViolationBehaviour.WARN);

    // No throw expected.
    pgStream.failOnDesync(IOException::new, "warn-mode synthetic message");

    assertEquals(1, warningCount(), "WARN mode should emit exactly one WARNING record");
    LogRecord record = capture.records.get(0);
    assertEquals(Level.WARNING, record.getLevel());
    assertTrue(record.getMessage().contains("warn-mode synthetic message"),
        "Log message should carry the original text");
    // The Throwable must be attached so the log carries a stack trace pointing
    // at the protocol-reader site. Whether the trace is rendered is up to the
    // logger formatter, but the LogRecord itself must hold the Throwable.
    Throwable thrown = record.getThrown();
    assertNotNull(thrown, "WARN mode must attach the Throwable to the LogRecord");
    assertTrue(thrown instanceof IOException,
        "Attached Throwable should match the factory type: " + thrown);
    assertTrue(thrown.getMessage().contains("warn-mode synthetic message"),
        "Attached Throwable should carry the original message");
    assertFalse(thrown.getMessage().contains(ProtocolViolationBehaviour.SYSTEM_PROPERTY),
        "WARN-mode Throwable must not carry the silence hint; the user already configured the property");
    assertFalse(pgStream.isClosed(),
        "WARN mode must not poison the stream");
  }

  @Test
  void warnModeSkipsConstructionWhenFilteredOut() throws IOException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolViolationBehaviour(ProtocolViolationBehaviour.WARN);

    // Raise the logger level above WARNING so isLoggable() returns false. The
    // failOnDesync call must then skip the factory invocation entirely (the
    // factory returns null here to make a regression observable; a real exception
    // factory in production would never return null).
    pgStreamLogger.setLevel(Level.SEVERE);

    pgStream.failOnDesync(msg -> {
      throw new AssertionError("factory must not be invoked when WARNING is filtered out");
    }, "filtered-out synthetic message");

    assertEquals(0, warningCount(),
        "Filtered-out WARNING must not produce a log record");
  }

  @Test
  void disableModeIsSilent() throws IOException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolViolationBehaviour(ProtocolViolationBehaviour.DISABLE);

    pgStream.failOnDesync(IOException::new, "disable-mode synthetic message");

    assertEquals(0, warningCount(),
        "DISABLE mode must not emit any WARNING records");
    assertFalse(pgStream.isClosed(),
        "DISABLE mode must not poison the stream");
  }

  @Test
  void failModeSupportsPSQLExceptionFactory() throws IOException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolViolationBehaviour(ProtocolViolationBehaviour.FAIL);

    PSQLException thrown = assertThrows(PSQLException.class, () ->
        pgStream.failOnDesync(
            msg -> new PSQLException(msg, PSQLState.PROTOCOL_VIOLATION),
            "auth round-trip cap"));
    assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), thrown.getSQLState());
    assertNotNull(thrown.getMessage());
    assertTrue(thrown.getMessage().contains("auth round-trip cap"));
  }

  @Test
  void parseFromSystemPropertyHandlesAllCases() {
    // CURRENT is cached at class-load time, so the test cannot reload it. Instead the
    // test reaches into the package-private fromSystemProperty() to cover every parse
    // branch directly: unset, empty, whitespace, each defined value (case-insensitive),
    // and an unknown value that must fall back to WARN (the configured default) rather
    // than silently flip the policy to a stricter or more permissive mode on a typo.
    String key = ProtocolViolationBehaviour.SYSTEM_PROPERTY;
    String previous = System.getProperty(key);
    try {
      System.clearProperty(key);
      assertSame(ProtocolViolationBehaviour.WARN, ProtocolViolationBehaviour.fromSystemProperty(),
          "Unset property must select WARN (the default)");

      System.setProperty(key, "");
      assertSame(ProtocolViolationBehaviour.WARN, ProtocolViolationBehaviour.fromSystemProperty(),
          "Empty property must select WARN");

      System.setProperty(key, "   ");
      assertSame(ProtocolViolationBehaviour.WARN, ProtocolViolationBehaviour.fromSystemProperty(),
          "Whitespace-only property must select WARN");

      System.setProperty(key, "fail");
      assertSame(ProtocolViolationBehaviour.FAIL, ProtocolViolationBehaviour.fromSystemProperty());

      System.setProperty(key, "wArN");
      assertSame(ProtocolViolationBehaviour.WARN, ProtocolViolationBehaviour.fromSystemProperty(),
          "Parsing must be case-insensitive");

      System.setProperty(key, " disable ");
      assertSame(ProtocolViolationBehaviour.DISABLE, ProtocolViolationBehaviour.fromSystemProperty(),
          "Surrounding whitespace must be trimmed");

      System.setProperty(key, "bogus");
      assertSame(ProtocolViolationBehaviour.WARN, ProtocolViolationBehaviour.fromSystemProperty(),
          "Unknown value must fall back to WARN, so a typo cannot silently flip the policy");
    } finally {
      if (previous == null) {
        System.clearProperty(key);
      } else {
        System.setProperty(key, previous);
      }
    }
  }

  @Test
  void dataRowNegativeFieldLengthIsUnconditional() throws IOException {
    // DataRow body: msgSize(4) + nf(2) + size_0(4). Layout:
    //   msgSize = 10: minimal envelope holding only the per-field length prefix.
    //   nf      = 1
    //   size_0  = -5: the protocol assigns meaning only to -1 (NULL) and to
    //                 non-negative values. Any other negative leaves the driver
    //                 no way to decode the field; the check is unconditional.
    byte[] data = new byte[]{
        0x00, 0x00, 0x00, 0x0A, // msgSize = 10
        0x00, 0x01,             // nf = 1
        (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFB, // size_0 = -5
    };

    for (ProtocolViolationBehaviour mode : ProtocolViolationBehaviour.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolViolationBehaviour(mode);

      IOException thrown = assertThrows(IOException.class, pgStream::receiveTupleV3,
          "DataRow negative field length must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("negative length"),
          "Thrown message must name the negative-length condition: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolViolationBehaviour.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob "
              + "the user cannot use: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must poison the stream in every mode, including " + mode);
    }
  }

  @Test
  void dataRowFieldOverrunIsUnconditional() throws IOException {
    // DataRow body: msgSize(4) + nf(2) + size_0(4) + payload. Layout:
    //   msgSize = 14: self(4) + nf(2) + size_0(4) + 4 bytes of envelope payload.
    //   nf      = 1
    //   size_0  = 100: claims 100 bytes of field data, but only 4 remain in the
    //                  envelope. This is the exact scenario from issue #4015.
    // The hardening check that catches it (size > remaining) must fire regardless
    // of protocolViolationBehaviour, because no wire-compatible backend can
    // physically fit 100 bytes of field into a 4-byte envelope window.
    byte[] data = new byte[]{
        0x00, 0x00, 0x00, 0x0E, // msgSize = 14
        0x00, 0x01,             // nf = 1
        0x00, 0x00, 0x00, 0x64, // size_0 = 100 (overruns)
        0x00, 0x00, 0x00, 0x00, // 4 bytes of envelope payload (never read)
    };

    for (ProtocolViolationBehaviour mode : ProtocolViolationBehaviour.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolViolationBehaviour(mode);

      IOException thrown = assertThrows(IOException.class, pgStream::receiveTupleV3,
          "DataRow field-overrun must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("exceeds remaining row bytes"),
          "Thrown message must name the field-overrun condition: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolViolationBehaviour.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob "
              + "the user cannot use: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must poison the stream in every mode, including " + mode);
    }
  }

  @Test
  void maxResultBufferOverrunIsUnconditional() throws Exception {
    // readMessageLength reads a 4-byte length and rejects it against maxResultBuffer.
    // Wire bytes: a length of 1000 with maxResultBuffer = 100.
    byte[] data = new byte[]{0x00, 0x00, 0x03, (byte) 0xE8}; // 1000

    for (ProtocolViolationBehaviour mode : ProtocolViolationBehaviour.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolViolationBehaviour(mode);
      pgStream.setMaxResultBuffer("100"); // bytes

      IOException thrown = assertThrows(IOException.class,
          () -> pgStream.readMessageLength("Test", 4),
          "maxResultBuffer overrun must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("exceeds maxResultBuffer"),
          "Thrown message must name the maxResultBuffer overrun: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolViolationBehaviour.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must poison the stream in every mode, including " + mode);
    }
  }

  @Test
  void silenceHintMentionsBothKnobs() {
    String hint = ProtocolViolationBehaviour.appendSilenceHint("base");
    assertTrue(hint.startsWith("base"));
    assertTrue(hint.contains("warn"), () -> "Hint should mention warn mode: " + hint);
    assertTrue(hint.contains("disable"), () -> "Hint should mention disable mode: " + hint);
    assertTrue(hint.contains(ProtocolViolationBehaviour.SYSTEM_PROPERTY),
        () -> "Hint should mention the system property name: " + hint);
    assertTrue(hint.contains("https://github.com/pgjdbc/pgjdbc"),
        () -> "Hint should link to the issue tracker: " + hint);
  }

  private long warningCount() {
    return capture.records.stream().filter(r -> r.getLevel() == Level.WARNING).count();
  }
}
