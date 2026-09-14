/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.postgresql.core.PGStreamTestSupport.assertBroken;
import static org.postgresql.core.PGStreamTestSupport.openStream;

import org.postgresql.PGNotification;
import org.postgresql.copy.CopyDual;
import org.postgresql.copy.CopyOperation;
import org.postgresql.copy.CopyOut;
import org.postgresql.copy.PGCopyInputStream;
import org.postgresql.core.v3.QueryExecutorImpl;
import org.postgresql.core.v3.replication.V3PGReplicationStream;
import org.postgresql.replication.LogSequenceNumber;
import org.postgresql.replication.ReplicationType;
import org.postgresql.test.util.FakeSocket;
import org.postgresql.test.util.Wire;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.PSQLWarning;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Every backend message {@link QueryExecutorImpl} reads after authentication is rejected when its
 * declared length does not fit what it carries, and the rejection closes the connection instead of
 * leaving the reader off a message boundary.
 *
 * <p>A fixed-size message must declare exactly its size. A message that carries a count
 * (ParameterDescription, RowDescription, and the three copy responses) must be long enough for
 * that count, and exactly as long where the protocol fixes the size of each counted item. Every
 * body must be consumed exactly. ErrorResponse, NoticeResponse, CommandComplete, ParameterStatus,
 * and NotificationResponse are limited by {@code maxServerTextMessageSize}, which
 * {@link ProtocolHardeningMode#DISABLE} switches off; the 8 MiB RowDescription limit holds in every
 * mode. A CopyData read during a copy is limited by {@code maxCopyDataSize}, or by 64 MB
 * (64000000 bytes) while that property is unset, and {@link ProtocolHardeningMode#DISABLE} switches
 * off only the 64 MB limit. Every other check holds in both modes, so each rejection runs under
 * both.</p>
 *
 * <p>The executor reads from bytes built here through a fake socket, so no server is involved. A
 * rejection reaches the caller as SQLState 08006 with the reader's {@link IOException} as the
 * cause, with three exceptions: during startup the constructor throws the reader's exception
 * itself, a CopyData over its limit reaches the caller as a {@link PSQLException} with SQLState
 * 08S01 whose own message names the limit, and an empty CopyData on a replication stream reaches
 * the caller as a {@link PSQLException} with SQLState 08P01 and leaves the connection open.</p>
 */
class QueryExecutorImplMessageLengthTest {
  private static final String MAX = String.valueOf(PGStream.MAX_MESSAGE_SIZE);

  // Message ids copied verbatim from PGStream and QueryExecutorImpl, so each assertion pins which
  // error was thrown while surviving a translation refresh.
  private static final String INVALID_LENGTH =
      "Protocol error. {0} message has invalid length {1} (expected between {2} and {3}).";
  private static final String FIXED_LENGTH = "Protocol error. {0} message has length {1}, expected {2}.";
  private static final String UNREAD = "Protocol error. {0} message has {1} unread bytes.";
  private static final String TEXT_LIMIT =
      "Protocol error. {0} message has length {1}, which exceeds the pgjdbc limit of {2} bytes. Raise the {3} connection property if the backend legitimately sends more, or set -D{4}=disable to skip these limits altogether.";

  // ---------------------------------------------------------------------------------------------
  // Fixed-size messages

  static Stream<Arguments> fixedSizeMessagesWithAWrongLength() {
    List<Arguments.ArgumentSet> cases = new ArrayList<>();
    Object[][] messages = {
        {'1', "ParseComplete", 4},
        {'2', "BindComplete", 4},
        {'3', "CloseComplete", 4},
        {'n', "NoData", 4},
        {'s', "PortalSuspended", 4},
        {'I', "EmptyQueryResponse", 4},
        {'c', "CopyDone", 4},
        {'Z', "ReadyForQuery", 5},
    };
    for (Object[] m : messages) {
      int size = (Integer) m[2];
      cases.add(argumentSet(m[1] + " one byte short", m[0], m[1], size, size - 1));
      cases.add(argumentSet(m[1] + " one byte long", m[0], m[1], size, size + 1));
    }
    return inEveryMode(cases.stream());
  }

  @ParameterizedTest
  @MethodSource("fixedSizeMessagesWithAWrongLength")
  void aFixedSizeMessageWithAnotherLengthBreaksTheConnection(char type, String name, int size,
      int declared, ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(), header(type, declared), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e,
        GT.tr(FIXED_LENGTH, name, String.valueOf(declared), String.valueOf(size)));
  }

  static Stream<Arguments> lengthsOutsideTheReaderBounds() {
    return inEveryMode(Stream.of(
        argumentSet("RowDescription below 6", 'T', "RowDescription", 5, "6", MAX),
        argumentSet("ParameterDescription below 6", 't', "ParameterDescription", 5, "6", "262146"),
        argumentSet("ParameterDescription above 6 + 4 * 65535", 't', "ParameterDescription", 262147,
            "6", "262146"),
        argumentSet("CopyInResponse below 4", 'G', "CopyInResponse", 3, "4", MAX),
        argumentSet("CopyOutResponse below 4", 'H', "CopyOutResponse", 3, "4", MAX),
        argumentSet("CopyData below 4", 'd', "CopyData", 3, "4", MAX)));
  }

  @ParameterizedTest
  @MethodSource("lengthsOutsideTheReaderBounds")
  void aLengthOutsideTheReaderBoundsBreaksTheConnection(char type, String name, int declared,
      String min, String max, ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(), header(type, declared), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e,
        GT.tr(INVALID_LENGTH, name, String.valueOf(declared), min, max));
  }

  /**
   * A CopyData outside a copy operation is skipped by its declared length, and the query that
   * follows it still completes.
   */
  @Test
  void aCopyDataOutsideACopyIsSkipped() throws Exception {
    Session s = new Session(readyForQuery(),
        message('d', new Wire().bytes(3).toBytes()),
        message('C', cstring("SELECT 0")),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    Recorder recorder = s.executeSimpleQuery(executor);

    assertAll(
        () -> assertEquals(Collections.singletonList("SELECT 0"), recorder.statuses, "command statuses"),
        () -> assertFalse(executor.isClosed(), "isClosed()"));
  }

  // ---------------------------------------------------------------------------------------------
  // maxServerTextMessageSize

  /**
   * The messages {@code maxServerTextMessageSize} limits. {@link #body(int)} builds a valid body
   * for a message whose length field holds {@code length}, at least 20, carrying
   * {@code letters(payloadLength(length))} as its text, and {@link #readBack} returns that text
   * from where the executor exposes it.
   */
  enum TextMessage {
    ERROR_RESPONSE('E', "ErrorResponse", 5),
    NOTICE_RESPONSE('N', "NoticeResponse", 5),
    COMMAND_COMPLETE('C', "CommandComplete", 5),
    PARAMETER_STATUS('S', "ParameterStatus", 6),
    NOTIFICATION_RESPONSE('A', "NotificationResponse", 10);

    final char type;
    final String protocolName;
    final int minLength;

    TextMessage(char type, String protocolName, int minLength) {
      this.type = type;
      this.protocolName = protocolName;
      this.minLength = minLength;
    }

    int payloadLength(int length) {
      switch (this) {
        case ERROR_RESPONSE:
        case NOTICE_RESPONSE:
          return length - 4 - 3; // 'M', the payload, its NUL, and the NUL that ends the fields
        case COMMAND_COMPLETE:
          return length - 4 - 1;
        case PARAMETER_STATUS:
          return length - 4 - 2 - 1; // "p\0", the value, its NUL
        default:
          return length - 4 - 4 - 2 - 1; // pid, "c\0", the payload, its NUL
      }
    }

    byte[] body(int length) {
      byte[] payload = letters(payloadLength(length));
      switch (this) {
        case ERROR_RESPONSE:
        case NOTICE_RESPONSE:
          return new Wire().int1('M').raw(payload).int1(0).int1(0).toBytes();
        case COMMAND_COMPLETE:
          return new Wire().raw(payload).int1(0).toBytes();
        case PARAMETER_STATUS:
          return new Wire().raw(cstring("p")).raw(payload).int1(0).toBytes();
        default:
          return new Wire().int4(7).raw(cstring("c")).raw(payload).int1(0).toBytes();
      }
    }

    /** The smallest valid body: empty strings and no error fields. */
    byte[] minimalBody() {
      switch (this) {
        case PARAMETER_STATUS:
          return new byte[2];
        case NOTIFICATION_RESPONSE:
          return new Wire().int4(7).int1(0).int1(0).toBytes();
        default:
          return new byte[1];
      }
    }

    /** Runs a simple query over the prepared response and returns the text the message carried. */
    String readBack(Session s, QueryExecutorImpl executor) throws SQLException {
      switch (this) {
        case ERROR_RESPONSE: {
          PSQLException e = assertThrowsExactly(PSQLException.class,
              () -> s.executeSimpleQuery(executor));
          return String.valueOf(castNonNull(e.getServerErrorMessage()).getMessage());
        }
        case NOTICE_RESPONSE:
          return String.valueOf(((PSQLWarning) s.executeSimpleQuery(executor).warnings.get(0))
              .getServerErrorMessage().getMessage());
        case COMMAND_COMPLETE:
          return s.executeSimpleQuery(executor).statuses.get(0);
        case PARAMETER_STATUS:
          s.executeSimpleQuery(executor);
          return String.valueOf(executor.getParameterStatus("p"));
        default: {
          s.executeSimpleQuery(executor);
          PGNotification[] notifications = executor.getNotifications();
          assertEquals(1, notifications.length, "notifications");
          return notifications[0].getParameter();
        }
      }
    }
  }

  @ParameterizedTest
  @EnumSource(TextMessage.class)
  void aTextMessageAtTheLimitIsRead(TextMessage kind) throws Exception {
    Session s = new Session(readyForQuery(), message(kind.type, kind.body(100)), readyForQuery());
    s.stream.setMaxServerTextMessageSize("100");
    QueryExecutorImpl executor = s.connect();

    String text = kind.readBack(s, executor);

    assertAll(
        () -> assertEquals(text(kind.payloadLength(100)), text, "text the message carried"),
        () -> assertFalse(executor.isClosed(), "isClosed()"));
  }

  @ParameterizedTest
  @EnumSource(TextMessage.class)
  void aTextMessageOverTheLimitBreaksTheConnection(TextMessage kind) throws Exception {
    Session s = new Session(readyForQuery(), message(kind.type, kind.body(101)), readyForQuery());
    s.stream.setMaxServerTextMessageSize("100");
    s.stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(TEXT_LIMIT, kind.protocolName, "101", "100",
        "maxServerTextMessageSize", "pgjdbc.protocolHardeningMode"));
  }

  /** With {@code maxServerTextMessageSize} unset, the limit is 64 MB (64000000 bytes). */
  @ParameterizedTest
  @EnumSource(TextMessage.class)
  void aTextMessageOverTheDefaultLimitBreaksTheConnection(TextMessage kind) throws Exception {
    Session s = new Session(readyForQuery(), header(kind.type, 64000001), new byte[8]);
    s.stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(TEXT_LIMIT, kind.protocolName, "64000001",
        "64000000", "maxServerTextMessageSize", "pgjdbc.protocolHardeningMode"));
  }

  @ParameterizedTest
  @EnumSource(TextMessage.class)
  void aTextMessageOverTheLimitIsReadUnderDisable(TextMessage kind) throws Exception {
    Session s = new Session(readyForQuery(), message(kind.type, kind.body(101)), readyForQuery());
    s.stream.setMaxServerTextMessageSize("100");
    s.stream.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);
    QueryExecutorImpl executor = s.connect();

    String text = kind.readBack(s, executor);

    assertAll(
        () -> assertEquals(text(kind.payloadLength(101)), text, "text the message carried"),
        () -> assertFalse(executor.isClosed(), "isClosed()"));
  }

  @ParameterizedTest
  @EnumSource(value = TextMessage.class, mode = EnumSource.Mode.EXCLUDE, names = "ERROR_RESPONSE")
  void aTextMessageAtItsMinimumLengthIsRead(TextMessage kind) throws Exception {
    Session s = new Session(readyForQuery(), message(kind.type, kind.minimalBody()), readyForQuery());
    QueryExecutorImpl executor = s.connect();

    s.executeSimpleQuery(executor);

    assertFalse(executor.isClosed(), "isClosed()");
  }

  /**
   * A 5-byte ErrorResponse carries no fields, so the query fails with a server error that has no
   * SQLState, and the connection stays open.
   */
  @Test
  void anErrorResponseAtItsMinimumLengthIsReportedAsAServerError() throws Exception {
    Session s = new Session(readyForQuery(), message('E', new byte[1]), readyForQuery());
    QueryExecutorImpl executor = s.connect();

    PSQLException e = assertThrowsExactly(PSQLException.class, () -> s.executeSimpleQuery(executor));

    assertAll(
        () -> assertNotNull(e.getServerErrorMessage(), "server error message"),
        () -> assertNull(e.getSQLState(), "SQLState"),
        () -> assertFalse(executor.isClosed(), "isClosed()"));
  }

  static Stream<Arguments> textMessagesBelowTheirMinimumInEveryMode() {
    List<Arguments> cases = new ArrayList<>();
    for (TextMessage kind : TextMessage.values()) {
      for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
        cases.add(Arguments.arguments(kind, mode));
      }
    }
    return cases.stream();
  }

  @ParameterizedTest
  @MethodSource("textMessagesBelowTheirMinimumInEveryMode")
  void aTextMessageBelowItsMinimumLengthBreaksTheConnection(TextMessage kind,
      ProtocolHardeningMode mode) throws Exception {
    int declared = kind.minLength - 1;
    Session s = new Session(readyForQuery(), header(kind.type, declared), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(INVALID_LENGTH, kind.protocolName,
        String.valueOf(declared), String.valueOf(kind.minLength), MAX));
  }

  /**
   * The channel name and the payload are each bounded only by what the body has left, so a byte
   * after the payload's NUL is left for the end-of-message check to reject.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aNotificationResponseWithAByteAfterThePayloadBreaksTheConnection(ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(),
        message('A', new Wire().int4(7).raw(cstring("c")).raw(cstring("x")).int1('z').toBytes()),
        readyForQuery());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(UNREAD, "NotificationResponse", "1"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aParameterStatusWithAByteAfterTheValueBreaksTheConnection(ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(),
        message('S', new Wire().raw(cstring("p")).raw(cstring("v")).int1('z').toBytes()),
        readyForQuery());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(UNREAD, "ParameterStatus", "1"));
  }

  // ---------------------------------------------------------------------------------------------
  // RowDescription

  private static final String ROW_DESCRIPTION_COUNT =
      "Protocol error. RowDescription field count {0} requires at least {1} bytes, but the message is only {2} bytes.";
  private static final String ROW_DESCRIPTION_LIMIT =
      "Protocol error. RowDescription message has length {0}, which exceeds the pgjdbc limit of {1} bytes.";

  /** One field description: label, table OID 0, attnum 1, type OID 23, typlen 4, typmod -1. */
  private static byte[] fieldDescription(String label, int formatCode) {
    return new Wire().raw(cstring(label)).int4(0).int2(1).int4(23).int2(4).int4(-1)
        .int2(formatCode).toBytes();
  }

  @Test
  void aRowDescriptionIsReadIntoFields() throws Exception {
    Session s = new Session(readyForQuery(),
        message('T', new Wire().int2(1).raw(fieldDescription("a", 1)).toBytes()),
        message('C', cstring("SELECT 0")),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    Field[] fields = castNonNull(s.executeSimpleQuery(executor).fields);

    assertAll(
        () -> assertEquals(1, fields.length, "field count"),
        () -> assertEquals("a", fields[0].getColumnLabel(), "label"),
        () -> assertEquals(23, fields[0].getOID(), "type OID"),
        () -> assertEquals(1, fields[0].getFormat(), "format code"));
  }

  /** A format code is a signed int16, so 0xffff is -1 rather than 65535. */
  @Test
  void aRowDescriptionFormatCodeIsSigned() throws Exception {
    Session s = new Session(readyForQuery(),
        message('T', new Wire().int2(1).raw(fieldDescription("a", 0xffff)).toBytes()),
        message('C', cstring("SELECT 0")),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    Field[] fields = castNonNull(s.executeSimpleQuery(executor).fields);

    assertEquals(-1, fields[0].getFormat(), "format code");
  }

  @Test
  void aRowDescriptionOfSixBytesWithNoFieldsIsRead() throws Exception {
    Session s = new Session(readyForQuery(),
        message('T', new Wire().int2(0).toBytes()),
        message('C', cstring("SELECT 0")),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    Field[] fields = castNonNull(s.executeSimpleQuery(executor).fields);

    assertAll(
        () -> assertEquals(0, fields.length, "field count"),
        () -> assertFalse(executor.isClosed(), "isClosed()"));
  }

  /** Two fields with empty labels take 19 bytes each, the least a field description can take. */
  @Test
  void aRowDescriptionOfTheSmallestFieldsForItsCountIsRead() throws Exception {
    Session s = new Session(readyForQuery(),
        message('T', new Wire().int2(2).raw(fieldDescription("", 0))
            .raw(fieldDescription("", 0)).toBytes()),
        message('C', cstring("SELECT 0")),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    Field[] fields = castNonNull(s.executeSimpleQuery(executor).fields);

    assertEquals(2, fields.length, "field count");
  }

  /**
   * The field count is unsigned: 0x8000 and 0xffff are 32768 and 65535 fields, each needing at
   * least 19 bytes, not negative counts.
   */
  static Stream<Arguments> rowDescriptionsTooShortForTheirCount() {
    return inEveryMode(Stream.of(
        argumentSet("2 fields in 43 bytes", 2, 43, "44"),
        argumentSet("0x8000 fields in 43 bytes", 0x8000, 43, "622598"),
        argumentSet("0xffff fields in 43 bytes", 0xffff, 43, "1245171")));
  }

  @ParameterizedTest
  @MethodSource("rowDescriptionsTooShortForTheirCount")
  void aRowDescriptionTooShortForItsFieldCountBreaksTheConnection(int count, int declared,
      String required, ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(),
        new Wire().int1('T').int4(declared).int2(count).toBytes(), new byte[64]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(ROW_DESCRIPTION_COUNT,
        String.valueOf(count), required, String.valueOf(declared)));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aRowDescriptionWithAByteAfterItsLastFieldBreaksTheConnection(ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(),
        message('T', new Wire().int2(1).raw(fieldDescription("a", 0)).int1('z').toBytes()),
        readyForQuery());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(UNREAD, "RowDescription", "1"));
  }

  @Test
  void aRowDescriptionAtTheSizeLimitIsRead() throws Exception {
    // 4 (length) + 2 (count) + label + NUL + 18 fixed bytes = 8388608
    String label = text(PGStream.MAX_ROW_DESCRIPTION_SIZE - 4 - 2 - 1 - 18);
    Session s = new Session(readyForQuery(),
        message('T', new Wire().int2(1).raw(fieldDescription(label, 0)).toBytes()),
        message('C', cstring("SELECT 0")),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    Field[] fields = castNonNull(s.executeSimpleQuery(executor).fields);

    assertEquals(label.length(), fields[0].getColumnLabel().length(), "label length");
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aRowDescriptionOverTheSizeLimitBreaksTheConnectionInEveryMode(ProtocolHardeningMode mode)
      throws Exception {
    Session s = new Session(readyForQuery(), header('T', 8388609), new Wire().int2(1).toBytes());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.executeSimpleQuery(executor));

    assertConnectionBroken(s, executor, e, GT.tr(ROW_DESCRIPTION_LIMIT, "8388609", "8388608"));
  }

  // ---------------------------------------------------------------------------------------------
  // ParameterDescription

  private static final String PARAMETER_DESCRIPTION_COUNT =
      "Protocol error. ParameterDescription parameter count {0} requires message size {1}, but the message is {2} bytes.";

  @Test
  void aParameterDescriptionResolvesTheParameterTypes() throws Exception {
    Session s = new Session(readyForQuery(),
        header('1', 4),
        message('t', new Wire().int2(1).int4(23).toBytes()),
        header('n', 4),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();
    Query query = executor.createQuery("SELECT ?", true, true).query;
    ParameterList parameters = query.createParameterList();

    s.describe(executor, query, parameters);

    assertArrayEquals(new int[]{23}, parameters.getTypeOIDs(), "parameter type OIDs");
  }

  @Test
  void aParameterDescriptionOfSixBytesWithNoParametersIsRead() throws Exception {
    Session s = new Session(readyForQuery(),
        header('1', 4),
        message('t', new Wire().int2(0).toBytes()),
        header('n', 4),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();
    Query query = executor.createQuery("SELECT 1", true, true).query;
    ParameterList parameters = query.createParameterList();

    s.describe(executor, query, parameters);

    assertFalse(executor.isClosed(), "isClosed()");
  }

  /**
   * The parameter count is unsigned: 0x8000 parameters need 131078 bytes, where a signed read
   * would give a negative count.
   */
  static Stream<Arguments> parameterDescriptionsWhoseSizeDoesNotMatchTheCount() {
    return inEveryMode(Stream.of(
        argumentSet("1 parameter in 6 bytes", 1, new byte[0], "10"),
        argumentSet("1 parameter in 14 bytes", 1, new byte[8], "10"),
        argumentSet("0x8000 parameters in 10 bytes", 0x8000, new byte[4], "131078")));
  }

  @ParameterizedTest
  @MethodSource("parameterDescriptionsWhoseSizeDoesNotMatchTheCount")
  void aParameterDescriptionWhoseSizeDoesNotMatchItsCountBreaksTheConnection(int count,
      byte[] oids, String required, ProtocolHardeningMode mode) throws Exception {
    byte[] body = new Wire().int2(count).raw(oids).toBytes();
    Session s = new Session(readyForQuery(), header('1', 4), message('t', body), readyForQuery());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();
    Query query = executor.createQuery("SELECT ?", true, true).query;
    ParameterList parameters = query.createParameterList();

    SQLException e = assertThrows(SQLException.class,
        () -> s.describe(executor, query, parameters));

    assertConnectionBroken(s, executor, e, GT.tr(PARAMETER_DESCRIPTION_COUNT,
        String.valueOf(count), required, String.valueOf(4 + body.length)));
  }

  // ---------------------------------------------------------------------------------------------
  // FunctionCallResponse

  @Test
  void aFunctionValueFillingTheMessageIsReturned() throws Exception {
    Session s = new Session(readyForQuery(),
        message('V', new Wire().int4(3).raw(letters(3)).toBytes()),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    assertArrayEquals(letters(3), s.fastpathCall(executor), "function value");
  }

  @Test
  void aNullFunctionValueIsReturnedAsNull() throws Exception {
    Session s = new Session(readyForQuery(), message('V', new Wire().int4(-1).toBytes()),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();

    assertNull(s.fastpathCall(executor), "function value");
  }

  @ParameterizedTest
  @MethodSource("valueLengthsBeyondTheMessage")
  void aFunctionValueLongerThanTheMessageBreaksTheConnection(int valueLength,
      ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(),
        message('V', new Wire().int4(valueLength).raw(letters(3)).toBytes()),
        readyForQuery());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.fastpathCall(executor));

    assertConnectionBroken(s, executor, e, GT.tr(
        "Protocol error. FunctionCallResponse value length {0} exceeds the {1} bytes left in the message.",
        String.valueOf(valueLength), "3"));
  }

  static Stream<Arguments> valueLengthsBeyondTheMessage() {
    return inEveryMode(Stream.of(
        argumentSet("4 with 3 bytes left", 4),
        argumentSet("Integer.MAX_VALUE with 3 bytes left", Integer.MAX_VALUE)));
  }

  @ParameterizedTest
  @MethodSource("negativeValueLengthsOtherThanMinusOne")
  void aNegativeFunctionValueLengthOtherThanMinusOneBreaksTheConnection(int valueLength,
      ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(), message('V', new Wire().int4(valueLength).toBytes()),
        readyForQuery());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.fastpathCall(executor));

    assertConnectionBroken(s, executor, e, GT.tr(
        "Protocol error. FunctionCallResponse has negative value length {0}.",
        String.valueOf(valueLength)));
  }

  static Stream<Arguments> negativeValueLengthsOtherThanMinusOne() {
    return inEveryMode(Stream.of(
        argumentSet("-2", -2),
        argumentSet("Integer.MIN_VALUE", Integer.MIN_VALUE)));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aFunctionValueShorterThanTheMessageBreaksTheConnection(ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(),
        message('V', new Wire().int4(2).raw(letters(3)).toBytes()),
        readyForQuery());
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.fastpathCall(executor));

    assertConnectionBroken(s, executor, e, GT.tr(UNREAD, "FunctionCallResponse", "1"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aFunctionCallResponseShorterThanItsValueLengthFieldBreaksTheConnection(
      ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(), header('V', 7), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class, () -> s.fastpathCall(executor));

    assertConnectionBroken(s, executor, e,
        GT.tr(INVALID_LENGTH, "FunctionCallResponse", "7", "8", MAX));
  }

  // ---------------------------------------------------------------------------------------------
  // CopyInResponse, CopyOutResponse, CopyBothResponse

  // The fake backend ignores the statement text and answers with the bytes each test prepares.
  private static final String COPY_STATEMENT = "COPY t FROM STDIN";

  private static final String COPY_COUNT =
      "Protocol error. {0} field count {1} requires message size {2}, but the message is {3} bytes.";

  enum CopyResponse {
    COPY_IN('G', "CopyInResponse"),
    COPY_OUT('H', "CopyOutResponse"),
    COPY_BOTH('W', "CopyBothResponse");

    final char type;
    final String protocolName;

    CopyResponse(char type, String protocolName) {
      this.type = type;
      this.protocolName = protocolName;
    }
  }

  /** Field format codes are signed int16, so 0xffff is -1. */
  @ParameterizedTest
  @EnumSource(CopyResponse.class)
  void aCopyResponseIsReadIntoTheCopyOperation(CopyResponse kind) throws Exception {
    Session s = new Session(readyForQuery(),
        message(kind.type, new Wire().int1(1).int2(2).int2(1).int2(0xffff).toBytes()));
    QueryExecutorImpl executor = s.connect();

    CopyOperation op = executor.startCopy(COPY_STATEMENT, true);

    assertAll(
        () -> assertEquals(1, op.getFormat(), "row format"),
        () -> assertEquals(2, op.getFieldCount(), "field count"),
        () -> assertEquals(1, op.getFieldFormat(0), "format of field 0"),
        () -> assertEquals(-1, op.getFieldFormat(1), "format of field 1"));
  }

  static Stream<Arguments> copyResponsesWhoseSizeDoesNotMatchTheCount() {
    List<Arguments.ArgumentSet> cases = new ArrayList<>();
    for (CopyResponse kind : CopyResponse.values()) {
      cases.add(argumentSet(kind.protocolName + " with 1 field in 7 bytes", kind, 1, 0, "9"));
      cases.add(argumentSet(kind.protocolName + " with 1 field in 11 bytes", kind, 1, 4, "9"));
      cases.add(argumentSet(kind.protocolName + " with 0x8000 fields in 9 bytes", kind, 0x8000, 2,
          "65543"));
    }
    return inEveryMode(cases.stream());
  }

  /** The field count is unsigned: 0x8000 fields need 65543 bytes. */
  @ParameterizedTest
  @MethodSource("copyResponsesWhoseSizeDoesNotMatchTheCount")
  void aCopyResponseWhoseSizeDoesNotMatchItsCountBreaksTheConnection(CopyResponse kind, int count,
      int formatBytes, String required, ProtocolHardeningMode mode) throws Exception {
    byte[] body = new Wire().int1(0).int2(count).raw(new byte[formatBytes]).toBytes();
    Session s = new Session(readyForQuery(), message(kind.type, body));
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class,
        () -> executor.startCopy(COPY_STATEMENT, true));

    assertConnectionBroken(s, executor, e, GT.tr(COPY_COUNT, kind.protocolName,
        String.valueOf(count), required, String.valueOf(4 + body.length)));
  }

  static Stream<Arguments> copyResponsesInEveryMode() {
    return inEveryMode(Arrays.stream(CopyResponse.values())
        .map(kind -> argumentSet(kind.protocolName, kind)));
  }

  /** 65535 fields, the most the unsigned count allows, make a copy response of 131077 bytes. */
  @ParameterizedTest
  @EnumSource(CopyResponse.class)
  void aCopyResponseOfTheProtocolMaximumIsRead(CopyResponse kind) throws Exception {
    Session s = new Session(readyForQuery(),
        message(kind.type, new Wire().int1(0).int2(0xffff).raw(new byte[2 * 0xffff]).toBytes()));
    QueryExecutorImpl executor = s.connect();

    CopyOperation op = executor.startCopy(COPY_STATEMENT, true);

    assertEquals(65535, op.getFieldCount(), "field count");
  }

  @ParameterizedTest
  @MethodSource("copyResponsesInEveryMode")
  void aCopyResponseLongerThanTheProtocolMaximumBreaksTheConnection(CopyResponse kind,
      ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(), header(kind.type, 131078), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class,
        () -> executor.startCopy(COPY_STATEMENT, true));

    assertConnectionBroken(s, executor, e,
        GT.tr(INVALID_LENGTH, kind.protocolName, "131078", "7", "131077"));
  }

  /** The copy reader that startCopy runs rejects the CopyDone before any copy response. */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aCopyDoneWithAnotherLengthBeforeACopyStartsBreaksTheConnection(ProtocolHardeningMode mode)
      throws Exception {
    Session s = new Session(readyForQuery(), header('c', 5), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();

    SQLException e = assertThrows(SQLException.class,
        () -> executor.startCopy(COPY_STATEMENT, true));

    assertConnectionBroken(s, executor, e, GT.tr(FIXED_LENGTH, "CopyDone", "5", "4"));
  }

  /** A CopyOutResponse with no fields, which starts a copy-out. */
  private static byte[] copyOutResponse() {
    return message('H', new Wire().int1(0).int2(0).toBytes());
  }

  @Test
  void aCopyDoneEndsACopyOut() throws Exception {
    Session s = new Session(readyForQuery(), copyOutResponse(),
        header('c', 4), message('C', cstring("COPY 0")), readyForQuery());
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    byte[] row = copyOut.readFromCopy();

    assertAll(
        () -> assertNull(row, "readFromCopy()"),
        () -> assertFalse(copyOut.isActive(), "isActive()"),
        () -> assertFalse(executor.isClosed(), "executor isClosed()"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aCopyDoneWithAnotherLengthDuringACopyOutBreaksTheConnection(ProtocolHardeningMode mode)
      throws Exception {
    Session s = new Session(readyForQuery(), copyOutResponse(), header('c', 5), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    SQLException e = assertThrows(SQLException.class, copyOut::readFromCopy);

    assertConnectionBroken(s, executor, e, GT.tr(FIXED_LENGTH, "CopyDone", "5", "4"));
  }

  /**
   * A ParameterStatus the driver cannot apply ends the copy-out read with an SQLException and no
   * ReadyForQuery. The copy must not keep the connection lock, because every later operation
   * waits for that lock.
   */
  @Test
  void anSqlExceptionDuringACopyOutReleasesTheCopyLock() throws Exception {
    Session s = new Session(readyForQuery(), copyOutResponse(),
        message('S', new Wire().raw(cstring("client_encoding")).raw(cstring("LATIN1")).toBytes()),
        readyForQuery());
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    assertThrowsExactly(PSQLException.class, copyOut::readFromCopy);

    assertFalse(copyOut.isActive(), "isActive() after the failed read");
  }

  // ---------------------------------------------------------------------------------------------
  // CopyData during a copy: maxCopyDataSize

  private static final String COPY_DATA_BUILT_IN_LIMIT =
      "Protocol error. CopyData message has length {0}, which exceeds the built-in limit of {1} bytes. Raise the {2} connection property if the backend legitimately sends more, or set -D{3}=disable to skip these limits altogether.";
  private static final String COPY_DATA_CONFIGURED_LIMIT =
      "CopyData message has length {0}, which exceeds the maxCopyDataSize limit of {1} bytes.";

  /** A copy response of {@code kind} with no fields. */
  private static byte[] copyResponse(CopyResponse kind) {
    return message(kind.type, new Wire().int1(0).int2(0).toBytes());
  }

  /**
   * A copy-out that sends 2 bytes, an empty CopyData, 3 bytes, and CopyDone. The protocol allows a
   * CopyData with an empty body.
   */
  private static Session copyOutWithAnEmptyCopyDataBetweenTwoRows() {
    return new Session(readyForQuery(), copyOutResponse(), message('d', letters(2)),
        header('d', 4), message('d', letters(3)), header('c', 4), message('C', cstring("COPY 3")),
        readyForQuery());
  }

  /**
   * The CopyData after the empty one is read as its own message, so the empty one was closed at
   * its declared length of 4.
   */
  @Test
  void anEmptyCopyDataIsReturnedAsAnEmptyArray() throws Exception {
    Session s = copyOutWithAnEmptyCopyDataBetweenTwoRows();
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    byte[] first = copyOut.readFromCopy();
    byte[] empty = copyOut.readFromCopy();
    byte[] second = copyOut.readFromCopy();
    byte[] end = copyOut.readFromCopy();

    assertAll(
        () -> assertArrayEquals(letters(2), first, "first readFromCopy()"),
        () -> assertArrayEquals(new byte[0], empty, "second readFromCopy(), the empty CopyData"),
        () -> assertArrayEquals(letters(3), second, "third readFromCopy()"),
        () -> assertNull(end, "fourth readFromCopy(), after CopyDone"),
        () -> assertFalse(executor.isClosed(), "executor isClosed()"));
  }

  @Test
  void aCopyInputStreamReadsSingleBytesAcrossAnEmptyCopyData() throws Exception {
    Session s = copyOutWithAnEmptyCopyDataBetweenTwoRows();
    QueryExecutorImpl executor = s.connect();
    PGCopyInputStream in =
        new PGCopyInputStream((CopyOut) executor.startCopy(COPY_STATEMENT, true));

    int[] read = new int[6];
    for (int i = 0; i < read.length; i++) {
      read[i] = in.read();
    }

    assertArrayEquals(new int[]{'a', 'b', 'a', 'b', 'c', -1}, read, "six read() calls");
  }

  @Test
  void aCopyInputStreamReadsAnArrayAcrossAnEmptyCopyData() throws Exception {
    Session s = copyOutWithAnEmptyCopyDataBetweenTwoRows();
    QueryExecutorImpl executor = s.connect();
    PGCopyInputStream in =
        new PGCopyInputStream((CopyOut) executor.startCopy(COPY_STATEMENT, true));
    byte[] buf = new byte[10];

    int count = in.read(buf, 0, buf.length);

    assertAll(
        () -> assertEquals(5, count, "read(buf, 0, 10)"),
        () -> assertArrayEquals(new byte[]{'a', 'b', 'a', 'b', 'c'}, Arrays.copyOf(buf, 5),
            "bytes read"),
        () -> assertEquals(-1, in.read(buf, 0, buf.length), "read(buf, 0, 10) after CopyDone"));
  }

  @Test
  void aCopyInputStreamReadFromCopySkipsAnEmptyCopyData() throws Exception {
    Session s = copyOutWithAnEmptyCopyDataBetweenTwoRows();
    QueryExecutorImpl executor = s.connect();
    PGCopyInputStream in =
        new PGCopyInputStream((CopyOut) executor.startCopy(COPY_STATEMENT, true));

    byte[] first = in.readFromCopy();
    byte[] second = in.readFromCopy();
    byte[] end = in.readFromCopy();

    assertAll(
        () -> assertArrayEquals(letters(2), first, "first readFromCopy()"),
        () -> assertArrayEquals(letters(3), second, "second readFromCopy(), past the empty CopyData"),
        () -> assertNull(end, "third readFromCopy(), after CopyDone"));
  }

  /** An XLogData replication message: type {@code w}, three 8-byte fields, then the payload. */
  private static byte[] xLogData(long startLsn, byte[] payload) {
    return ByteBuffer.allocate(1 + 8 + 8 + 8 + payload.length)
        .put((byte) 'w').putLong(startLsn).putLong(startLsn).putLong(0).put(payload)
        .array();
  }

  private static byte[] remaining(@Nullable ByteBuffer buffer) {
    byte[] bytes = new byte[castNonNull(buffer).remaining()];
    buffer.get(bytes);
    return bytes;
  }

  /**
   * Every replication message starts with a type code, so an empty CopyData between two XLogData
   * messages is a protocol violation, reported like an unknown message type: the connection stays
   * open, on the boundary of the next message, which the next read returns.
   */
  @Test
  void anEmptyCopyDataOnAReplicationStreamIsAProtocolViolation() throws Exception {
    Session s = new Session(readyForQuery(), copyResponse(CopyResponse.COPY_BOTH),
        message('d', xLogData(16, letters(2))), header('d', 4),
        message('d', xLogData(32, letters(3))));
    QueryExecutorImpl executor = s.connect();
    CopyDual copyDual = (CopyDual) executor.startCopy(COPY_STATEMENT, true);
    V3PGReplicationStream stream = new V3PGReplicationStream(copyDual,
        LogSequenceNumber.valueOf(0), 0, false, ReplicationType.LOGICAL);

    byte[] first = remaining(stream.read());
    PSQLException e = assertThrowsExactly(PSQLException.class, stream::read);
    boolean closedAfterRejection = executor.isClosed();
    byte[] third = remaining(stream.read());

    assertAll(
        () -> assertArrayEquals(letters(2), first, "first read()"),
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(
            GT.tr("Protocol error. The replication stream received an empty CopyData message, which carries no message type."),
            e.getMessage()),
        () -> assertFalse(closedAfterRejection, "executor isClosed() after the rejection"),
        () -> assertArrayEquals(letters(3), third, "read() after the rejection"));
  }

  /** A CopyData length field of 3 cannot count its own four bytes. */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aCopyDataLengthBelowFourDuringACopyOutBreaksTheConnection(ProtocolHardeningMode mode)
      throws Exception {
    Session s = new Session(readyForQuery(), copyOutResponse(), header('d', 3), new byte[8]);
    s.stream.setProtocolHardeningMode(mode);
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    SQLException e = assertThrows(SQLException.class, copyOut::readFromCopy);

    assertConnectionBroken(s, executor, e, GT.tr(INVALID_LENGTH, "CopyData", "3", "4", MAX));
  }

  /**
   * The backend sends only the declared length, so a reader that read the 64000001 bytes before
   * the check would fail on end of stream with a different exception. A CopyBothResponse starts the
   * copy a replication stream uses.
   */
  @ParameterizedTest
  @EnumSource(value = CopyResponse.class, names = {"COPY_OUT", "COPY_BOTH"})
  void aCopyDataOverTheBuiltInLimitFailsTheCopyAndBreaksTheConnection(CopyResponse kind)
      throws Exception {
    Session s = new Session(readyForQuery(), copyResponse(kind), header('d', 64000001));
    s.stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    PSQLException e = assertThrowsExactly(PSQLException.class, copyOut::readFromCopy);

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(COPY_DATA_BUILT_IN_LIMIT, "64000001", "64000000",
            "maxCopyDataSize", "pgjdbc.protocolHardeningMode"), e.getMessage()),
        () -> assertTrue(executor.isClosed(), "executor isClosed()"),
        () -> assertBroken(s.stream, s.socket));
  }

  static Stream<Arguments> copyOutAndCopyBothInEveryMode() {
    return inEveryMode(Stream.of(CopyResponse.COPY_OUT, CopyResponse.COPY_BOTH)
        .map(kind -> argumentSet(kind.protocolName, kind)));
  }

  /** The message of length 11 carries 7 bytes of data. */
  @ParameterizedTest
  @MethodSource("copyOutAndCopyBothInEveryMode")
  void aCopyDataOverAConfiguredLimitFailsTheCopyInEveryMode(CopyResponse kind,
      ProtocolHardeningMode mode) throws Exception {
    Session s = new Session(readyForQuery(), copyResponse(kind), message('d', letters(7)));
    s.stream.setProtocolHardeningMode(mode);
    s.stream.setMaxCopyDataSize("10");
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    PSQLException e = assertThrowsExactly(PSQLException.class, copyOut::readFromCopy);

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(COPY_DATA_CONFIGURED_LIMIT, "11", "10"), e.getMessage()),
        () -> assertTrue(executor.isClosed(), "executor isClosed()"),
        () -> assertBroken(s.stream, s.socket));
  }

  @Test
  void aCopyDataAtAConfiguredLimitIsReturned() throws Exception {
    Session s = new Session(readyForQuery(), copyOutResponse(), message('d', letters(6)));
    s.stream.setMaxCopyDataSize("10");
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    assertArrayEquals(letters(6), copyOut.readFromCopy(), "readFromCopy()");
  }

  @Test
  void maxResultBufferDoesNotLimitCopyData() throws Exception {
    Session s = new Session(readyForQuery(), copyOutResponse(), message('d', letters(100)));
    s.stream.setMaxResultBuffer("10");
    QueryExecutorImpl executor = s.connect();
    CopyOut copyOut = (CopyOut) executor.startCopy(COPY_STATEMENT, true);

    assertArrayEquals(letters(100), copyOut.readFromCopy(), "readFromCopy()");
  }

  // ---------------------------------------------------------------------------------------------
  // Startup: the messages between AuthenticationOk and the first ReadyForQuery

  @Test
  void backendKeyDataWithAFourByteKeyIsReadUnderProtocol30() throws Exception {
    Session s = new Session(message('K', new Wire().int4(4242).bytes(4).toBytes()), readyForQuery());
    s.stream.setProtocolVersion(ProtocolVersion.v3_0);

    assertEquals(4242, s.connect().getBackendPID(), "backend PID");
  }

  static Stream<Arguments> keyLengthsOtherThanFour() {
    return inEveryMode(Stream.of(
        argumentSet("0", 0),
        argumentSet("3", 3),
        argumentSet("5", 5),
        argumentSet("256", 256)));
  }

  @ParameterizedTest
  @MethodSource("keyLengthsOtherThanFour")
  void backendKeyDataWithAKeyOtherThanFourBytesBreaksTheConnectionUnderProtocol30(int keyLength,
      ProtocolHardeningMode mode) {
    Session s = new Session(message('K', new Wire().int4(4242).bytes(keyLength).toBytes()),
        readyForQuery());
    s.stream.setProtocolVersion(ProtocolVersion.v3_0);
    s.stream.setProtocolHardeningMode(mode);

    PSQLException e = assertThrowsExactly(PSQLException.class, s::connect);

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr("Protocol error. Cancel Key should be 4 bytes for protocol version {0},"
            + " but received {1} bytes. Session setup failed.", ProtocolVersion.v3_0, keyLength),
            e.getMessage()),
        () -> assertBroken(s.stream, s.socket));
  }

  @Test
  void backendKeyDataWithA256ByteKeyIsReadUnderProtocol32() throws Exception {
    Session s = new Session(message('K', new Wire().int4(4242).bytes(256).toBytes()),
        readyForQuery());
    s.stream.setProtocolVersion(ProtocolVersion.v3_2);

    assertEquals(4242, s.connect().getBackendPID(), "backend PID");
  }

  static Stream<Arguments> backendKeyDataLengthsOutsideTheProtocolRange() {
    List<Arguments> cases = new ArrayList<>();
    for (ProtocolHardeningMode mode : ProtocolHardeningMode.values()) {
      cases.add(argumentSet("7 bytes, " + mode, 7, mode));
      cases.add(argumentSet("265 bytes, " + mode, 265, mode));
    }
    return cases.stream();
  }

  @ParameterizedTest
  @MethodSource("backendKeyDataLengthsOutsideTheProtocolRange")
  void backendKeyDataOutsideTheProtocolRangeBreaksTheConnection(int declared,
      ProtocolHardeningMode mode) {
    Session s = new Session(header('K', declared), new byte[300]);
    s.stream.setProtocolVersion(ProtocolVersion.v3_2);
    s.stream.setProtocolHardeningMode(mode);

    IOException e = assertThrowsExactly(IOException.class, s::connect);

    assertAll(
        () -> assertEquals(GT.tr(INVALID_LENGTH, "BackendKeyData", String.valueOf(declared), "8",
            "264"), e.getMessage()),
        () -> assertBroken(s.stream, s.socket));
  }

  @Test
  void aStartupParameterStatusOverTheLimitBreaksTheConnection() {
    Session s = new Session(message('S', TextMessage.PARAMETER_STATUS.body(101)), readyForQuery());
    setLimit(s.stream, "100");
    s.stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);

    IOException e = assertThrowsExactly(IOException.class, s::connect);

    assertAll(
        () -> assertEquals(GT.tr(TEXT_LIMIT, "ParameterStatus", "101", "100",
            "maxServerTextMessageSize", "pgjdbc.protocolHardeningMode"),
            e.getMessage()),
        () -> assertBroken(s.stream, s.socket));
  }

  @Test
  void aStartupParameterStatusOverTheLimitIsReadUnderDisable() throws Exception {
    Session s = new Session(message('S', TextMessage.PARAMETER_STATUS.body(101)), readyForQuery());
    setLimit(s.stream, "100");
    s.stream.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);

    QueryExecutorImpl executor = s.connect();

    assertEquals(text(TextMessage.PARAMETER_STATUS.payloadLength(101)),
        executor.getParameterStatus("p"), "reported value of p");
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void anUnexpectedMessageDuringStartupBreaksTheConnection(ProtocolHardeningMode mode) {
    Session s = new Session(message('D', new Wire().int2(0).toBytes()), readyForQuery());
    s.stream.setProtocolHardeningMode(mode);

    PSQLException e = assertThrowsExactly(PSQLException.class, s::connect);

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertBroken(s.stream, s.socket));
  }

  /** Startup accepts 1000 messages, the last of them ReadyForQuery. */
  @Test
  void aReadyForQueryAsTheThousandthStartupMessageIsRead() throws Exception {
    Session s = new Session(parameterStatuses(999), readyForQuery());

    assertFalse(s.connect().isClosed(), "isClosed()");
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aThousandStartupMessagesWithoutReadyForQueryBreakTheConnection(ProtocolHardeningMode mode) {
    Session s = new Session(parameterStatuses(1000), readyForQuery());
    s.stream.setProtocolHardeningMode(mode);

    PSQLException e = assertThrowsExactly(PSQLException.class, s::connect);

    assertAll(
        () -> assertEquals(PSQLState.PROTOCOL_VIOLATION.getState(), e.getSQLState(), "SQLState"),
        () -> assertBroken(s.stream, s.socket));
  }

  // ---------------------------------------------------------------------------------------------
  // Helpers

  /** Repeats each named case once per {@link ProtocolHardeningMode}, passing the mode last. */
  private static Stream<Arguments> inEveryMode(Stream<Arguments.ArgumentSet> cases) {
    return cases.flatMap(c -> Arrays.stream(ProtocolHardeningMode.values()).map(mode -> {
      Object[] args = Arrays.copyOf(c.get(), c.get().length + 1);
      args[args.length - 1] = mode;
      return argumentSet(c.getName() + ", " + mode, args);
    }));
  }

  /** Asserts the state a reader's rejection leaves at the executor's public entry points. */
  private static void assertConnectionBroken(Session s, QueryExecutorImpl executor, SQLException e,
      String expectedCauseMessage) {
    Throwable cause = e.getCause();
    assertAll(
        () -> assertEquals(PSQLState.CONNECTION_FAILURE.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(IOException.class, cause == null ? null : cause.getClass(), "cause"),
        () -> assertEquals(expectedCauseMessage, cause == null ? null : cause.getMessage()),
        () -> assertTrue(executor.isClosed(), "executor isClosed()"),
        () -> assertBroken(s.stream, s.socket));
  }

  private static void setLimit(PGStream stream, String value) {
    try {
      stream.setMaxServerTextMessageSize(value);
    } catch (SQLException e) {
      throw new AssertionError("maxServerTextMessageSize=" + value, e);
    }
  }

  private static byte[] readyForQuery() {
    return message('Z', new byte[]{'I'});
  }

  /** A message whose length field counts itself and {@code body}. */
  private static byte[] message(char type, byte[] body) {
    return new Wire().int1(type).int4(4 + body.length).raw(body).toBytes();
  }

  /** A type byte and a length field, with no body. */
  private static byte[] header(char type, int declaredLength) {
    return new Wire().int1(type).int4(declaredLength).toBytes();
  }

  private static byte[] parameterStatuses(int count) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] one = message('S', new Wire().raw(cstring("p")).raw(cstring("v")).toBytes());
    for (int i = 0; i < count; i++) {
      out.write(one, 0, one.length);
    }
    return out.toByteArray();
  }

  private static byte[] cstring(String value) {
    return new Wire().raw(value.getBytes(StandardCharsets.UTF_8)).int1(0).toBytes();
  }

  /** {@code count} bytes {@code 'a'}, {@code 'b'}, ... */
  private static byte[] letters(int count) {
    return new Wire().bytes(count).toBytes();
  }

  private static String text(int count) {
    return new String(letters(count), StandardCharsets.US_ASCII);
  }

  private static <T> T castNonNull(T value) {
    assertNotNull(value);
    return value;
  }

  /** A {@link QueryExecutorImpl} over a fake socket that serves the concatenated backend bytes. */
  private static final class Session {
    final FakeSocket socket;
    final PGStream stream;

    Session(byte[]... backendBytes) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      for (byte[] part : backendBytes) {
        out.write(part, 0, part.length);
      }
      socket = new FakeSocket(out.toByteArray());
      stream = openStream(socket);
    }

    /** Creates the executor, which reads the startup messages. */
    QueryExecutorImpl connect() throws IOException, SQLException {
      return new QueryExecutorImpl(stream, 0, new Properties());
    }

    Recorder executeSimpleQuery(QueryExecutorImpl executor) throws SQLException {
      Recorder recorder = new Recorder();
      executor.execute(executor.createSimpleQuery("SELECT 1"), null, recorder, 0, 0,
          QueryExecutor.QUERY_EXECUTE_AS_SIMPLE | QueryExecutor.QUERY_SUPPRESS_BEGIN);
      return recorder;
    }

    void describe(QueryExecutorImpl executor, Query query, ParameterList parameters)
        throws SQLException {
      executor.execute(query, parameters, new Recorder(), 0, 0,
          QueryExecutor.QUERY_DESCRIBE_ONLY | QueryExecutor.QUERY_SUPPRESS_BEGIN);
    }

    @SuppressWarnings("deprecation")
    byte[] fastpathCall(QueryExecutorImpl executor) throws SQLException {
      return executor.fastpathCall(1, executor.createFastpathParameters(0), true);
    }
  }

  /** Records what the executor reports, and throws the first error from handleCompletion. */
  private static final class Recorder extends ResultHandlerBase {
    final List<String> statuses = new ArrayList<>();
    final List<SQLWarning> warnings = new ArrayList<>();
    Field[] fields;

    @Override
    public void handleResultRows(Query fromQuery, Field[] fields, List<Tuple> tuples,
        ResultCursor cursor) {
      this.fields = fields;
    }

    @Override
    public void handleCommandStatus(String status, long updateCount, long insertOID) {
      statuses.add(status);
    }

    @Override
    public void handleWarning(SQLWarning warning) {
      warnings.add(warning);
    }
  }
}
