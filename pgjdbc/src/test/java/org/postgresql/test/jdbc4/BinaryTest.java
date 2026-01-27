/*
 * Copyright (c) 2007, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.postgresql.PGConnection;
import org.postgresql.PGResultSetMetaData;
import org.postgresql.PGStatement;
import org.postgresql.core.Field;
import org.postgresql.jdbc.PgStatement;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * We don't want to use the binary protocol for one-off queries as it involves another round-trip to
 * the server to 'describe' the query. If we use the query enough times (see
 * {@link PGConnection#setPrepareThreshold(int)} then we'll change to using the binary protocol to
 * save bandwidth and reduce decoding time.
 */
public class BinaryTest extends BaseTest4 {
  private ResultSet results;
  private PreparedStatement statement;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    assumeTrue(
        preferQueryMode != PreferQueryMode.SIMPLE,
        "Server-prepared statements are not supported in 'simple protocol only'");
    statement = con.prepareStatement("select 1");

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  @Test
  public void testPreparedStatement_3() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(3);

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  @Test
  public void testPreparedStatement_1() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(1);

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  @Test
  public void testPreparedStatement_0() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(0);

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.TEXT_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  @Test
  public void testPreparedStatement_negative1() throws Exception {
    ((PGStatement) statement).setPrepareThreshold(-1);

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    results = statement.executeQuery();
    assertEquals(Field.BINARY_FORMAT, getFormat(results));

    ((PGStatement) statement).setPrepareThreshold(5);
  }

  @Test
  public void testReceiveBinary() throws Exception {
    PreparedStatement ps = con.prepareStatement("select ?");
    for (int i = 0; i < 10; i++) {
      ps.setInt(1, 42 + i);
      ResultSet rs = ps.executeQuery();
      assertTrue(rs.next(), "One row should be returned");
      assertEquals(42 + i, rs.getInt(1));
      rs.close();
    }
    ps.close();
  }

  @Test
  public void testGetMetaDataBeforeExecuteQuery() throws SQLException {
    PreparedStatement ps = null;
    ResultSet rs = null;
    try {
      ps = con.prepareStatement("select ?::int8");
      PgStatement unwrap = ps.unwrap(PgStatement.class);
      unwrap.setPrepareThreshold(-1);
      ps.getMetaData();
      long paramsLong = 1000L;
      ps.setLong(1, paramsLong);
      rs = ps.executeQuery();
      assertTrue(rs.next(), "One row should be returned");
      byte[] bytes = rs.getBytes(1);
      ByteBuffer bf = ByteBuffer.wrap(bytes);
      long longResult = bf.getLong();
      assertEquals(Field.BINARY_FORMAT, getFormat(rs));
      assertEquals(paramsLong, longResult);
    } finally {
      if (rs != null) {
        rs.close();
      }
      if (ps != null) {
        ps.close();
      }
    }

  }

  private static int getFormat(ResultSet results) throws SQLException {
    return ((PGResultSetMetaData) results.getMetaData()).getFormat(1);
  }
}
