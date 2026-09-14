/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.postgresql.core.PGStreamTestSupport.assertBroken;
import static org.postgresql.core.PGStreamTestSupport.openStream;

import org.postgresql.test.util.FakeSocket;
import org.postgresql.util.GT;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;

/**
 * A CopyData message longer than {@code maxCopyDataSize} is rejected with a
 * {@link PSQLException}, and the stream is then broken. While the property is unset the limit is
 * 64 MB (64000000 bytes), which {@link ProtocolHardeningMode#DISABLE} switches off; a value set
 * through {@link PGStream#setMaxCopyDataSize(String)} applies in every mode.
 *
 * <p>{@link PGStream#checkCopyDataSize(int)} takes the declared length as an argument, so no
 * message bytes are needed. Every test sets the mode it depends on, so the outcome does not
 * change with {@code -Dpgjdbc.protocolHardeningMode}.</p>
 */
class PGStreamCopyDataSizeTest {

  // Message ids copied verbatim from PGStream, so each assertion pins which of the two limits
  // rejected the message while surviving a translation refresh.
  private static final String BUILT_IN_LIMIT =
      "Protocol error. CopyData message has length {0}, which exceeds the built-in limit of {1} bytes. Raise the {2} connection property if the backend legitimately sends more, or set -D{3}=disable to skip these limits altogether.";
  private static final String CONFIGURED_LIMIT =
      "CopyData message has length {0}, which exceeds the maxCopyDataSize limit of {1} bytes.";

  @Test
  void aCopyDataAtTheBuiltInLimitIsAccepted() throws SQLException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);

    stream.checkCopyDataSize(64000000);

    assertFalse(stream.isClosed(), "isClosed()");
  }

  @Test
  void aCopyDataOverTheBuiltInLimitBreaksTheStream() {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);

    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> stream.checkCopyDataSize(64000001));

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(BUILT_IN_LIMIT, "64000001", "64000000", "maxCopyDataSize",
            "pgjdbc.protocolHardeningMode"), e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  /** 1073741823 is the largest length {@code readMessageLength} returns. */
  @Test
  void disableSwitchesTheBuiltInLimitOff() throws SQLException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(ProtocolHardeningMode.DISABLE);

    stream.checkCopyDataSize(1073741823);

    assertAll(
        () -> assertFalse(stream.isClosed(), "isClosed()"),
        () -> assertFalse(socket.closed, "socket closed"));
  }

  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aCopyDataAtAConfiguredLimitIsAccepted(ProtocolHardeningMode mode) throws SQLException {
    PGStream stream = openStream(new FakeSocket());
    stream.setProtocolHardeningMode(mode);
    stream.setMaxCopyDataSize("1000");

    stream.checkCopyDataSize(1000);

    assertFalse(stream.isClosed(), "isClosed()");
  }

  /** The message offers no way to silence the limit, because the user chose the number. */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void aCopyDataOverAConfiguredLimitBreaksTheStreamInEveryMode(ProtocolHardeningMode mode)
      throws SQLException {
    FakeSocket socket = new FakeSocket();
    PGStream stream = openStream(socket);
    stream.setProtocolHardeningMode(mode);
    stream.setMaxCopyDataSize("1000");

    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> stream.checkCopyDataSize(1001));

    assertAll(
        () -> assertEquals(PSQLState.COMMUNICATION_ERROR.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(CONFIGURED_LIMIT, "1001", "1000"), e.getMessage()),
        () -> assertBroken(stream, socket));
  }

  @Test
  void aConfiguredLimitAboveTheBuiltInOneRaisesIt() throws SQLException {
    PGStream stream = openStream(new FakeSocket());
    stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    stream.setMaxCopyDataSize("65000000");

    stream.checkCopyDataSize(64000001);

    assertFalse(stream.isClosed(), "isClosed()");
  }

  /**
   * The suffixes are decimal, so {@code 64M} sets the same boundary as the built-in limit. The
   * message over it is the one for a configured limit, which shows the value took effect.
   */
  @ParameterizedTest
  @EnumSource(ProtocolHardeningMode.class)
  void sixtyFourMSetsTheBoundaryAt64000000Bytes(ProtocolHardeningMode mode) throws SQLException {
    PGStream stream = openStream(new FakeSocket());
    stream.setProtocolHardeningMode(mode);
    stream.setMaxCopyDataSize("64M");

    stream.checkCopyDataSize(64000000);
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> stream.checkCopyDataSize(64000001));

    assertEquals(GT.tr(CONFIGURED_LIMIT, "64000001", "64000000"), e.getMessage());
  }

  /**
   * The 1001-byte message is accepted, so the earlier value is gone, and the 64000001-byte one
   * gets the built-in message.
   */
  @ParameterizedTest
  @NullAndEmptySource
  void anUnsetValueRestoresTheBuiltInLimit(String value) throws SQLException {
    PGStream stream = openStream(new FakeSocket());
    stream.setProtocolHardeningMode(ProtocolHardeningMode.FAIL);
    stream.setMaxCopyDataSize("1000");

    stream.setMaxCopyDataSize(value);
    stream.checkCopyDataSize(1001);
    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> stream.checkCopyDataSize(64000001));

    assertEquals(GT.tr(BUILT_IN_LIMIT, "64000001", "64000000", "maxCopyDataSize",
        "pgjdbc.protocolHardeningMode"), e.getMessage());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-1"})
  void aValueThatIsNotPositiveIsRejected(String value) {
    PGStream stream = openStream(new FakeSocket());

    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> stream.setMaxCopyDataSize(value));

    assertAll(
        () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(
            "The {0} connection property must be a positive size, but its value is {1}. Give a byte count such as 150M or a share of the heap such as 10p, or leave the property unset.",
            "maxCopyDataSize", value), e.getMessage()));
  }

  /** {@code 3000000000K} is rejected because 3000000000 overflows an {@code int}. */
  @ParameterizedTest
  @ValueSource(strings = {"abcM", "3000000000K"})
  void aValueOutsideTheSizeSyntaxIsRejected(String value) {
    PGStream stream = openStream(new FakeSocket());

    PSQLException e = assertThrowsExactly(PSQLException.class,
        () -> stream.setMaxCopyDataSize(value));

    assertAll(
        () -> assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(), "SQLState"),
        () -> assertEquals(GT.tr(
            "The {0} connection property has the value {1}, which is not a valid size. Give a byte count such as 150M or a share of the heap with the p suffix, such as 10p.",
            "maxCopyDataSize", value), e.getMessage()));
  }
}
