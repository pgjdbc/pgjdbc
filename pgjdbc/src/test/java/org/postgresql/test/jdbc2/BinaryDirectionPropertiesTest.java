/*
 * Copyright (c) 2024, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.postgresql.PGProperty;
import org.postgresql.core.BaseConnection;
import org.postgresql.core.Oid;
import org.postgresql.core.QueryExecutor;
import org.postgresql.test.TestUtil;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Tests for the per-direction, per-type {@code binarySend} and {@code binaryReceive} properties,
 * including their precedence over the legacy {@code binaryTransfer*} properties.
 */
class BinaryDirectionPropertiesTest {

  private static QueryExecutor executorOf(Connection con) throws SQLException {
    return con.unwrap(BaseConnection.class).getQueryExecutor();
  }

  @Test
  void forceAddsSendBinaryForType() throws SQLException {
    Properties props = new Properties();
    // Start from nothing so the only send-binary type is the one we force.
    PGProperty.BINARY_TRANSFER.set(props, false);
    PGProperty.BINARY_SEND.set(props, "int4:force");
    try (Connection con = TestUtil.openDB(props)) {
      QueryExecutor executor = executorOf(con);
      assertTrue(executor.useBinaryForSend(Oid.INT4), "int4:force should enable binary send");
      assertFalse(executor.useBinaryForReceive(Oid.INT4),
          "binaryReceive untouched and binaryTransfer=false, so receive stays text");
    }
  }

  @Test
  void forceAcceptsNumericOid() throws SQLException {
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER.set(props, false);
    // 23 is the OID of int4.
    PGProperty.BINARY_SEND.set(props, "23:force");
    try (Connection con = TestUtil.openDB(props)) {
      assertTrue(executorOf(con).useBinaryForSend(Oid.INT4),
          "numeric OID 23:force should enable binary send for int4");
    }
  }

  @Test
  void disableRemovesReceiveBinaryFromDefault() throws SQLException {
    Properties props = new Properties();
    // int4 is a default binary type; disable it on receive only.
    PGProperty.BINARY_RECEIVE.set(props, "int4:disable");
    try (Connection con = TestUtil.openDB(props)) {
      QueryExecutor executor = executorOf(con);
      assertFalse(executor.useBinaryForReceive(Oid.INT4),
          "int4:disable should turn off binary receive");
      assertTrue(executor.useBinaryForSend(Oid.INT4),
          "send direction is untouched and stays at the default");
    }
  }

  @Test
  void autoKeepsDriverDefault() throws SQLException {
    Properties props = new Properties();
    PGProperty.BINARY_SEND.set(props, "int4:auto");
    PGProperty.BINARY_RECEIVE.set(props, "int4:auto");
    try (Connection con = TestUtil.openDB(props)) {
      QueryExecutor executor = executorOf(con);
      assertTrue(executor.useBinaryForSend(Oid.INT4), "auto keeps the default (binary) for send");
      assertTrue(executor.useBinaryForReceive(Oid.INT4),
          "auto keeps the default (binary) for receive");
    }
  }

  @Test
  void autoResetsLegacyEnableToDefault() throws SQLException {
    Properties props = new Properties();
    // bool is not a default binary type, so the legacy enable is what turns send on.
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "bool");
    PGProperty.BINARY_SEND.set(props, "bool:auto");
    try (Connection con = TestUtil.openDB(props)) {
      QueryExecutor executor = executorOf(con);
      assertFalse(executor.useBinaryForSend(Oid.BOOL),
          "auto should override binaryTransferEnable and reset bool to its default (text) for send");
      assertTrue(executor.useBinaryForReceive(Oid.BOOL),
          "receive has no override, so binaryTransferEnable=bool still applies");
    }
  }

  @Test
  void autoResetsLegacyDisableToDefault() throws SQLException {
    Properties props = new Properties();
    // int4 is a default binary type; the legacy disable turns it off.
    PGProperty.BINARY_TRANSFER_DISABLE.set(props, "int4");
    PGProperty.BINARY_RECEIVE.set(props, "int4:auto");
    try (Connection con = TestUtil.openDB(props)) {
      QueryExecutor executor = executorOf(con);
      assertTrue(executor.useBinaryForReceive(Oid.INT4),
          "auto should override binaryTransferDisable and reset int4 to its default (binary)");
      assertFalse(executor.useBinaryForSend(Oid.INT4),
          "send has no override, so binaryTransferDisable=int4 still applies");
    }
  }

  @Test
  void perDirectionForceWinsOverLegacyDisable() throws SQLException {
    Properties props = new Properties();
    PGProperty.BINARY_TRANSFER_DISABLE.set(props, "int4");
    PGProperty.BINARY_SEND.set(props, "int4:force");
    try (Connection con = TestUtil.openDB(props)) {
      QueryExecutor executor = executorOf(con);
      assertTrue(executor.useBinaryForSend(Oid.INT4),
          "binarySend=int4:force should override binaryTransferDisable=int4 for send");
      assertFalse(executor.useBinaryForReceive(Oid.INT4),
          "binaryTransferDisable still applies to receive, which has no override");
    }
  }

  @Test
  void perDirectionDisableWinsOverLegacyEnable() throws SQLException {
    Properties props = new Properties();
    // bool is not a default binary type, so binaryTransferEnable is what turns it on.
    PGProperty.BINARY_TRANSFER_ENABLE.set(props, "bool");
    PGProperty.BINARY_RECEIVE.set(props, "bool:disable");
    try (Connection con = TestUtil.openDB(props)) {
      QueryExecutor executor = executorOf(con);
      assertFalse(executor.useBinaryForReceive(Oid.BOOL),
          "binaryReceive=bool:disable should override binaryTransferEnable=bool for receive");
      assertTrue(executor.useBinaryForSend(Oid.BOOL),
          "send direction keeps binaryTransferEnable=bool");
    }
  }

  @Test
  void forceIsRejectedForReceive() {
    assertInvalidParameterValue("binaryReceive", "int4:force",
        "force is not a valid mode for binaryReceive");
  }

  @Test
  void unknownModeIsRejected() {
    assertInvalidParameterValue("binarySend", "int4:bogus", "an unknown mode should be rejected");
  }

  @Test
  void missingColonIsRejected() {
    assertInvalidParameterValue("binarySend", "int4",
        "an entry without oid:mode syntax should be rejected");
  }

  @Test
  void duplicateTypeIsRejected() {
    assertInvalidParameterValue("binarySend", "int4:force,int4:disable",
        "the same type listed twice in one direction should be rejected");
  }

  /**
   * Asserts that connecting with the given binary direction property fails with
   * {@link PSQLState#INVALID_PARAMETER_VALUE}, so the test cannot pass on an unrelated
   * connection error.
   */
  private static void assertInvalidParameterValue(String property, String value, String message) {
    Properties props = new Properties();
    props.setProperty(property, value);
    SQLException e = assertThrows(SQLException.class, () -> TestUtil.openDB(props).close(), message);
    assertEquals(PSQLState.INVALID_PARAMETER_VALUE.getState(), e.getSQLState(), message);
  }
}
