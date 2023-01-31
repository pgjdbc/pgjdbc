/*
 * Copyright (c) 2023, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.core;

import org.postgresql.core.BaseConnection;
import org.postgresql.core.QueryExecutor;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Test;

import java.sql.SQLException;
import java.util.Set;

/**
 * TestCase to test handling of binary types.
 */
public class QueryExecutorTest extends BaseTest4 {
  /**
   * Make sure the functions for adding binary transfer OIDs for custom types are correct.
   *
   * @throws SQLException if a database error occurs
   */
  @Test
  public void testBinaryTransferOids() throws SQLException {
    QueryExecutor queryExecutor = con.unwrap(BaseConnection.class).getQueryExecutor();
    // get current OIDs (make a copy of them)
    @SuppressWarnings("deprecation")
    Set<? extends Integer> oidsReceive = queryExecutor.getBinaryReceiveOids();
    @SuppressWarnings("deprecation")
    Set<? extends Integer> oidsSend = queryExecutor.getBinarySendOids();
    // add a new OID to be transferred as binary data
    int customTypeOid = 91716;
    assertBinaryForReceive(customTypeOid, false,
        () -> "Custom type OID should not be binary for receive by default");
    // first for receiving
    queryExecutor.addBinaryReceiveOid(customTypeOid);
    // Verify
    assertBinaryForReceive(customTypeOid, true,
        () -> "Just added oid via addBinaryReceiveOid");
    assertBinaryForSend(customTypeOid, false,
        () -> "Just added oid via addBinaryReceiveOid");
    for (int oid : oidsReceive) {
      assertBinaryForReceive(oid, true,
          () -> "Previously registered BinaryReceiveOids should be intact after "
              + "addBinaryReceiveOid(" + customTypeOid + ")");
    }
    for (int oid : oidsSend) {
      assertBinaryForSend(oid, true,
          () -> "Previously registered BinarySendOids should be intact after "
              + "addBinaryReceiveOid(" + customTypeOid + ")");
    }
    // then for sending
    queryExecutor.addBinarySendOid(customTypeOid);
    // check new OID
    assertBinaryForReceive(customTypeOid, true, () -> "added oid via addBinaryReceiveOid and "
        + "addBinarySendOid");
    assertBinaryForSend(customTypeOid, true, () -> "added oid via addBinaryReceiveOid and "
        + "addBinarySendOid");
    for (int oid : oidsReceive) {
      assertBinaryForReceive(oid, true, () -> "Previously registered BinaryReceiveOids should be "
          + "intact after addBinaryReceiveOid(" + customTypeOid + ") and addBinarySendOid(" + customTypeOid + ")");
    }
    for (int oid : oidsSend) {
      assertBinaryForSend(oid, true, () -> "Previously registered BinarySendOids should be intact"
          + " after addBinaryReceiveOid(" + customTypeOid + ")");
    }
  }
}
