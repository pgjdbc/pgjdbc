/*
 * Copyright (c) 2022, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.PGConnection;
import org.postgresql.PGPreparedStatement;
import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest5;
import org.postgresql.test.jdbc2.BatchExecuteTest;
import org.postgresql.util.GT;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

public class NativeParametersTest extends BaseTest5 {

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.PLACEHOLDER_STYLE.set(props, PlaceholderStyle.NATIVE.value());
  }

  @ParameterizedTest
  @CsvSource({"SELECT ?+$1,JDBC,NATIVE",
      "SELECT $1+?,NATIVE,JDBC",
      "SELECT $1+:dummy,NATIVE,NAMED"
  })
  @DisplayName("Mixing placeholder styles must cause an SQLException")
  public void dontMixStyles(String sqlText, String firstStyle, String secondStyle)
      throws Exception {
    final PGConnection pgConnection = con.unwrap(PGConnection.class);
    pgConnection.setPlaceholderStyle(PlaceholderStyle.ANY);
    final SQLException sqlException =
        Assertions.assertThrows(SQLException.class, () -> con.prepareStatement(sqlText));
    Assertions.assertEquals(GT.tr(
        "Placeholder styles cannot be combined. Saw {0} first but attempting to also use: {1}",
        firstStyle, secondStyle), sqlException.getMessage());
  }

  @ParameterizedTest
  @CsvSource({"SELECT $1+$4,$2,'[$1, $4]'",
      "SELECT $2+$0,$1,[$2]",
      "SELECT $2+$-1,$1,[$2]"
  })
  @DisplayName("Specifying non-contiguous placeholders must cause an SQLException")
  public void testNativeParametersContiguous(String sqlText, String missingMsg, String foundMsg)
      throws Exception {
    con.unwrap(PGConnection.class).setPlaceholderStyle(PlaceholderStyle.NATIVE);

    final SQLException sqlException =
        Assertions.assertThrows(SQLException.class, () -> con.prepareStatement(sqlText));
    Assertions.assertEquals(GT.tr(
            "Native parameter {0} was not found.\n"
                + "The following parameters where captured: {1}\n"
                + "Native parameters must form a contiguous set of integers, starting from 1.",
            missingMsg, foundMsg),
        sqlException.getMessage());
  }

  @ParameterizedTest
  @EnumSource(PlaceholderStyle.class)
  public void hasNamedParametersNonePresent(PlaceholderStyle placeholderStyle)
      throws SQLException {
    con.unwrap(PGConnection.class).setPlaceholderStyle(placeholderStyle);

    String sql = "SELECT 'constant'";
    try (PGPreparedStatement testStmt = con.prepareStatement(sql)
        .unwrap(PGPreparedStatement.class)) {
      Assertions.assertFalse(testStmt.hasParameterNames(),
          "Must return false as no parameters are present");
      final SQLException sqlException =
          Assertions.assertThrows(SQLException.class, testStmt::getParameterNames);
      NamedParametersTest.verifyNoParameterMessage(placeholderStyle, sqlException);
    }
  }

  @ParameterizedTest
  @CsvSource({"ANY,true",
      "NAMED,false",
      "NATIVE,true",
      "NONE,false"
  })
  public void hasNamedParameters(PlaceholderStyle placeholderStyle, boolean parameterPresent)
      throws SQLException {
    con.unwrap(PGConnection.class).setPlaceholderStyle(placeholderStyle);

    String sql = "SELECT $1";
    try (PGPreparedStatement testStmt = con.prepareStatement(sql)
        .unwrap(PGPreparedStatement.class)) {
      Assertions.assertEquals(parameterPresent, testStmt.hasParameterNames());

      if (parameterPresent) {
        Assertions.assertEquals(Collections.singletonList("$1"), testStmt.getParameterNames());
      } else {
        final SQLException sqlException =
            Assertions.assertThrows(SQLException.class, testStmt::getParameterNames);
        NamedParametersTest.verifyNoParameterMessage(placeholderStyle, sqlException);
      }
    }
  }

  private static Stream<Arguments> testMultiDigitParameters() {
    return generateMultiDigitParameterArguments(false);
  }

  private static Stream<Arguments> testMultiDigitParametersReuse() {
    return generateMultiDigitParameterArguments(true);
  }

  private static Stream<Arguments> generateMultiDigitParameterArguments(boolean repeated) {
    // Builds a simple SELECT-statement with a lot of placeholders.

    List<Arguments> generatedArguments = new ArrayList<>();
    StringBuilder sb = new StringBuilder();
    sb.append("SELECT ");
    final int numberOfParametersTest = 10;
    for (int i = 0; i <= 1000; i++) {
      if (i > 0 && i % numberOfParametersTest == 0) {
        if (repeated) {
          generatedArguments.add(Arguments.of(sb.toString(), numberOfParametersTest));
        } else {
          generatedArguments.add(Arguments.of(sb.toString(), i));
        }
      }

      if (i > 0) {
        sb.append(",");
      }
      if (repeated) {
        sb.append("$").append(i % numberOfParametersTest + 1);
      } else {
        sb.append("$").append(i + 1);
      }
    }

    return generatedArguments.stream();
  }

  @ParameterizedTest
  @MethodSource
  @DisplayName("Every parameter is unique")
  void testMultiDigitParameters(String sqlText, int parameterCount) throws Exception {
    try (PGPreparedStatement testStmt = con.prepareStatement(sqlText)
        .unwrap(PGPreparedStatement.class)) {
      Assertions.assertTrue(testStmt.hasParameterNames());
      Assertions.assertEquals(parameterCount, testStmt.getParameterNames().size());
    }
  }

  @ParameterizedTest
  @MethodSource
  @DisplayName("Parameters must be reused as the name is repeated")
  void testMultiDigitParametersReuse(String sqlText, int parameterCount) throws Exception {
    try (PGPreparedStatement testStmt = con.prepareStatement(sqlText)
        .unwrap(PGPreparedStatement.class)) {
      Assertions.assertTrue(testStmt.hasParameterNames());
      Assertions.assertEquals(parameterCount, testStmt.getParameterNames().size());
    }
  }

  @Test
  public void setUnknownNativeParameter() throws Exception {
    PreparedStatement preparedStatement = con.prepareStatement("select $1||$2||$3 AS "
        + "teststr");
    PGPreparedStatement ps = preparedStatement.unwrap(PGPreparedStatement.class);
    final String failureParameterName = "$4";
    final SQLException sqlException = Assertions.assertThrows(SQLException.class,
        () -> ps.setString(failureParameterName, "1"), "Must throw as the parameter is not known!");
    Assertions.assertEquals(
        GT.tr("The parameterName was not found : {0}. The following names are known : \n\t {1}",
            failureParameterName, "[$1, $2, $3]"), sqlException.getMessage());
  }

  /**
   * Perform an end-to-end test using native parameters.
   */
  @Test
  @DisplayName("Test parameter reuse during INSERT and UPDATE")
  public void testInsertAndUpdate() throws Exception {
    TestUtil.createTable(con, "test_dates", "pk INTEGER, d1 date, d2 date, d3 date");

    final java.sql.Date sqlDate = java.sql.Date.valueOf(LocalDate.now());

    final String insertSQL = "INSERT INTO test_dates( d1, pk, d2, d3 ) values ( $2, $1, $2, $2 )";
    try (PGPreparedStatement insertStmt = con.prepareStatement(insertSQL)
        .unwrap(PGPreparedStatement.class)) {

      insertStmt.setInt(1, 1);
      insertStmt.setDate(2, sqlDate);
      insertStmt.execute();
    }

    final String sql = "SELECT td.*, $1::DATE AS d4 FROM test_dates td WHERE td.d1 = $1 "
        + "AND $1 BETWEEN td.d2 AND td.d3";
    try (PGPreparedStatement pstmt = con.prepareStatement(sql).unwrap(PGPreparedStatement.class)) {

      pstmt.setDate(1, sqlDate);
      pstmt.execute();

      try (ResultSet resultSet = pstmt.getResultSet()) {
        resultSet.next();

        Assertions.assertEquals(sqlDate, resultSet.getDate("d1"),
            "Must batch the value bound to $2 during INSERT");
        Assertions.assertEquals(sqlDate, resultSet.getDate("d2"),
            "Must batch the value bound to $2 during INSERT");
        Assertions.assertEquals(sqlDate, resultSet.getDate("d3"),
            "Must batch the value bound to $2 during INSERT");
        Assertions.assertEquals(sqlDate, resultSet.getDate("d4"),
            "Must batch the value bound to $1 in the SELECT statement");
      }
    }

    final java.sql.Date sqlDate2 = java.sql.Date.valueOf(LocalDate.now().plus(1,
        ChronoUnit.DAYS));

    final String updateSQL = "UPDATE test_dates\n"
        + "SET d1 = $3, d3 = $3\n"
        + "WHERE pk = $1 AND d1 = $2\n"
        + "RETURNING d1, $2 AS d2, d3, d2 AS d4";
    try (PGPreparedStatement updateStmt = con.prepareStatement(updateSQL)
        .unwrap(PGPreparedStatement.class)) {

      updateStmt.setInt("$1", 1);
      updateStmt.setDate("$2", sqlDate);
      updateStmt.setDate("$3", sqlDate2);
      updateStmt.execute();

      try (ResultSet resultSet = updateStmt.getResultSet()) {
        resultSet.next();

        Assertions.assertEquals(sqlDate2, resultSet.getDate("d1"),
            "d1 was updated to the value of $3");
        Assertions.assertEquals(sqlDate, resultSet.getDate("d2"),
            "The value of $2 is used in the RETURNING clause");
        Assertions.assertEquals(sqlDate2, resultSet.getDate("d3"),
            "d3 was updated to the value of $3");
        Assertions.assertEquals(sqlDate, resultSet.getDate("d4"),
            "d4 is an alias for d2 (contains the value of $2");
      }
    }
  }

  @Test
  public void testToString() throws Exception {
    final String sql = "select $2||$2||$1 AS teststr";
    try (PGPreparedStatement ps = con.prepareStatement(sql).unwrap(PGPreparedStatement.class)) {

      // Test toString before bind
      Assertions.assertEquals(sql, ps.toString(),
          "Equals the input SQL text, as values are not yet bound");

      ps.setString("$1", "1");
      ps.setString("$2", "2");

      // Test toString after bind
      Assertions.assertEquals("select ('2')||('2')||('1') AS teststr", ps.toString(),
          "The bound values must now be present");
      ps.execute();
      try (ResultSet resultSet = ps.getResultSet()) {
        resultSet.next();

        final String testStr = resultSet.getString("testStr");
        Assertions.assertEquals("221", testStr);
      }
    }
  }

  @Test
  public void testBatchToString() throws SQLException {
    // Drop the test table if it already exists for some reason. It is
    // not an error if it doesn't exist.
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER, col2 INTEGER");

    try (PGPreparedStatement pstmt = con.prepareStatement(
            "INSERT INTO testbatch VALUES ($1,$2,$1)")
        .unwrap(PGPreparedStatement.class)) {

      pstmt.setInt("$1", 1);
      pstmt.setInt("$2", 2);
      Assertions.assertEquals("INSERT INTO testbatch VALUES (('1'::int4),('2'::int4),('1'::int4))", pstmt.toString());
      pstmt.addBatch();
      pstmt.setInt("$1", 3);
      pstmt.setInt("$2", 4);
      Assertions.assertEquals("INSERT INTO testbatch VALUES (('3'::int4),('4'::int4),('3'::int4))", pstmt.toString());
      pstmt.addBatch();
      pstmt.setInt("$1", 5);
      pstmt.setInt("$2", 6);
      Assertions.assertEquals("INSERT INTO testbatch VALUES (('5'::int4),('6'::int4),('5'::int4))", pstmt.toString());
      pstmt.addBatch();
    }
  }

  @Test
  @DisplayName("Assign values to native placeholders based on index")
  void setValuesByIndex() throws Exception {
    final String sql = "SELECT $1||$2||$3 AS teststr";
    try (PgPreparedStatement ps = (PgPreparedStatement) con.prepareStatement(sql)) {
      int i = 1;
      for (String name : ps.getParameterNames()) {
        switch (name) {
          case "$1":
            ps.setString(i, "333");
            break;
          case "$2":
            ps.setString(i, "1");
            break;
          case "$3":
            ps.setString(i, "222");
            break;
        }
        i++;
      }
      ps.execute();
      try (ResultSet resultSet = ps.getResultSet()) {
        resultSet.next();

        final String testStr = resultSet.getString("testStr");
        Assertions.assertEquals("3331222", testStr);
      }
    }
  }

  @Test
  public void testBatchWithReWrittenRepeatedInsertStatementOptimizationEnabled()
      throws SQLException {

    // Drop the test table if it already exists for some reason. It is
    // not an error if it doesn't exist.
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER, col2 INTEGER");

    final String sql = "INSERT INTO testbatch VALUES ($1,$2,$1)";
    try (PGPreparedStatement pstmt = con.prepareStatement(sql).unwrap(PGPreparedStatement.class)) {

      pstmt.setInt("$1", 1);
      pstmt.setInt("$2", 2);
      pstmt.addBatch();
      pstmt.setInt("$1", 3);
      pstmt.setInt("$2", 4);
      pstmt.addBatch();
      pstmt.setInt("$1", 5);
      pstmt.setInt("$2", 6);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(3, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 3, "3 rows inserted");

      /*
       * Now check the ps can be reused. The batched statement should be reset
       * and have no knowledge of prior re-written batch. This test uses a
       * different batch size. To test if the driver detects the different size
       * and prepares the statement on with the backend. If not then an
       * exception will be thrown for an unknown prepared statement.
       */
      pstmt.setInt("$1", 1);
      pstmt.setInt("$2", 2);
      pstmt.addBatch();
      pstmt.setInt("$1", 3);
      pstmt.setInt("$2", 4);
      pstmt.addBatch();
      pstmt.setInt("$1", 5);
      pstmt.setInt("$2", 6);
      pstmt.addBatch();
      pstmt.setInt("$1", 7);
      pstmt.setInt("$2", 8);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(4, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 7, "3+4 rows inserted");

      pstmt.setInt("$1", 1);
      pstmt.setInt("$2", 2);
      pstmt.addBatch();
      pstmt.setInt("$1", 3);
      pstmt.setInt("$2", 4);
      pstmt.addBatch();
      pstmt.setInt("$1", 5);
      pstmt.setInt("$2", 6);
      pstmt.addBatch();
      pstmt.setInt("$1", 7);
      pstmt.setInt("$2", 8);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(4, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 11, "3+4+4 rows inserted");
    }

    try (PGPreparedStatement pstmt = con.prepareStatement(
            "SELECT count(*) AS rows FROM testbatch WHERE pk = col2 AND pk <> col1")
        .unwrap(PGPreparedStatement.class)) {
      pstmt.execute();
      try (ResultSet rs = pstmt.getResultSet()) {
        rs.next();
        // There should be 11 rows with pk <> col1 AND pk = col2
        Assertions.assertEquals(11, rs.getInt("rows"));
      }
    }
  }
}
