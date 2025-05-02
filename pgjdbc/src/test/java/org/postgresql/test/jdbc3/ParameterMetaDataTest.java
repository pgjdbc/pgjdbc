/*
 * Copyright (c) 2005, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.postgresql.PGProperty;
import org.postgresql.core.ParameterList;
import org.postgresql.core.Query;
import org.postgresql.core.QueryExecutor;
import org.postgresql.core.ResultHandler;
import org.postgresql.jdbc.PgConnection;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assume;
import org.junit.Test;
import org.junit.platform.commons.util.ReflectionUtils;
import org.junit.platform.commons.util.ReflectionUtils.HierarchyTraversalMode;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;

@RunWith(Parameterized.class)
public class ParameterMetaDataTest extends BaseTest4 {
  public ParameterMetaDataTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @Parameters(name = "binary = {0}")
  public static Iterable<Object[]> data() {
    Collection<Object[]> ids = new ArrayList<>();
    for (BinaryMode binaryMode : BinaryMode.values()) {
      ids.add(new Object[]{binaryMode});
    }
    return ids;
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    Assume.assumeTrue("simple protocol only does not support describe statement requests",
        preferQueryMode != PreferQueryMode.SIMPLE);
    TestUtil.createTable(con, "parametertest",
        "a int4, b float8, c text, d point, e timestamp with time zone");
  }

  @Override
  public void tearDown() throws SQLException {
    TestUtil.dropTable(con, "parametertest");
    super.tearDown();
  }

  @Test
  public void testParameterMD() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ? AND d >^ ? ");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    assertEquals(3, pmd.getParameterCount());
    assertEquals(Types.DOUBLE, pmd.getParameterType(1));
    assertEquals("float8", pmd.getParameterTypeName(1));
    assertEquals("java.lang.Double", pmd.getParameterClassName(1));
    assertEquals(Types.VARCHAR, pmd.getParameterType(2));
    assertEquals("text", pmd.getParameterTypeName(2));
    assertEquals("java.lang.String", pmd.getParameterClassName(2));
    assertEquals(Types.OTHER, pmd.getParameterType(3));
    assertEquals("point", pmd.getParameterTypeName(3));
    assertEquals("org.postgresql.geometric.PGpoint", pmd.getParameterClassName(3));

    pstmt.close();
  }

  @Test
  public void testFailsOnBadIndex() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE b = ? AND c = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();
    try {
      pmd.getParameterType(0);
      fail("Can't get parameter for index < 1.");
    } catch (SQLException sqle) {
    }
    try {
      pmd.getParameterType(3);
      fail("Can't get parameter for index 3 with only two parameters.");
    } catch (SQLException sqle) {
    }
  }

  // Make sure we work when mashing two queries into a single statement.
  @Test
  public void testMultiStatement() throws SQLException {
    PreparedStatement pstmt = con.prepareStatement(
        "SELECT a FROM parametertest WHERE b = ? AND c = ? ; SELECT b FROM parametertest WHERE a = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    assertEquals(3, pmd.getParameterCount());
    assertEquals(Types.DOUBLE, pmd.getParameterType(1));
    assertEquals("float8", pmd.getParameterTypeName(1));
    assertEquals(Types.VARCHAR, pmd.getParameterType(2));
    assertEquals("text", pmd.getParameterTypeName(2));
    assertEquals(Types.INTEGER, pmd.getParameterType(3));
    assertEquals("int4", pmd.getParameterTypeName(3));

    pstmt.close();

  }

  // Here we test that we can legally change the resolved type
  // from text to varchar with the complicating factor that there
  // is also an unknown parameter.
  //
  @Test
  public void testTypeChangeWithUnknown() throws SQLException {
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE c = ? AND e = ?");
    ParameterMetaData pmd = pstmt.getParameterMetaData();

    pstmt.setString(1, "Hi");
    pstmt.setTimestamp(2, new Timestamp(0L));

    ResultSet rs = pstmt.executeQuery();
    rs.close();
  }

  @Test
  public void testGetParameterMetadata_cached() throws SQLException, IllegalAccessException {
    // given
    PreparedStatement pstmt =
        con.prepareStatement("SELECT a FROM parametertest WHERE c = ? AND e = ?");

    pstmt.setString(1, "anything");
    pstmt.setTimestamp(2, new Timestamp(0L));

    int expectedAmountOfExecutions =
        binaryMode == BinaryMode.FORCE
            ? 1
            : Integer.parseInt(PGProperty.PREPARE_THRESHOLD.getDefaultValue());

    for (int i = 0; i < expectedAmountOfExecutions; i++) {
      pstmt.executeQuery();
    }

    // and
    setUpSpyOnQueryExecutor();
    QueryExecutor spyOnQueryExecutor = con.unwrap(PgConnection.class).getQueryExecutor();

    // when
    ParameterMetaData firstCall = pstmt.getParameterMetaData();
    ParameterMetaData secondCall = pstmt.getParameterMetaData();
    ParameterMetaData thirdCall = pstmt.getParameterMetaData();

    // then
    // We're not expecting any interactions with the QueryExecutor#execute when the statement
    // is already prepared on the server side.
    Mockito
        .verify(spyOnQueryExecutor, Mockito.never())
        .execute(
            Mockito.any(Query.class),
            Mockito.any(ParameterList.class),
            Mockito.any(ResultHandler.class),
            Mockito.anyInt(),
            Mockito.anyInt(),
            Mockito.anyInt()
        );

    // However, we d expect that for each three invocations of getParameterMetaData there would be a
    // corresponding call to QueryExecutor#requiresDescribe
    Mockito
        .verify(spyOnQueryExecutor, Mockito.times(3))
        .requiresDescribe(
            Mockito.any(Query.class),
            Mockito.any(ParameterList.class)
        );
  }

  private QueryExecutor setUpSpyOnQueryExecutor() throws SQLException, IllegalAccessException {
    PgConnection pgConnection = con.unwrap(PgConnection.class);

    Field queryExecutorField = ReflectionUtils.findFields(
        PgConnection.class,
        field -> field.getName().equals("queryExecutor"),
        HierarchyTraversalMode.TOP_DOWN
    ).get(0);

    queryExecutorField.setAccessible(true);
    QueryExecutor originalQueryExecutor = (QueryExecutor) queryExecutorField.get(pgConnection);

    // this check is necessary to not spy on spy.
    // It is required since the connection is shared between the tests, and so is QueryExecutor bind to it.
    if (Mockito.mockingDetails(originalQueryExecutor).isSpy()) {
      Mockito.clearInvocations(originalQueryExecutor);
    } else {
      QueryExecutor spyOnQueryExecutor = Mockito.spy(originalQueryExecutor);
      queryExecutorField.set(pgConnection, spyOnQueryExecutor);
      return spyOnQueryExecutor;
    }

    return originalQueryExecutor;
  }
}
