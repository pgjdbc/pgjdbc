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

import java.lang.reflect.Method;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

class NamedParametersTest extends BaseTest5 {

  @Override
  protected void updateProperties(Properties props) {
    super.updateProperties(props);
    PGProperty.PLACEHOLDER_STYLE.set(props, PlaceholderStyle.NAMED.value());
  }

  @ParameterizedTest
  @CsvSource({"SELECT ?+:dummy,POSITIONAL,NAMED",
      "SELECT :dummy+?,NAMED,POSITIONAL",
      "SELECT :dummy+$1,NAMED,NATIVE"
  })
  @DisplayName("Mixing placeholder styles must cause an SQLException")
  void dontMixStyles(String sqlText, String firstStyle, String secondStyle)
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
      "NAMED,true",
      "NATIVE,false",
      "NONE,false"
  })
  public void hasNamedParameters(PlaceholderStyle placeholderStyle, boolean parameterPresent)
      throws SQLException {
    con.unwrap(PGConnection.class).setPlaceholderStyle(placeholderStyle);

    String sql = "SELECT :myParam";
    try (PGPreparedStatement testStmt = con.prepareStatement(sql)
        .unwrap(PGPreparedStatement.class)) {
      Assertions.assertEquals(parameterPresent, testStmt.hasParameterNames());

      if (parameterPresent) {
        Assertions.assertEquals(Collections.singletonList("myParam"), testStmt.getParameterNames());
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
        sb.append(":p").append(i % numberOfParametersTest + 1);
      } else {
        sb.append(":p").append(i + 1);
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
  public void setUnknownNamedParameter() throws Exception {
    PreparedStatement preparedStatement = con.prepareStatement("select :ASTR||:bStr||:c AS "
        + "teststr");
    PGPreparedStatement ps = preparedStatement.unwrap(PGPreparedStatement.class);
    final String failureParameterName = "BsTr";
    final SQLException sqlException = Assertions.assertThrows(SQLException.class,
        () -> ps.setString(failureParameterName, "1"), "Must throw as the parameter is not known!");
    Assertions.assertEquals(
        GT.tr("The parameterName was not found : {0}. The following names are known : \n\t {1}",
            failureParameterName, "[ASTR, bStr, c]"), sqlException.getMessage());
  }

  /**
   * Perform an end-to-end test using named parameters.
   */
  @Test
  @DisplayName("Test parameter reuse during INSERT and UPDATE")
  void testInsertAndUpdate() throws Exception {
    {
      TestUtil.createTable(con, "test_dates", "pk INTEGER, d1 date, d2 date, d3 date");

      final java.sql.Date sqlDate = java.sql.Date.valueOf(LocalDate.now());
      {
        final String insertSQL = "INSERT INTO test_dates( d1, pk, d2, d3 )\n"
            + "VALUES ( :date, :pk, :date, :date )";
        try (PGPreparedStatement insertStmt =
                 con.prepareStatement(insertSQL).unwrap(PGPreparedStatement.class)) {
          insertStmt.setInt("pk", 1);
          insertStmt.setDate("date", sqlDate);
          insertStmt.execute();
        }
      }

      {
        final String sql = "SELECT td.*, :date::DATE AS d4 FROM test_dates td\n"
            + "WHERE td.d1 = :date AND :date BETWEEN td.d2 AND td.d3";
        try (PGPreparedStatement pstmt = con.prepareStatement(sql)
            .unwrap(PGPreparedStatement.class)) {
          pstmt.setDate("date", sqlDate);
          pstmt.execute();

          try (ResultSet resultSet = pstmt.getResultSet()) {
            resultSet.next();

            Assertions.assertEquals(sqlDate, resultSet.getDate("d1"),
                "Must match the value bound to :date during INSERT!");
            Assertions.assertEquals(sqlDate, resultSet.getDate("d2"),
                "Must match the value bound to :date during INSERT!");
            Assertions.assertEquals(sqlDate, resultSet.getDate("d3"),
                "Must match the value bound to :date during INSERT!");
            Assertions.assertEquals(sqlDate, resultSet.getDate("d4"),
                "Must match the value bound to :date in the SELECT statement!");
          }
        }
      }

      final java.sql.Date sqlDate2 = java.sql.Date.valueOf(LocalDate.now().plus(1,
          ChronoUnit.DAYS));
      {
        final String updateSQL = "UPDATE test_dates\n"
            + "SET d1 = :date2, d3 = :date2\n"
            + "WHERE pk = :pk AND d1 = :date\n"
            + "RETURNING d1, :date AS d2, d3, d2 AS d4";
        try (PGPreparedStatement updateStmt =
                 con.prepareStatement(updateSQL).unwrap(PGPreparedStatement.class)
        ) {

          updateStmt.setInt("pk", 1);
          updateStmt.setDate("date", sqlDate);
          updateStmt.setDate("date2", sqlDate2);
          updateStmt.execute();

          try (ResultSet resultSet = updateStmt.getResultSet()) {
            resultSet.next();

            Assertions.assertEquals(sqlDate2, resultSet.getDate("d1"),
                "d1 was updated to the value of :date2");
            Assertions.assertEquals(sqlDate, resultSet.getDate("d2"),
                "The value of :date is used in the RETURNING clause");
            Assertions.assertEquals(sqlDate2, resultSet.getDate("d3"),
                "d3 was updated to the value of :date2");
            Assertions.assertEquals(sqlDate, resultSet.getDate("d4"),
                "d4 is an alias for d2 (contains the value of :date");
          }
        }
      }
    }
  }

  @Test
  public void testToString() throws Exception {
    {
      final String sql = "select :b||:b||:a AS teststr";
      try (PGPreparedStatement ps = con.prepareStatement(sql).unwrap(PGPreparedStatement.class)) {

        // Test toString before bind
        Assertions.assertEquals(sql, ps.toString(),
            "Equals the input SQL text, as values are not yet bound");

        ps.setString("a", "1");
        ps.setString("b", "2");

        // Test toString after bind
        Assertions.assertEquals("select '2'||'2'||'1' AS teststr", ps.toString(),
            "The bound values must now be present");
        ps.execute();
        try (ResultSet resultSet = ps.getResultSet()) {
          resultSet.next();

          final String testStr = resultSet.getString("testStr");
          Assertions.assertEquals("221", testStr);
        }
      }
    }
  }

  @Test
  @DisplayName("Assign values to named placeholders based on index")
  void setValuesByIndex() throws Exception {
    try (PgPreparedStatement ps =
             (PgPreparedStatement) con.prepareStatement("SELECT :a||:b||:c AS teststr")) {
      int i = 1;
      for (String name : ps.getParameterNames()) {
        switch (name) {
          case "a":
            ps.setString(i, "333");
            break;
          case "b":
            ps.setString(i, "1");
            break;
          case "c":
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
  void testBatchWithReWrittenRepeatedInsertStatementOptimizationEnabled()
      throws SQLException {

    // Drop the test table if it already exists for some reason. It is
    // not an error if it doesn't exist.
    TestUtil.createTable(con, "testbatch", "pk INTEGER, col1 INTEGER, col2 INTEGER");

    try (PGPreparedStatement pstmt = con.prepareStatement(
            "INSERT INTO testbatch VALUES (:int1,:int2,:int1)")
        .unwrap(PGPreparedStatement.class)) {

      pstmt.setInt("int1", 1);
      pstmt.setInt("int2", 2);
      pstmt.addBatch();
      pstmt.setInt("int1", 3);
      pstmt.setInt("int2", 4);
      pstmt.addBatch();
      pstmt.setInt("int1", 5);
      pstmt.setInt("int2", 6);
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
      pstmt.setInt("int1", 1);
      pstmt.setInt("int2", 2);
      pstmt.addBatch();
      pstmt.setInt("int1", 3);
      pstmt.setInt("int2", 4);
      pstmt.addBatch();
      pstmt.setInt("int1", 5);
      pstmt.setInt("int2", 6);
      pstmt.addBatch();
      pstmt.setInt("int1", 7);
      pstmt.setInt("int2", 8);
      pstmt.addBatch();
      BatchExecuteTest.assertSimpleInsertBatch(4, pstmt.executeBatch());
      TestUtil.assertNumberOfRows(con, "testbatch", 7, "3+4 rows inserted");

      pstmt.setInt("int1", 1);
      pstmt.setInt("int2", 2);
      pstmt.addBatch();
      pstmt.setInt("int1", 3);
      pstmt.setInt("int2", 4);
      pstmt.addBatch();
      pstmt.setInt("int1", 5);
      pstmt.setInt("int2", 6);
      pstmt.addBatch();
      pstmt.setInt("int1", 7);
      pstmt.setInt("int2", 8);
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
        Assertions.assertEquals(11, rs.getInt("rows"),
            "There should be 11 rows with pk <> col1 AND pk = col2");
      }
    }
  }

  private static Stream<Arguments> PreparedStatementStatementSettersSource() {
    return Arrays.stream(PreparedStatement.class.getDeclaredMethods())
        .filter(f -> f.getName().startsWith("set"))
        .sorted(Comparator.comparing(Method::getName))
        .map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("PreparedStatementStatementSettersSource")
  void testPGPreparedStatementSetters(Method methodFromPreparedStatement) {
    /*
     Make sure PGPreparedStatement declares the same setXXX-methods as PreparedStatement does:
     Instead of int we need to see a setter method with String as the first parameter in the
     signature:
    */
    final Class<?>[] parameterTypesFromPreparedStatement =
        methodFromPreparedStatement.getParameterTypes();
    Assertions.assertEquals(int.class, parameterTypesFromPreparedStatement[0],
        "First parameter must be int, looks like we didn't get the right method!");
    Class<?>[] wantedParameterTypes = new Class[parameterTypesFromPreparedStatement.length];
    wantedParameterTypes[0] = String.class;
    System.arraycopy(parameterTypesFromPreparedStatement, 1, wantedParameterTypes, 1,
        wantedParameterTypes.length - 1);

    // We will get a NoSuchMethodException here if the method is missing
    Assertions.assertDoesNotThrow(() ->
            PGPreparedStatement.class.getDeclaredMethod(methodFromPreparedStatement.getName(),
                wantedParameterTypes),
        "Setter method missing!");
  }

  private static Stream<Arguments> PgCallableStatementSettersSource() {
    return Arrays.stream(PgPreparedStatement.class.getDeclaredMethods())
        .filter(f -> f.getName().startsWith("set"))
        .filter(f -> f.getParameterCount() > 1 && f.getParameterTypes()[0] == String.class)
        .sorted(Comparator.comparing(Method::getName))
        .map(Arguments::of);
  }

  @ParameterizedTest
  @MethodSource("PgCallableStatementSettersSource")
  void testPgCallableStatementSetters(Method methodFromPgPreparedStatement) {
    /*
      Make sure PgCallableStatement overrides all the setX(String, ..) methods that are inherited
      from PgPreparedStatement but aren't supported there.
     */

    Assertions.assertDoesNotThrow(() ->
            PgCallableStatement.class.getDeclaredMethod(methodFromPgPreparedStatement.getName(),
                methodFromPgPreparedStatement.getParameterTypes()),
        "Setter method missing!");

  }

  static void verifyNoParameterMessage(PlaceholderStyle placeholderStyle,
      SQLException sqlException) {
    Assertions.assertEquals(
        GT.tr(
            "No parameter names are available, you need to call hasParameterNames to verify the"
                + " presence of any names.\n"
                + "Perhaps you need to enable support for named placeholders? Current setting "
                + "is: PLACEHOLDER_STYLE = {0}",
            placeholderStyle), sqlException.getMessage());
  }
}
