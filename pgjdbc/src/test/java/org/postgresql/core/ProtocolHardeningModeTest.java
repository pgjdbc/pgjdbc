/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.postgresql.test.util.PgWire.bodyWithLength;
import static org.postgresql.test.util.PgWire.concat;
import static org.postgresql.test.util.PgWire.cstring;
import static org.postgresql.test.util.PgWire.filler;
import static org.postgresql.test.util.PgWire.int1;
import static org.postgresql.test.util.PgWire.int2;
import static org.postgresql.test.util.PgWire.int4;
import static org.postgresql.test.util.PgWire.message;
import static org.postgresql.test.util.PgWire.parameterStatus;
import static org.postgresql.test.util.PgWire.text;

import org.postgresql.test.util.InMemorySocketFactory;
import org.postgresql.util.HostSpec;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Fails when a hardening check reacts to {@link ProtocolHardeningMode} the wrong way.
 *
 * <p>A test here pins three things about one check on {@link PGStream}: whether the check throws,
 * whether it marks the stream broken, and what its message names. A rejection marks the stream
 * broken unless the driver can skip the offending row and read on. Only a check the mode can
 * switch off may name {@link ProtocolHardeningMode#SYSTEM_PROPERTY} in its message; one that fires
 * in every mode, such as an impossible protocol value, a user-configured limit, or a
 * pre-authentication ceiling, must not advertise a knob that would not help.</p>
 */
@Isolated("Tests modify System.properties")
class ProtocolHardeningModeTest {

  /**
   * Builds a {@link PGStream} backed by {@code inputBytes}, ready for read-side tests.
   */
  private static PGStream newStream(byte[] inputBytes) throws IOException {
    return newStream(new InMemorySocketFactory(inputBytes));
  }

  private static PGStream newStream(InMemorySocketFactory socketFactory) throws IOException {
    return new PGStream(socketFactory, new HostSpec("localhost", 1), 0, 8192);
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
  void defaultBehaviourFollowsCurrent() throws IOException {
    PGStream pgStream = newStream(new byte[0]);
    assertSame(ProtocolHardeningMode.CURRENT, pgStream.getProtocolHardeningMode(),
        "Newly constructed PGStream should pick up the JVM-wide ProtocolHardeningMode.CURRENT");
  }

  @Test
  void failModeThrowsAndMarksBroken() throws IOException, PSQLException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    pgStream.setMaxServerTextMessageSize("1024");

    IOException thrown = assertThrows(IOException.class, () ->
        pgStream.checkServerTextMessageSize("ParameterStatus", 4096));

    assertTrue(thrown.getMessage().contains("ParameterStatus"),
        "Thrown exception should name the message that tripped the ceiling");
    assertTrue(thrown.getMessage().contains("exceeds the pgjdbc ceiling"),
        "Thrown exception should say the ceiling is pgjdbc's own: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains("maxServerTextMessageSize"),
        "The message must name the property that raises it: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
        "The message should also name the escape hatch: " + thrown.getMessage());
    assertTrue(pgStream.isClosed(),
        "FAIL mode must mark the stream broken so connection pools discard it");
  }

  @Test
  void disableModeIsSilent() throws IOException, PSQLException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);
    pgStream.setMaxServerTextMessageSize("1024");

    pgStream.checkServerTextMessageSize("ParameterStatus", 4096);

    assertEquals(0, warningCount(),
        "DISABLE mode must not emit any WARNING records");
    assertFalse(pgStream.isClosed(),
        "DISABLE mode must not mark the stream broken");
  }

  @Test
  void configuredCeilingIsHonoured() throws IOException, PSQLException {
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setMaxServerTextMessageSize("128M");

    // Above the default, below the configured ceiling.
    pgStream.checkServerTextMessageSize("NoticeResponse",
        PGStream.DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE + 1);
    assertFalse(pgStream.isClosed(), "A raised ceiling must let the larger message through");

    // And lowering it below the default takes effect too.
    PGStream lowered = newStream(new byte[0]);
    lowered.setMaxServerTextMessageSize("1M");
    assertThrows(IOException.class,
        () -> lowered.checkServerTextMessageSize("NoticeResponse", 2 * 1024 * 1024),
        "A lowered ceiling must reject what the default would have allowed");
  }

  @Test
  void defaultCeilingClearsLibpqParity() throws IOException {
    // libpq exempts ErrorResponse, NoticeResponse and NotificationResponse from its own
    // 30000-byte limit and applies no ceiling of its own, so the default has to clear any
    // notice or error detail a server already sends today.
    PGStream pgStream = newStream(new byte[0]);
    pgStream.checkServerTextMessageSize("NoticeResponse", 40 * 1024 * 1024);
    assertFalse(pgStream.isClosed(), "A 40 MB notice must pass at the default ceiling");
  }

  @Test
  void authenticationCeilingClearsLibpqParity() throws IOException {
    // libpq refuses an AuthenticationRequest or an AuthenticationGSSContinue over 2000 bytes
    // (fe-connect.c), and every payload those carry travels server to client, so a server that
    // needed more than libpq accepts could not authenticate psql either. The large Kerberos
    // tokens go the other way, under the backend's own PG_MAX_AUTH_TOKEN_LENGTH.
    int libpqCeiling = 2000;
    assertTrue(PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE >= 4 * libpqCeiling,
        "The pre-auth ceiling must stay clear of libpq's own " + libpqCeiling + "-byte bound, "
            + "but it is " + PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE);

    PGStream atLibpqCeiling = newStream(int4(libpqCeiling));
    assertEquals(libpqCeiling,
        atLibpqCeiling.readPreAuthMessageLength("AuthenticationGSSContinue", 8,
            PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE),
        "The largest GSS continuation libpq accepts must pass here too");
    assertFalse(atLibpqCeiling.isClosed(),
        "A token within libpq's bound must not break the stream");

    PGStream overCeiling = newStream(int4(PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE + 1));
    assertThrows(IOException.class,
        () -> overCeiling.readPreAuthMessageLength("AuthenticationGSSContinue", 8,
            PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE),
        "A length one byte over the ceiling must be refused");
  }

  @Test
  void preAuthCeilingIsHardInEveryMode() throws IOException {
    // The pre-authentication ceilings answer to the property, not to the mode: the peer has
    // proved nothing yet, so PGStream must apply them even under DISABLE.
    int over = PGStream.DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE + 1;
    byte[] data = int4(over);

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);

      IOException thrown = assertThrows(IOException.class,
          () -> pgStream.readPreAuthMessageLength("ErrorResponse", 5,
              pgStream.getMaxServerTextMessageSize(), "maxServerTextMessageSize"),
          "A pre-auth ceiling must reject in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("pgjdbc ceiling"),
          "The message must name the ceiling as pgjdbc's own rather than as a protocol "
              + "bound: " + thrown.getMessage());
      assertTrue(thrown.getMessage().contains("maxServerTextMessageSize"),
          "The message must name the property that raises it: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "A pre-auth ceiling must not advertise a knob that does not apply to it: "
              + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "A pre-auth ceiling violation must mark the stream broken in every mode, including "
              + mode);
    }
  }

  @Test
  void parseFromSystemPropertyHandlesAllCases() {
    // CURRENT is cached at class-load time, so the test cannot reload it. Instead the
    // test reaches into the package-private fromSystemProperty() to cover every parse
    // branch directly: unset, empty, whitespace, each defined value (case-insensitive),
    // and an unknown value, which fromSystemProperty must resolve to FAIL (the default)
    // rather than silently switching the ceilings off on a typo.
    String key = ProtocolHardeningMode.SYSTEM_PROPERTY;
    String previous = System.getProperty(key);
    try {
      System.clearProperty(key);
      assertSame(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty(),
          "Unset property must select FAIL (the default)");

      System.setProperty(key, "");
      assertSame(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty(),
          "Empty property must select FAIL");

      System.setProperty(key, "   ");
      assertSame(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty(),
          "Whitespace-only property must select FAIL");

      System.setProperty(key, "fAiL");
      assertSame(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty(),
          "Parsing must be case-insensitive");

      System.setProperty(key, " disable ");
      assertSame(ProtocolHardeningMode.DISABLE, ProtocolHardeningMode.fromSystemProperty(),
          "Surrounding whitespace must be trimmed");

      System.setProperty(key, "bogus");
      assertSame(ProtocolHardeningMode.FAIL, ProtocolHardeningMode.fromSystemProperty(),
          "Unknown value must fall back to FAIL, so a typo cannot silently disable them");
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
    // A minimal DataRow envelope: one field, and nothing but its length prefix. The
    // protocol assigns meaning only to -1 (NULL) and to non-negative values, so any other
    // negative leaves the driver no way to decode the field; the check is unconditional.
    byte[] data = bodyWithLength(10, int2(1), int4(-5));

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);

      IOException thrown = assertThrows(IOException.class, pgStream::receiveTupleV3,
          "DataRow negative field length must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("negative length"),
          "Thrown message must name the negative-length condition: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob "
              + "the user cannot use: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must mark the stream broken in every mode, including " + mode);
    }
  }

  @Test
  void dataRowFieldOverrunIsUnconditional() throws IOException {
    // A DataRow whose single field claims 100 bytes of data where only 4 remain in the
    // envelope. This is the exact scenario from issue #4015. The hardening check that
    // catches it (size > remaining) must fire regardless of protocolHardeningMode, because
    // no wire-compatible backend can physically fit 100 bytes of field into a 4-byte
    // envelope window.
    byte[] data = bodyWithLength(14, int2(1), int4(100), filler(4));

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);

      IOException thrown = assertThrows(IOException.class, pgStream::receiveTupleV3,
          "DataRow field-overrun must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("exceeds remaining row bytes"),
          "Thrown message must name the field-overrun condition: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob "
              + "the user cannot use: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must mark the stream broken in every mode, including " + mode);
    }
  }

  @Test
  void maxResultBufferOverrunSkipsRowAndKeepsConnection() throws Exception {
    // readMessageLength reads a 4-byte length and rejects it against maxResultBuffer. Three
    // DataRows: one over the limit, one small enough to pass, and a second over-sized one.
    // The check runs before any of a row's body is read, so the reader can skip the
    // over-sized ones and stay on a message boundary either way.
    byte[] data = concat(
        bodyWithLength(20, int2(0), filler(14)),   // over the limit
        bodyWithLength(6, int2(0)),                // small enough to pass
        bodyWithLength(20, int2(0), filler(14)));  // over the limit again

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);
      pgStream.setMaxResultBuffer("10"); // bytes

      // maxResultBuffer is a user-configured resource limit, not a protocol violation, so
      // it must reach the caller as the SQLException that QueryExecutorImpl's
      // catch (SQLException) hands to the result handler -- not as an IOException that
      // escapes processResults and is reported as "An I/O error occurred while sending to
      // the backend".
      PSQLException thrown = assertThrows(PSQLException.class, pgStream::receiveTupleV3,
          "maxResultBuffer overrun must throw in every mode, including " + mode);
      assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), thrown.getSQLState(),
          "The SQLState must match the one increaseByteCounter raises for the cumulative "
              + "case, so one setting reports itself one way: " + thrown.getMessage());
      assertTrue(thrown.getMessage().contains("maxResultBuffer"),
          "Thrown message must name the maxResultBuffer overrun: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob: " + thrown.getMessage());

      // The over-sized row was skipped rather than half-read, so the connection survives
      // and the next row still parses. An application that retries with a smaller
      // fetchSize does not need a new connection.
      assertFalse(pgStream.isClosed(),
          "A resource limit must not break the connection in " + mode);
      Tuple next = pgStream.receiveTupleV3();
      assertNotNull(next,
          "A row within the limit is returned normally, whether or not an earlier one was "
              + "skipped; mode " + mode);
      assertEquals(0, next.fieldCount(),
          "The reader must be back on a message boundary in " + mode);

      // The second over-sized row is skipped too, but without a second exception: chaining
      // one per rejected row would grow ResultHandlerBase's chain without bound. null is how
      // that is signalled, and QueryExecutorImpl drops it rather than adding a row.
      assertNull(pgStream.receiveTupleV3(),
          "A second over-sized row is skipped in silence; mode " + mode);
      assertFalse(pgStream.isClosed(),
          "Skipping in silence must not break the connection either, in " + mode);
    }
  }

  @Test
  void cStringBudgetOverrunMarksBroken() throws IOException {
    // Declare a tight envelope (msgSize = 12, so body budget = 8 bytes) and feed
    // 9 readable body bytes without a NUL. scanBoundedCStringLength caps the scan
    // budget at 8 (the remaining envelope); VisibleBufferedInputStream.scanCStringLength
    // increments scanned to 9 before the buffer is depleted, hits the budget check,
    // and throws a plain IOException. PGStream must route that through markBroken
    // so the broken flag is set at the throw site, not only after the upstream
    // caller invokes abort().
    //
    // (Eight body bytes are not enough to trigger the budget check: the inner
    // scan loop exits when the buffer is depleted at scanned == 8, and the next
    // readMore call hits EOF first.)
    byte[] data = bodyWithLength(12, text("aaaaaaaaa"));

    PGStream pgStream = newStream(data);
    int len = pgStream.readMessageLength("ParameterStatus", 6);
    assertEquals(12, len);

    IOException thrown = assertThrows(IOException.class, pgStream::receiveString,
        "C-string overrun must throw");
    assertTrue(thrown.getMessage().contains("exceeds remaining budget"),
        "Thrown message must name the budget-overrun condition: " + thrown.getMessage());
    assertTrue(pgStream.isClosed(),
        "C-string overrun must mark the stream broken at the throw site, "
            + "so isClosed() returns true before the upstream caller invokes abort()");
  }

  @Test
  void cStringCeilingRejectsUnderFailAndIsLiftedUnderDisable() throws IOException {
    // A ParameterStatus may declare up to maxServerTextMessageSize, but no single C-string
    // inside it carries bulk data, so the per-string ceiling rejects the string before the
    // envelope does. A GUC value is the one scanned field the server gives no length of its
    // own, so DISABLE has to lift that ceiling back to the envelope the way it lifts the
    // message ceilings.
    int overCeiling = PGStream.MAX_CSTRING_LENGTH + 1;
    byte[] value = new byte[overCeiling];
    Arrays.fill(value, (byte) 'a');
    // Body: the over-long value and its NUL, so the envelope is spent exactly and only the
    // ceiling can reject it.
    byte[] data = bodyWithLength(4 + overCeiling + 1, value, new byte[]{0});

    PGStream failing = newStream(data);
    failing.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    assertEquals(4 + overCeiling + 1, failing.readMessageLength("ParameterStatus", 6));

    IOException thrown = assertThrows(IOException.class, failing::receiveString,
        "A C-string over the ceiling must throw under FAIL");
    assertTrue(thrown.getMessage().contains("pgjdbc ceiling"),
        "Thrown message must name the ceiling as pgjdbc's own rather than as the "
            + "envelope: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains(String.valueOf(PGStream.MAX_CSTRING_LENGTH)),
        "Thrown message must quote the ceiling it enforced: " + thrown.getMessage());
    assertTrue(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
        "A ceiling the mode relaxes must name the escape hatch: " + thrown.getMessage());
    assertTrue(failing.isClosed(),
        "A ceiling violation must mark the stream broken under FAIL");

    PGStream disabled = newStream(data);
    disabled.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);
    assertEquals(4 + overCeiling + 1, disabled.readMessageLength("ParameterStatus", 6));
    assertEquals(overCeiling, disabled.receiveString().length(),
        "DISABLE must lift the ceiling back to the remaining envelope");
    assertFalse(disabled.isClosed(),
        "A string the envelope still allows must not break the stream under DISABLE");
  }

  @Test
  void cStringCeilingClearsEveryFieldThatReachesAScan() throws IOException {
    // The ceiling is pgjdbc's own number, so it has to clear the largest field a conforming
    // backend can send through a C-string scan. The widest of those is a NOTIFY payload.
    int notifyPayloadMaxLength = 8000;
    int nameDataLen = 64;

    assertTrue(PGStream.MAX_CSTRING_LENGTH >= 100 * notifyPayloadMaxLength,
        "The ceiling must stay at least 100x NOTIFY_PAYLOAD_MAX_LENGTH (" + notifyPayloadMaxLength
            + " bytes), so that a fork raising the payload limit still fits, but it is "
            + PGStream.MAX_CSTRING_LENGTH);
    assertTrue(PGStream.MAX_CSTRING_LENGTH >= 1000 * nameDataLen,
        "The ceiling must stay at least 1000x NAMEDATALEN (" + nameDataLen
            + " bytes), so that a fork raising it still fits, but it is "
            + PGStream.MAX_CSTRING_LENGTH);
    assertTrue(PGStream.MAX_CSTRING_LENGTH < PGStream.DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE,
        "A ceiling at or above DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE ("
            + PGStream.DEFAULT_MAX_SERVER_TEXT_MESSAGE_SIZE + " bytes) would bound nothing");
  }

  @Test
  void everyPreAuthenticationEnvelopeBoundsACStringOnItsOwn() {
    // Before authentication the mode reaches nothing, so a C-string there must stay bounded
    // whatever the mode says. That holds only while every ceiling applied before
    // authentication sits at or below the per-string ceiling: the envelope then bounds the
    // string on its own. These are the two such ceilings today, NegotiateProtocolVersion for
    // its option names and AuthenticationRequest for the SASL mechanism list. Raising either
    // past MAX_CSTRING_LENGTH, or adding a third above it, re-opens the gap.
    assertTrue(PGStream.MAX_NEGOTIATE_PROTOCOL_VERSION_SIZE <= PGStream.MAX_CSTRING_LENGTH,
        "NegotiateProtocolVersion may not open an envelope wider than a single C-string may "
            + "be, but its ceiling is " + PGStream.MAX_NEGOTIATE_PROTOCOL_VERSION_SIZE);
    assertTrue(PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE <= PGStream.MAX_CSTRING_LENGTH,
        "AuthenticationRequest may not open an envelope wider than a single C-string may be, "
            + "but its ceiling is " + PGStream.MAX_AUTHENTICATION_MESSAGE_SIZE);
  }

  @Test
  void cStringEofMidScanMarksBroken() throws IOException {
    // Declare msgSize = 100 (so the scan budget is 96 bytes, well above the data
    // we feed) and provide a name that starts without a NUL and then truncates
    // before the budget is hit. VisibleBufferedInputStream.scanCStringLength's
    // readMore returns false on the truncated stream and throws EOFException.
    // PGStream must route that through markBroken too.
    byte[] data = bodyWithLength(100, text("abcd")); // 4 bytes of body, no NUL, then EOF

    PGStream pgStream = newStream(data);
    int len = pgStream.readMessageLength("ParameterStatus", 6);
    assertEquals(100, len);

    assertThrows(EOFException.class, pgStream::receiveString,
        "C-string EOF mid-scan must still throw EOFException, not the plain IOException the "
            + "per-string ceiling branch throws");
    assertTrue(pgStream.isClosed(),
        "C-string EOF mid-scan must mark the stream broken at the throw site");
  }

  @Test
  void cStringBeyondTheEnvelopeIsUnconditional() throws IOException {
    // The envelope is spent exactly, and the reader asks for one more C-string. The scan
    // has no budget left, so honouring it would run past the message end and into the next
    // message's header. cStringBudgetOverrunMarksBroken covers the neighbouring case where
    // a budget still exists but the NUL never arrives; this one covers a budget of zero,
    // which takes a separate branch and produces a separate message.
    // ParameterStatus, msgSize = 8, so the body is the two C-strings and nothing else.
    byte[] data = bodyWithLength(8, cstring("a"), cstring("b"));

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);

      assertEquals(8, pgStream.readMessageLength("ParameterStatus", 6));
      assertEquals("a", pgStream.receiveString());
      assertEquals("b", pgStream.receiveString());

      IOException thrown = assertThrows(IOException.class, pgStream::receiveString,
          "A C-string read past the envelope must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("no remaining envelope budget"),
          "Thrown message must name the exhausted budget: " + thrown.getMessage());
      assertTrue(thrown.getMessage().contains("ParameterStatus"),
          "Thrown message must name the message being parsed: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob "
              + "the user cannot use: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must mark the stream broken in every mode, including " + mode);
    }
  }

  @Test
  void fixedMessageLengthMismatchIsUnconditional() throws IOException {
    // ParseComplete and its fixed-length siblings carry no body, so their length is the
    // only field the driver can check. A length other than the protocol-defined one means
    // the byte the reader took for a message type came from somewhere else, and every
    // following read is against garbage.
    // ParseComplete declares 5 where the protocol fixes it at 4.
    byte[] data = bodyWithLength(5);

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);

      IOException thrown = assertThrows(IOException.class,
          () -> pgStream.readFixedMessageLength("ParseComplete", 4),
          "A fixed-length mismatch must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("ParseComplete"),
          "Thrown message must name the message that carried the wrong length: "
              + thrown.getMessage());
      assertTrue(thrown.getMessage().contains("expected 4"),
          "Thrown message must state the length the protocol fixes: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob "
              + "the user cannot use: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must mark the stream broken in every mode, including " + mode);
    }
  }

  @Test
  void fixedMessageLengthOpensAnEnvelopeOnTheDeclaredLength() throws IOException {
    // The positive half of the check: a matching length starts an envelope, so the
    // boundary tracker accepts the next message type. Without it, a mutation that drops
    // the beginMessage call from readFixedMessageLength would go unnoticed.
    byte[] data = concat(
        message('1'),                 // ParseComplete, no body
        parameterStatus("k", "v"));   // the message that follows it

    PGStream pgStream = newStream(data);
    assertEquals('1', pgStream.receiveMessageType());
    pgStream.readFixedMessageLength("ParseComplete", 4);
    pgStream.endMessage();

    assertEquals('S', pgStream.receiveMessageType(),
        "A fixed-length message read through the bounded API must leave the stream "
            + "on a boundary");
    assertFalse(pgStream.isClosed());
  }

  @Test
  void endMessageEnvelopeMismatchIsUnconditional() throws IOException {
    // A DataRow that declares 12 bytes and carries no field. receiveTupleV3 reads only the
    // 6 bytes of length and field count; endMessage then compares actual against declared
    // and finds 6 unread envelope bytes, the desync signature.
    byte[] data = bodyWithLength(12, int2(0));

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);

      IOException thrown = assertThrows(IOException.class, pgStream::receiveTupleV3,
          "Envelope mismatch must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("unread bytes"),
          "Thrown message must name the envelope-mismatch condition: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "Unconditional-check message must not advertise a silence knob "
              + "the user cannot use: " + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Unconditional check must mark the stream broken in every mode, including " + mode);
    }
  }

  @Test
  void skipAdvancesTheEnvelopePositionByWhatItDiscarded() throws IOException {
    // QueryExecutorImpl.skipMessage discards a message the driver does not act on: it reads the
    // length, calls skip() for the body, then endMessage(). endMessage compares the stream
    // position against the declared envelope, so that pair holds only while skip() advances the
    // position by exactly the number of bytes it discarded. Three routes reach the position
    // counter and each counts separately: bytes already in the buffer, bytes the wrapped stream
    // skips, and the read PGStream.skip falls back to when the wrapped stream skips nothing. A
    // route that counted wrong would leave endMessage silent on a stream sitting mid-message.
    for (boolean refusesToSkip : new boolean[]{false, true}) {
      // The buffer holds 8192 bytes, so a body of 100 is discarded inside it and one of 20000
      // reaches the wrapped stream.
      for (int bodyLength : new int[]{100, 20000}) {
        String label = "bodyLength=" + bodyLength + " refusesToSkip=" + refusesToSkip;
        byte[] data = concat(
            bodyWithLength(4 + bodyLength, filler(bodyLength)),
            int1('Z'));

        PGStream pgStream = newStream(refusesToSkip
            ? InMemorySocketFactory.refusingToSkip(data)
            : new InMemorySocketFactory(data));
        assertEquals(4 + bodyLength, pgStream.readMessageLength("NoticeResponse", 4), label);

        pgStream.skip(bodyLength);
        pgStream.endMessage();
        assertFalse(pgStream.isClosed(),
            label + ": discarding exactly the body must leave the stream usable");
        assertEquals('Z', pgStream.receiveChar(),
            label + ": the next byte must be the one after the message");
      }
    }
  }

  @Test
  void endMessageOverReadIsUnconditional() throws IOException {
    // The mirror image of endMessageEnvelopeMismatchIsUnconditional: here the reader
    // consumes more than the envelope declared. Every existing envelope test covers the
    // under-read direction, where `expected - actual` is positive; over-read used to print
    // that same difference and report "-2 unread bytes", which reads as a driver bug rather
    // than as the desync it is.
    // msgSize = 8, so the body is 4 bytes, and the reader below takes 6 of them.
    byte[] data = bodyWithLength(8, filler(6));

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(data);
      pgStream.setProtocolHardeningMode(mode);

      assertEquals(8, pgStream.readMessageLength("ParameterStatus", 6));
      pgStream.receive(6);

      IOException thrown = assertThrows(IOException.class, pgStream::endMessage,
          "Envelope over-read must throw in every mode, including " + mode);
      assertTrue(thrown.getMessage().contains("past its declared envelope"),
          "Over-read must be named as such rather than reported as a negative unread-byte "
              + "count: " + thrown.getMessage());
      assertTrue(thrown.getMessage().contains("2"),
          "Over-read must report how far past the envelope the reader went: "
              + thrown.getMessage());
      assertTrue(pgStream.isClosed(),
          "Envelope over-read must mark the stream broken in every mode, including " + mode);
    }
  }

  @Test
  void maxResultBufferOverrunTooLargeToSkipClosesConnection() throws Exception {
    // Skipping an over-sized row keeps the connection alive, but only while the amount to
    // discard stays small. One byte past the ceiling would mean pulling that much off the
    // wire per row, for as many rows as the peer sends, so the driver stops rather than
    // paying it. Derived from the constant so the two cannot drift apart.
    int messageSize = PGStream.MAX_RECOVERABLE_SKIP + 7;
    byte[] data = bodyWithLength(messageSize, int2(0));

    PGStream pgStream = newStream(data);
    pgStream.setMaxResultBuffer("10");

    PSQLException thrown = assertThrows(PSQLException.class, pgStream::receiveTupleV3);
    assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), thrown.getSQLState());
    assertTrue(thrown.getMessage().contains("cannot be skipped"),
        "The error must say why the connection was closed rather than the row skipped: "
            + thrown.getMessage());
    assertTrue(pgStream.isClosed(),
        "A row too large to skip must break the connection rather than cost unbounded "
            + "wire traffic");
  }

  @Test
  void copyDataUnderTheCeilingIsSilentInEveryMode() throws Exception {
    // Every COPY row and every replication message goes through this check, so a ceiling that
    // fires below its own limit would break COPY outright under FAIL. Deliberately covers the
    // ordinary sizes, not the boundary: copyDataCapIsSoftByDefaultAndHardWhenConfigured passes
    // lengths that are over the limit, which is exactly how a missing comparison slipped through.
    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(new byte[0]);
      pgStream.setProtocolHardeningMode(mode);
      capture.records.clear();

      for (int msgLen : new int[]{5, 6, 1024, PGStream.DEFAULT_MAX_COPY_DATA_SIZE}) {
        pgStream.checkCopyDataSize(msgLen);
      }

      assertEquals(0, warningCount(),
          "A CopyData within the ceiling must not be logged, in " + mode);
      assertFalse(pgStream.isClosed(),
          "A CopyData within the ceiling must not break the connection, in " + mode);
    }

    // Same for a configured limit.
    PGStream pgStream = newStream(new byte[0]);
    pgStream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    pgStream.setMaxCopyDataSize("1M");
    pgStream.checkCopyDataSize(1000000);
    assertFalse(pgStream.isClosed(),
        "A CopyData exactly at a configured maxCopyDataSize must pass");
  }

  @Test
  void copyDataCapIsSoftByDefaultAndHardWhenConfigured() throws Exception {
    int over = PGStream.DEFAULT_MAX_COPY_DATA_SIZE + 1;

    // Unset property: the built-in ceiling applies, and DISABLE reads on.
    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(new byte[0]);
      pgStream.setProtocolHardeningMode(mode);

      if (mode == ProtocolHardeningMode.FAIL) {
        // A PSQLException rather than an IOException, so readFromCopy reports the ceiling
        // by name instead of rewriting it into "Database connection failed when reading
        // from copy".
        PSQLException thrown = assertThrows(PSQLException.class,
            () -> pgStream.checkCopyDataSize(over));
        assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), thrown.getSQLState());
        assertTrue(thrown.getMessage().contains("maxCopyDataSize"),
            "The message must name the property that raises the ceiling: "
                + thrown.getMessage());
        assertTrue(pgStream.isClosed(), "FAIL must mark the stream broken");
        continue;
      }

      pgStream.checkCopyDataSize(over);
      assertFalse(pgStream.isClosed(), "DISABLE must read on past the built-in ceiling");
    }

    // Configured property: the number is the user's own, so no mode overrides it. It also
    // surfaces as a SQLException, so readFromCopy reports the limit by name instead of
    // wrapping it in "Database connection failed when reading from copy".
    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream = newStream(new byte[0]);
      pgStream.setProtocolHardeningMode(mode);
      pgStream.setMaxCopyDataSize("1M");

      PSQLException thrown = assertThrows(PSQLException.class,
          () -> pgStream.checkCopyDataSize(2 * 1024 * 1024),
          "A configured maxCopyDataSize must reject in every mode, including " + mode);
      assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), thrown.getSQLState());
      assertTrue(thrown.getMessage().contains("maxCopyDataSize"),
          "The error must name the setting that caused it: " + thrown.getMessage());
      assertFalse(thrown.getMessage().contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
          "A user-configured limit must not advertise a knob that does not override it: "
              + thrown.getMessage());
      assertTrue(pgStream.isClosed());
    }
  }

  @Test
  void maxResultBufferDoesNotApplyToNonResultMessages() throws IOException, PSQLException {
    // maxResultBuffer is documented as the size of the result buffer, so PGStream must apply
    // it to DataRow and nothing else -- which is also what earlier driver versions did.
    // Applying it to every message turned a small maxResultBuffer into a connection that
    // could not even be established: the GSS handshake token alone may be 65532 bytes, and
    // setMaxResultBuffer runs before the handshake does.
    byte[] data = int4(1000);

    PGStream pgStream = newStream(data);
    pgStream.setMaxResultBuffer("100"); // bytes

    assertEquals(1000, pgStream.readMessageLength("ParameterStatus", 6),
        "maxResultBuffer must not bound a message that does not fill the result buffer");
    assertFalse(pgStream.isClosed(), "The stream must stay usable");
  }

  @Test
  void untrackedLengthStartsNoEnvelope() throws IOException {
    // The GSS handshake token length counts only the payload, so it describes no envelope
    // the driver can track. readUntrackedLength must therefore leave no envelope behind:
    // otherwise the next bounded C-string read inherits a budget computed from the wrong
    // base and rejects (or accepts) the wrong number of bytes.
    byte[] data = bodyWithLength(10,  // ParameterStatus whose envelope ends at position 10
        cstring("k"),                 // positions 4..5
        int4(4),                      // GSS token length = 4 (payload only), positions 6..9
        cstring("xy"));               // past the abandoned envelope, positions 10..12

    PGStream pgStream = newStream(data);
    assertEquals(10, pgStream.readMessageLength("ParameterStatus", 6));
    assertEquals("k", pgStream.receiveString());
    // Abandon the envelope mid-message, the way the handshake path does. The read lands
    // exactly on the old envelope's end position, so a tracker left in place would give the
    // next C-string a budget of zero and reject it.
    assertEquals(4, pgStream.readUntrackedLength("GSSEncryptionHandshakeToken", 0, 65532));
    assertEquals("xy", pgStream.receiveString(),
        "A read after readUntrackedLength must not inherit the abandoned envelope's budget");
    pgStream.endMessage();
    assertFalse(pgStream.isClosed(),
        "readUntrackedLength must not leave a stale envelope for endMessage to trip on");
  }

  /**
   * Two back-to-back ParameterStatus messages, each declaring {@code k=v}. Used by the
   * message-boundary tests, which differ only in how much of the first message they read
   * before asking for the second message's type.
   */
  private static byte[] twoParameterStatusMessages() {
    return concat(parameterStatus("k", "v"), parameterStatus("k", "v"));
  }

  @Test
  void messageBoundaryAcceptsAFullyBracketedRead() throws IOException {
    // The baseline the rejecting message-boundary cases are measured against: declare the
    // envelope, read the body, close the envelope, and the next message type is accepted.
    PGStream pgStream = newStream(twoParameterStatusMessages());

    assertEquals('S', pgStream.receiveMessageType());
    assertEquals(8, pgStream.readMessageLength("ParameterStatus", 6));
    assertEquals("k", pgStream.receiveString());
    assertEquals("v", pgStream.receiveString());
    pgStream.endMessage();

    assertEquals('S', pgStream.receiveMessageType(),
        "A message read through the bounded API must leave the stream on a boundary");
    assertFalse(pgStream.isClosed());
  }

  @Test
  void messageBoundaryRejectsAnInterruptedBody() throws IOException {
    // What a connection failure mid-message looks like from the next query's point of
    // view: two body bytes never arrived, so the bytes that follow are body rather than a
    // message header. Before this check the driver read on and served the caller garbage.
    PGStream pgStream = newStream(twoParameterStatusMessages());

    assertEquals('S', pgStream.receiveMessageType());
    assertEquals(8, pgStream.readMessageLength("ParameterStatus", 6));
    assertEquals("k", pgStream.receiveString());

    IOException e = assertThrows(IOException.class, pgStream::receiveMessageType);
    assertTrue(e.getMessage().contains("2 bytes of its body unread"),
        "Expected the count of unread body bytes, got: " + e.getMessage());
    assertTrue(e.getMessage().contains("ParameterStatus"),
        "Expected the interrupted message to be named, got: " + e.getMessage());
    assertTrue(pgStream.isClosed(),
        "A stream that is no longer on a message boundary must not be reused");
  }

  @Test
  void messageBoundaryRejectsAForgottenEndMessage() throws IOException {
    // A reader that consumes its whole body but skips endMessage leaves the envelope open.
    // Nothing is wrong with the wire here, so the message says so: it is a driver defect,
    // and it surfaces on the very next message rather than silently much later.
    PGStream pgStream = newStream(twoParameterStatusMessages());

    assertEquals('S', pgStream.receiveMessageType());
    assertEquals(8, pgStream.readMessageLength("ParameterStatus", 6));
    assertEquals("k", pgStream.receiveString());
    assertEquals("v", pgStream.receiveString());

    IOException e = assertThrows(IOException.class, pgStream::receiveMessageType);
    assertTrue(e.getMessage().contains("without a closing endMessage call"),
        "Expected the missing endMessage to be named, got: " + e.getMessage());
    assertTrue(pgStream.isClosed());
  }

  @Test
  void messageBoundaryRejectsALengthReadOutsideTheApi() throws IOException {
    // The regression this check exists for: a new message reader that takes its length
    // from a raw receiveInteger4 gets neither a bound on the length nor an envelope, which
    // is how the stream desyncs (issue #4015). No test of that reader is needed -- any test
    // that drives the message at all now fails on the following message type.
    PGStream pgStream = newStream(twoParameterStatusMessages());

    assertEquals('S', pgStream.receiveMessageType());
    assertEquals(8, pgStream.receiveInteger4());
    assertEquals("k", pgStream.receiveString());
    assertEquals("v", pgStream.receiveString());

    IOException e = assertThrows(IOException.class, pgStream::receiveMessageType);
    assertTrue(e.getMessage().contains("9 bytes away"),
        "Expected the distance from the boundary, got: " + e.getMessage());
    assertTrue(e.getMessage().contains("readMessageLength"),
        "Expected the message to name the API to use, got: " + e.getMessage());
    assertTrue(pgStream.isClosed());
  }

  @Test
  void markMessageBoundaryCoversTheUnframedHandshake() throws IOException {
    // The SSL and GSS encryption negotiations answer with a bare byte or with a length
    // that counts only the payload, so neither leaves an envelope for endMessage to close.
    // markMessageBoundary is how those paths declare that message framing resumes.
    byte[] data = concat(
        int1('N'),                    // SSLRequest refusal, unframed
        parameterStatus("k", "v"));   // framed dialogue resumes

    PGStream pgStream = newStream(data);
    assertEquals('N', pgStream.receiveChar());
    pgStream.markMessageBoundary();

    assertEquals('S', pgStream.receiveMessageType(),
        "markMessageBoundary must let the framed dialogue resume after an unframed reply");
    assertFalse(pgStream.isClosed());
  }

  @Test
  void replacingTheInputStreamResetsTheEnvelope() throws IOException {
    // changeSocket installs a VisibleBufferedInputStream whose byte counter restarts at
    // zero, so an envelope endpoint captured against the previous stream points at an
    // absolute position that no longer means anything. PGStream must not let a C-string read
    // after the swap inherit that endpoint.
    // msgSize = 100, so the envelope endpoint sits at position 100.
    byte[] data = bodyWithLength(100, cstring("abc"));

    PGStream pgStream = newStream(data);
    assertEquals(100, pgStream.readMessageLength("ParameterStatus", 6));
    pgStream.changeSocket(new InMemorySocketFactory(data).createSocket());

    // Envelope gone: endMessage has nothing to verify and must not report a mismatch
    // against the old stream's position.
    pgStream.endMessage();
    assertFalse(pgStream.isClosed(),
        "changeSocket must reset the envelope tracker rather than leave a stale endpoint");
  }

  @Test
  void disableContinuesReadingPastTheCeiling() throws IOException, PSQLException {
    // ParameterStatus carrying `k\0v\0`, but with a declared length just over a lowered
    // ceiling. PGStream must reject it under FAIL and read the message under DISABLE.
    int ceiling = 1024 * 1024;
    int declaredLen = ceiling + 1;

    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      PGStream pgStream =
          newStream(bodyWithLength(declaredLen, cstring("k"), cstring("v")));
      pgStream.setProtocolHardeningMode(mode);
      pgStream.setMaxServerTextMessageSize(String.valueOf(ceiling));

      int len = pgStream.readMessageLength("ParameterStatus", 6);
      assertEquals(declaredLen, len);

      if (mode == ProtocolHardeningMode.FAIL) {
        IOException thrown = assertThrows(IOException.class,
            () -> pgStream.checkServerTextMessageSize("ParameterStatus", len),
            "FAIL must reject a ceiling violation");
        assertTrue(thrown.getMessage().contains("maxServerTextMessageSize"),
            "The message must name the property that raises it: " + thrown.getMessage());
        assertTrue(pgStream.isClosed(), "FAIL must mark the stream broken");
        continue;
      }

      pgStream.checkServerTextMessageSize("ParameterStatus", len);
      assertFalse(pgStream.isClosed(), "DISABLE must leave the stream usable");
      assertEquals("k", pgStream.receiveString(), "Reading must continue in " + mode);
      assertEquals("v", pgStream.receiveString(), "Reading must continue in " + mode);
    }
  }

  @Test
  void silenceHintNamesThePropertyAndTheEscapeHatch() {
    String hint = ProtocolHardeningMode.appendSilenceHint("base", "maxServerTextMessageSize");
    assertTrue(hint.startsWith("base"));
    assertTrue(hint.contains("maxServerTextMessageSize"),
        () -> "Hint should name the property that raises the ceiling: " + hint);
    assertTrue(hint.contains("disable"), () -> "Hint should mention disable mode: " + hint);
    assertTrue(hint.contains(ProtocolHardeningMode.SYSTEM_PROPERTY),
        () -> "Hint should mention the system property name: " + hint);
    assertTrue(hint.contains(ProtocolHardeningMode.ISSUE_TRACKER_URL),
        () -> "Hint should link to the issue tracker: " + hint);
  }

  private long warningCount() {
    return capture.records.stream().filter(r -> r.getLevel() == Level.WARNING).count();
  }
}
