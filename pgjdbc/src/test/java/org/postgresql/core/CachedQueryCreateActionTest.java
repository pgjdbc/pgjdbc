/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.postgresql.jdbc.PgConnection;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.sql.SQLException;

public class CachedQueryCreateActionTest {

  private PgConnection connection;

  @Before
  public void setUp() throws Exception {
    connection = (PgConnection) TestUtil.openDB();
  }

  @After
  public void tearDown() throws SQLException {
    TestUtil.closeDB(connection);
  }

  @Test
  public void testCreate() throws SQLException, IOException {
    CachedQuery cachedQuery = connection.getQueryExecutor().createQueryByKey("select * from mytable where param1=?");
    assertCachedQuery(cachedQuery);
    assertEquals(SqlCommandType.SELECT, cachedQuery.query.getSqlCommand().getType());
    assertEquals("select * from mytable where param1=$1", cachedQuery.query.getNativeSql());
  }

  @Test
  public void testCreateWithComment() throws SQLException, IOException {
    CachedQuery cachedQuery = connection.getQueryExecutor().createQueryByKey("/*yeah! ? {d '2001-10-09'}*/ select * from mytable where param1=?");
    assertCachedQuery(cachedQuery);
    assertEquals(SqlCommandType.SELECT, cachedQuery.query.getSqlCommand().getType());
    assertEquals("/*yeah! ? {d '2001-10-09'}*/ select * from mytable where param1=$1", cachedQuery.query.getNativeSql());
  }

  @Test
  public void testCreateProcedureCall() throws SQLException, IOException {
    CallableQueryKey cqk = new CallableQueryKey("{ ? = call pack_getValue(?) }");
    CachedQuery cachedQuery = connection.getQueryExecutor().createQueryByKey(cqk);
    assertCachedQuery(cachedQuery);
    assertEquals(SqlCommandType.SELECT, cachedQuery.query.getSqlCommand().getType());
    assertEquals("select * from pack_getValue($1,$2)  as result", cachedQuery.query.getNativeSql());
  }

  @Test
  public void testCreateWithEscape() throws SQLException, IOException {
    CachedQuery cachedQuery = connection.getQueryExecutor().createQueryByKey("select yeahfield from x where d={d '2001-10-09'} and param1=?");
    assertCachedQuery(cachedQuery);
    assertEquals(SqlCommandType.SELECT, cachedQuery.query.getSqlCommand().getType());
    assertEquals("select yeahfield from x where d=DATE '2001-10-09' and param1=$1", cachedQuery.query.getNativeSql());
  }

  private void assertCachedQuery(CachedQuery cachedQuery) {
    assertNotNull(cachedQuery);
    assertNotNull(cachedQuery.query);
    assertNotNull(cachedQuery.query.getSqlCommand());
  }
}
