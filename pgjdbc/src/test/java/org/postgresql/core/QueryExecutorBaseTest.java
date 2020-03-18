/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import org.postgresql.copy.CopyOperation;
import org.postgresql.jdbc.BatchResultHandler;
import org.postgresql.test.TestUtil;
import org.postgresql.util.HostSpec;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;

import javax.net.SocketFactory;

public class QueryExecutorBaseTest {

  private PGStream pgStream;
  private QueryExecutorBase queryExecutor;

  /**
   * Setup to create object of QueryExecutorBase class.
   */
  @Before
  public void setUp() throws IOException, SQLException {
    pgStream = new PGStream(SocketFactory.getDefault(),
      new HostSpec(TestUtil.getServer(), TestUtil.getPort()), 0);
    queryExecutor = new QueryExecutorBaseMock(pgStream, TestUtil.getUser(), TestUtil.getDatabase(),
      0, new Properties());
  }

  /**
   * Test to check work of getPgStream() method.
   */
  @Test
  public void testGetPgStream() {
    Assert.assertEquals(pgStream, queryExecutor.getPgStream());
  }

  /**
   * Mockup extending QueryExecutorBase for testing.
   */
  private class QueryExecutorBaseMock extends QueryExecutorBase {

    protected QueryExecutorBaseMock(PGStream pgStream, String user, String database,
        int cancelSignalTimeout, Properties info) throws SQLException {
      super(pgStream, user, database, cancelSignalTimeout, info);
    }

    @Override
    protected void sendCloseMessage() throws IOException {

    }

    @Override
    public void execute(Query query, ParameterList parameters,
        ResultHandler handler, int maxRows, int fetchSize, int flags) throws SQLException {

    }

    @Override
    public void execute(Query[] queries, ParameterList[] parameterLists,
        BatchResultHandler handler, int maxRows, int fetchSize, int flags) throws SQLException {

    }

    @Override
    public void fetch(ResultCursor cursor,
        ResultHandler handler, int fetchSize) throws SQLException {

    }

    @Override
    public Query createSimpleQuery(String sql) throws SQLException {
      return null;
    }

    @Override
    public Query wrap(List<NativeQuery> queries) {
      return null;
    }

    @Override
    public void processNotifies() throws SQLException {

    }

    @Override
    public void processNotifies(int timeoutMillis) throws SQLException {

    }

    @Override
    public ParameterList createFastpathParameters(int count) {
      return null;
    }

    @Override
    public byte[] fastpathCall(int fnid, ParameterList params, boolean suppressBegin)
        throws SQLException {
      return new byte[0];
    }

    @Override
    public CopyOperation startCopy(String sql, boolean suppressBegin) throws SQLException {
      return null;
    }

    @Override
    public int getProtocolVersion() {
      return 0;
    }

    @Override
    public void setBinaryReceiveOids(Set<Integer> useBinaryForOids) {

    }

    @Override
    public void setBinarySendOids(Set<Integer> useBinaryForOids) {

    }

    @Override
    public boolean getIntegerDateTimes() {
      return false;
    }

    @Override
    public TimeZone getTimeZone() {
      return null;
    }

    @Override
    public String getApplicationName() {
      return null;
    }

    @Override
    public ReplicationProtocol getReplicationProtocol() {
      return null;
    }

    @Override
    public boolean useBinaryForSend(int oid) {
      return false;
    }

    @Override
    public boolean useBinaryForReceive(int oid) {
      return false;
    }
  }
}
