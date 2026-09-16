/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.v3;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.core.PGStream;
import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.FakeSocketFactory;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLState;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Every message the driver reads before authentication completes is read to exactly its declared
 * length, within the limit that applies to it, and a violation fails the connection attempt and
 * closes the socket.
 *
 * <p>The limits are 8008 bytes for AuthenticationRequest, 30000 bytes for ErrorResponse, 1 MiB
 * (1048576 bytes) for NegotiateProtocolVersion, and 64 messages for the whole authentication
 * exchange. A NegotiateProtocolVersion option count must not exceed the number of
 * parameters the startup packet carried, and must fit in the bytes left in its message. The limits
 * are applied to the stream the connection continues on after SSL and GSS negotiation, including a
 * stream opened by reconnecting.
 * These limits apply in every {@code pgjdbc.protocolHardeningMode}, so every test here passes
 * whichever mode the JVM runs in, and CI runs the suite in both.</p>
 *
 * <p>Each test serves scripted backend bytes through {@link FakeSocketFactory}, so no server
 * is involved. A script that the driver reads in sync ends in an ErrorResponse with SQLState
 * {@value #SERVER_SQL_STATE}, and a connection attempt that fails with that SQLState therefore
 * read every message before it at its declared length. A length failure reaches the caller with
 * the {@link IOException} as its cause, as SQLState 08001, or as SQLState 08004 inside the
 * AuthenticationSASL mechanism list. The
 * NegotiateProtocolVersion option-count checks and the authentication message limit throw
 * SQLState 08P01 directly.</p>
 */
public class ConnectionFactoryImplPreAuthMessageTest {
  private static final String SERVER_SQL_STATE = "28P01";

  private static final int AUTH_REQ_OK = 0;
  private static final int AUTH_REQ_PASSWORD = 3;
  private static final int AUTH_REQ_MD5 = 5;
  private static final int AUTH_REQ_SASL = 10;

  // AuthenticationRequest

  @Test
  void anAuthenticationRequestOf8008BytesIsRead() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .raw(saslWithLength(8008))
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertEquals(SERVER_SQL_STATE, e.getSQLState(), "SQLState");
  }

  @Test
  void anAuthenticationRequestOf8009BytesIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .raw(saslWithLength(8009))
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes applied before authentication. This limit cannot be relaxed.",
                "AuthenticationRequest", "8009", "8008"),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void anAuthenticationRequestShorterThanItsTypeCodeIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('R').int4(7).int4(AUTH_REQ_OK)
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "AuthenticationRequest", "7", "8", String.valueOf(PGStream.MAX_MESSAGE_SIZE)),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void anAuthenticationOkOfItsExactLengthIsRead() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('R').int4(8).int4(AUTH_REQ_OK)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertEquals(SERVER_SQL_STATE, e.getSQLState(), "SQLState");
  }

  @Test
  void anAuthenticationOkWithABodyByteLeftUnreadIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('R').int4(9).int4(AUTH_REQ_OK).int1(0)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has {1} unread bytes.",
                "AuthenticationRequest", "1"),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  /**
   * An AuthenticationMD5Password whose length leaves out the 4-byte salt makes the driver read the
   * salt past the end of the message.
   */
  @Test
  void anMd5RequestWhoseLengthLeavesOutTheSaltIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('R').int4(8).int4(AUTH_REQ_MD5).int4(0x01020304)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message was read {1} bytes past its declared length.",
                "AuthenticationRequest", "4"),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  // AuthenticationSASL, read by ScramAuthenticator

  /**
   * The mechanism list ends the AuthenticationSASL message, so a byte after the list terminator
   * fails the connection before the driver answers with SASLInitialResponse: nothing but the
   * startup packet is written to the socket.
   */
  @Test
  void aSaslMechanismListFollowedByAnExtraByteIsRefusedBeforeTheDriverResponds()
      throws SQLException {
    byte[] mechanisms = mechanismList("SCRAM-SHA-256");
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('R').int4(4 + 4 + mechanisms.length + 1).int4(AUTH_REQ_SASL)
        .raw(mechanisms).int1('x')
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    FakeSocket socket = server.onlySocket();
    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr("Invalid SCRAM client initialization"), e.getMessage()),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has {1} unread bytes.", "AuthenticationRequest", "1"),
            ioCause(e).getMessage()),
        () -> assertEquals(startupPacketLength(socket), socket.written().length,
            "bytes written: the startup packet only"),
        () -> server.assertEverySocketBroken());
  }

  /**
   * A body that holds only the list terminator advertises no mechanism, rather than one mechanism
   * with an empty name.
   */
  @Test
  void aSaslMessageWithAnEmptyMechanismListIsRejected() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('R').int4(4 + 4 + 1).int4(AUTH_REQ_SASL).int1(0)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_REJECTED.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr("Received AuthenticationSASL message with 0 mechanisms!"),
            e.getMessage()));
  }

  // NegotiateProtocolVersion

  @Test
  void aNegotiateProtocolVersionShorterThanItsFixedFieldsIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('v').int4(11).int4(3 << 16).int4(0)
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "NegotiateProtocolVersion", "11", "12",
                String.valueOf(PGStream.MAX_MESSAGE_SIZE)),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void aNegotiateProtocolVersionWithABodyByteLeftUnreadIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('v').int4(13).int4(3 << 16).int4(0).int1(0)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has {1} unread bytes.",
                "NegotiateProtocolVersion", "1"),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void aNegativeOptionCountIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('v').int4(12).int4(3 << 16).int4(-1)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. NegotiateProtocolVersion reports a negative option count of {0}.",
                "-1"),
            e.getMessage()),
        () -> server.assertEverySocketBroken());
  }

  /**
   * Every option name is at least its NUL terminator, so a two-byte option area holds at most two
   * options. Three are refused from the count alone; two are read, and the driver then reports
   * them as unrecognised options.
   */
  @Test
  void anOptionCountLargerThanTheBytesLeftIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('v').int4(12 + 2).int4(3 << 16).int4(3).int1(0).int1(0)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. NegotiateProtocolVersion reports {0} unrecognised options, but only {1} bytes remain in the message body.",
                "3", "2"),
            e.getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void anOptionCountEqualToTheBytesLeftIsReadAsUnrecognisedOptions() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('v').int4(12 + 2).int4(3 << 16).int4(2).int1(0).int1(0)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr("Protocol error, received invalid options: {0}", ","), e.getMessage()),
        () -> server.assertEverySocketBroken());
  }

  /**
   * The message holds one option whose name fills the body, so the driver reads all 1048576 bytes
   * and reports that name in full.
   */
  @Test
  void aNegotiateProtocolVersionOf1048576BytesIsRead() throws SQLException {
    char[] name = new char[1048576 - 12 - 1];
    Arrays.fill(name, 'o');
    ScriptedServer server = new ScriptedServer(
        negotiateProtocolVersion(1, Collections.singletonList(new String(name))));

    SQLException e = server.connectAndFail(new Properties());

    String expected = GT.tr("Protocol error, received invalid options: {0}", new String(name));
    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(expected.length(), e.getMessage().length(),
            "length of the error message"),
        () -> assertEquals(expected, e.getMessage(),
            "the localized text with the whole option name"));
  }

  /** The limit cannot be relaxed, so it holds under {@code pgjdbc.protocolHardeningMode=disable}. */
  @Test
  void aNegotiateProtocolVersionOf1048577BytesIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('v').int4(1048577).int4(3 << 16).int4(0)
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes applied before authentication. This limit cannot be relaxed.",
                "NegotiateProtocolVersion", "1048577", "1048576"),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void anOptionCountEqualToTheStartupParameterCountIsReadAsInvalidOptions() throws SQLException {
    int sent = startupParameterCount();
    List<String> names = new ArrayList<>();
    for (int i = 0; i < sent; i++) {
      names.add("option" + i);
    }
    ScriptedServer server = new ScriptedServer(negotiateProtocolVersion(sent, names));

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr("Protocol error, received invalid options: {0}", String.join(",", names)), e.getMessage()),
        () -> server.assertEverySocketBroken());
  }

  /**
   * The option names are present and fit in the body, so only the startup parameter count
   * refuses them.
   */
  @Test
  void anOptionCountAboveTheStartupParameterCountIsRefused() throws SQLException {
    int sent = startupParameterCount();
    List<String> names = new ArrayList<>();
    for (int i = 0; i <= sent; i++) {
      names.add("option" + i);
    }
    ScriptedServer server = new ScriptedServer(negotiateProtocolVersion(sent + 1, names));

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. NegotiateProtocolVersion reports {0} unrecognised options, but the startup packet carried only {1}.",
                String.valueOf(sent + 1), String.valueOf(sent)),
            e.getMessage()),
        () -> server.assertEverySocketBroken());
  }

  /**
   * The message declares 1048576 bytes and 1048564 options, one per body byte, but the script ends
   * after the option count. A driver that read an option name would reach end of stream and fail
   * with SQLState 08001 instead.
   */
  @Test
  void aHugeOptionCountIsRefusedBeforeAnyOptionNameIsRead() throws SQLException {
    int sent = startupParameterCount();
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('v').int4(1048576).int4(3 << 16).int4(1048564)
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. NegotiateProtocolVersion reports {0} unrecognised options, but the startup packet carried only {1}.",
                "1048564", String.valueOf(sent)),
            e.getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void invalidOptionsAreListedByNameInTheOrderReceived() throws SQLException {
    assertTrue(startupParameterCount() >= 3, "the startup packet carries at least 3 parameters");
    ScriptedServer server = new ScriptedServer(
        negotiateProtocolVersion(3, Arrays.asList("gamma", "alpha", "beta")));

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr("Protocol error, received invalid options: {0}", "gamma,alpha,beta"), e.getMessage()));
  }

  // The authentication message limit

  /**
   * NegotiateProtocolVersion with no options keeps the authentication loop going without
   * completing authentication, so a run of them counts messages toward the limit of 64.
   */
  @Test
  void theSixtyFourthAuthenticationMessageIsRead() throws SQLException {
    Wire wire = new Wire();
    for (int i = 0; i < 63; i++) {
      wire.raw(negotiateProtocolVersion(0));
    }
    ScriptedServer server = new ScriptedServer(
        wire.raw(errorResponse(SERVER_SQL_STATE, 100)).toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertEquals(SERVER_SQL_STATE, e.getSQLState(), "SQLState");
  }

  @Test
  void theSixtyFifthAuthenticationMessageIsRefused() throws SQLException {
    Wire wire = new Wire();
    for (int i = 0; i < 64; i++) {
      wire.raw(negotiateProtocolVersion(0));
    }
    ScriptedServer server = new ScriptedServer(
        wire.raw(errorResponse(SERVER_SQL_STATE, 100)).toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. Authentication did not complete within {0} authentication messages.",
                "64"),
            e.getMessage()),
        () -> server.assertEverySocketBroken());
  }

  /**
   * A message the driver answers still counts toward the limit: a server that repeats
   * AuthenticationCleartextPassword gets 64 password messages and then a refused connection.
   */
  @Test
  void repeatedPasswordRequestsAreAnsweredSixtyFourTimesAndThenRefused() throws SQLException {
    Wire wire = new Wire();
    for (int i = 0; i < 65; i++) {
      wire.int1('R').int4(8).int4(AUTH_REQ_PASSWORD);
    }
    ScriptedServer server = new ScriptedServer(
        wire.raw(errorResponse(SERVER_SQL_STATE, 100)).toBytes());

    SQLException e = server.connectAndFail(new Properties());

    FakeSocket socket = server.onlySocket();
    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. Authentication did not complete within {0} authentication messages.",
                "64"),
            e.getMessage()),
        () -> assertEquals(64, passwordMessagesWritten(socket), "password messages written"),
        () -> server.assertEverySocketBroken());
  }

  // ErrorResponse before authentication

  /** A lower {@code maxServerTextMessageSize} does not lower the limit. */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"100"})
  void anErrorResponseOf30000BytesIsRead(@Nullable String textMessageLimit) throws SQLException {
    ScriptedServer server = new ScriptedServer(errorResponse(SERVER_SQL_STATE, 30000));

    SQLException e = server.connectAndFail(maxServerTextMessageSize(textMessageLimit));

    assertEquals(SERVER_SQL_STATE, e.getSQLState(), "SQLState");
  }

  /**
   * The server sends the length field alone, so a driver that went on to read the body would fail
   * on end of stream with a different message. A higher {@code maxServerTextMessageSize} does not
   * raise the limit.
   */
  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"64M"})
  void anErrorResponseOf30001BytesIsRefused(@Nullable String textMessageLimit)
      throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('E').int4(30001)
        .toBytes());

    SQLException e = server.connectAndFail(maxServerTextMessageSize(textMessageLimit));

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(preAuthLimitMessage("ErrorResponse", 30001, 30000),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void anErrorResponseShorterThanItsTerminatorIsRefused() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('E').int4(4)
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_UNABLE_TO_CONNECT.getState(), e.getSQLState(),
            "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).",
                "ErrorResponse", "4", "5", String.valueOf(PGStream.MAX_MESSAGE_SIZE)),
            ioCause(e).getMessage()),
        () -> server.assertEverySocketBroken());
  }

  @Test
  void anUnexpectedMessageTypeBreaksTheConnection() throws SQLException {
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('Z').int4(5).int1('I')
        .toBytes());

    SQLException e = server.connectAndFail(new Properties());

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> server.assertEverySocketBroken());
  }

  // The message boundary after encryption negotiation

  /**
   * The {@code N} that refuses the SSLRequest or the GSSENCRequest is one byte outside any
   * message, and the authentication exchange starts on the message boundary after it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"sslmode", "gssEncMode"})
  void theAuthenticationExchangeIsReadAfterARefusedEncryptionRequest(String negotiation)
      throws SQLException {
    Properties props = new Properties();
    props.setProperty(negotiation, "prefer");
    ScriptedServer server = new ScriptedServer(new Wire()
        .int1('N')
        .raw(errorResponse(SERVER_SQL_STATE, 100))
        .toBytes());

    SQLException e = server.connectAndFail(props);

    assertAll(
        () -> assertEquals(SERVER_SQL_STATE, e.getSQLState(), "SQLState"),
        () -> assertEquals(1, server.sockets.size(), "sockets opened"));
  }

  // Read limits after encryption negotiation

  /**
   * A server that answers the SSLRequest or the GSSENCRequest with {@code E} makes the driver
   * close the socket and connect again. The ErrorResponse limit must hold on the second socket,
   * which is served an ErrorResponse one byte over it.
   */
  @ParameterizedTest
  @ValueSource(strings = {"sslmode", "gssEncMode"})
  void theErrorResponseLimitAppliesAfterNegotiationReconnects(String negotiation)
      throws SQLException {
    Properties props = new Properties();
    props.setProperty(negotiation, "prefer");
    ScriptedServer server = new ScriptedServer(
        new Wire().int1('E').toBytes(),
        new Wire().int1('E').int4(30001).toBytes());

    SQLException e = server.connectAndFail(props);

    assertAll(
        () -> assertEquals(2, server.sockets.size(), "sockets opened"),
        () -> assertEquals(preAuthLimitMessage("ErrorResponse", 30001, 30000),
            ioCause(e).getMessage()),
        () -> assertTrue(server.sockets.get(0).closed, "first socket closed"),
        () -> assertTrue(server.sockets.get(1).closed, "second socket closed"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"sslmode", "gssEncMode"})
  void aServerErrorAtTheLimitIsReadAfterNegotiationReconnects(String negotiation)
      throws SQLException {
    Properties props = new Properties();
    props.setProperty(negotiation, "prefer");
    ScriptedServer server = new ScriptedServer(
        new Wire().int1('E').toBytes(),
        errorResponse(SERVER_SQL_STATE, 30000));

    SQLException e = server.connectAndFail(props);

    assertAll(
        () -> assertEquals(2, server.sockets.size(), "sockets opened"),
        () -> assertEquals(SERVER_SQL_STATE, e.getSQLState(), "SQLState"));
  }

  // Helpers

  private static String preAuthLimitMessage(String messageName, int length, int limit) {
    return GT.tr(
        "Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes applied before authentication. This limit cannot be relaxed.",
        messageName, String.valueOf(length), String.valueOf(limit));
  }

  private static IOException ioCause(SQLException e) {
    Throwable cause = e.getCause();
    assertSame(IOException.class, cause == null ? null : cause.getClass(),
        () -> "cause of " + e);
    return (IOException) cause;
  }

  private static Properties maxServerTextMessageSize(@Nullable String value) {
    Properties props = new Properties();
    if (value != null) {
      props.setProperty("maxServerTextMessageSize", value);
    }
    return props;
  }

  /** Reads the length field that opens the startup packet, the first bytes the driver writes. */
  private static int startupPacketLength(FakeSocket socket) {
    byte[] out = socket.written();
    assertTrue(out.length >= 4, "the driver wrote a startup packet");
    return (out[0] & 0xff) << 24 | (out[1] & 0xff) << 16 | (out[2] & 0xff) << 8 | (out[3] & 0xff);
  }

  /**
   * Counts the password messages ({@code p}) the driver wrote after the startup packet, walking
   * the written bytes message by message.
   */
  private static int passwordMessagesWritten(FakeSocket socket) {
    byte[] out = socket.written();
    int count = 0;
    int pos = startupPacketLength(socket);
    while (pos + 5 <= out.length) {
      int type = out[pos];
      int length = (out[pos + 1] & 0xff) << 24 | (out[pos + 2] & 0xff) << 16
          | (out[pos + 3] & 0xff) << 8 | (out[pos + 4] & 0xff);
      final int offset = pos;
      assertEquals('p', type, () -> "message type at offset " + offset);
      count++;
      pos += 1 + length;
    }
    return count;
  }

  /** Builds a list of NUL-terminated mechanism names followed by the list terminator. */
  private static byte[] mechanismList(String... names) {
    Wire wire = new Wire();
    for (String name : names) {
      wire.raw(name.getBytes(StandardCharsets.US_ASCII)).int1(0);
    }
    return wire.int1(0).toBytes();
  }

  /**
   * Builds an AuthenticationSASL message whose length field is {@code length}, offering
   * SCRAM-SHA-256 and one unknown mechanism whose name pads the message to that length.
   */
  private static byte[] saslWithLength(int length) {
    int fixed = 4 + 4 + mechanismList("SCRAM-SHA-256", "").length;
    char[] padding = new char[length - fixed];
    Arrays.fill(padding, 'X');
    byte[] mechanisms = mechanismList("SCRAM-SHA-256", new String(padding));
    return new Wire().int1('R').int4(length).int4(AUTH_REQ_SASL).raw(mechanisms).toBytes();
  }

  private static byte[] negotiateProtocolVersion(int optionCount) {
    return new Wire().int1('v').int4(12).int4(3 << 16).int4(optionCount).toBytes();
  }

  /**
   * Builds a NegotiateProtocolVersion that declares {@code optionCount} options and carries
   * {@code names} as NUL-terminated option names, with a length field that covers exactly them.
   */
  private static byte[] negotiateProtocolVersion(int optionCount, List<String> names) {
    Wire options = new Wire();
    for (String name : names) {
      options.raw(name.getBytes(StandardCharsets.US_ASCII)).int1(0);
    }
    byte[] optionBytes = options.toBytes();
    return new Wire()
        .int1('v').int4(12 + optionBytes.length).int4(3 << 16).int4(optionCount)
        .raw(optionBytes)
        .toBytes();
  }

  /**
   * Counts the name and value pairs in the startup packet the driver sends with no extra
   * connection properties, read off a connection attempt that the server refuses at once.
   */
  private static int startupParameterCount() throws SQLException {
    ScriptedServer probe = new ScriptedServer(errorResponse(SERVER_SQL_STATE, 100));
    probe.connectAndFail(new Properties());
    byte[] out = probe.onlySocket().written();
    // 4 bytes of length and 4 of protocol version precede the pairs; a lone NUL ends them.
    int pos = 8;
    int count = 0;
    while (out[pos] != 0) {
      for (int field = 0; field < 2; field++) {
        while (out[pos] != 0) {
          pos++;
        }
        pos++;
      }
      count++;
    }
    return count;
  }

  /**
   * Builds an ErrorResponse whose length field is {@code length}, with severity FATAL, the given
   * SQLState, and a message text that pads it to that length.
   */
  private static byte[] errorResponse(String sqlState, int length) {
    int fixed = 4 + (1 + 6) + (1 + sqlState.length() + 1) + (1 + 1) + 1;
    char[] text = new char[length - fixed];
    Arrays.fill(text, 'm');
    return new Wire()
        .int1('E').int4(length)
        .int1('S').raw("FATAL".getBytes(StandardCharsets.US_ASCII)).int1(0)
        .int1('C').raw(sqlState.getBytes(StandardCharsets.US_ASCII)).int1(0)
        .int1('M').raw(new String(text).getBytes(StandardCharsets.US_ASCII)).int1(0)
        .int1(0)
        .toBytes();
  }

  /**
   * Connects through {@link FakeSocketFactory}, which serves one script per socket the driver
   * opens, in order.
   */
  private static final class ScriptedServer {
    final List<FakeSocket> sockets = Collections.synchronizedList(new ArrayList<>());
    private final String key = UUID.randomUUID().toString();
    private final Deque<byte[]> scripts;

    ScriptedServer(byte[]... scripts) {
      this.scripts = new ArrayDeque<>(Arrays.asList(scripts));
    }

    SQLException connectAndFail(Properties extra) {
      Properties props = new Properties();
      props.setProperty("user", "test");
      props.setProperty("password", "test");
      props.setProperty("sslmode", "disable");
      props.setProperty("gssEncMode", "disable");
      props.setProperty("socketFactory", FakeSocketFactory.class.getName());
      props.setProperty("socketFactoryArg", key);
      props.putAll(extra);
      FakeSocketFactory.register(key, this::nextSocket);
      try {
        return assertThrows(SQLException.class,
            () -> DriverManager.getConnection("jdbc:postgresql://localhost:5432/test", props)
                .close());
      } finally {
        FakeSocketFactory.unregister(key);
      }
    }

    synchronized FakeSocket nextSocket() {
      byte[] script = scripts.poll();
      assertTrue(script != null, "the driver opened more sockets than the test scripted");
      FakeSocket socket = new FakeSocket(script);
      sockets.add(socket);
      return socket;
    }

    FakeSocket onlySocket() {
      assertEquals(1, sockets.size(), "sockets opened");
      return sockets.get(0);
    }

    /**
     * Asserts that every socket was closed, and the last one with SO_LINGER on and a zero timeout,
     * the state {@link PGStream#markBroken(Throwable)} leaves.
     */
    void assertEverySocketBroken() {
      FakeSocket last = sockets.get(sockets.size() - 1);
      assertAll(
          () -> assertTrue(sockets.stream().allMatch(s -> s.closed), "every socket closed"),
          () -> assertTrue(last.lingerOn, "SO_LINGER on"),
          () -> assertEquals(0, last.lingerSeconds, "SO_LINGER timeout"));
    }
  }
}
