/*
 * Copyright (c) 2026, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc3;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.postgresql.test.TestUtil;
import org.postgresql.test.jdbc2.BaseTest4;
import org.postgresql.util.PGobject;
import org.postgresql.util.PSQLState;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;

/**
 * A {@code { ? = call ... }} statement with one registered OUT parameter receives a composite or
 * {@code record} result whole, and with several OUT parameters receives one field in each.
 *
 * <p>{@link BinaryMode#FORCE} runs each test on a server-prepared statement.</p>
 */
@ParameterizedClass
@EnumSource(BaseTest4.BinaryMode.class)
class CallableStatementRowResultTest extends BaseTest4 {

  CallableStatementRowResultTest(BinaryMode binaryMode) {
    setBinaryMode(binaryMode);
  }

  @BeforeAll
  static void createFunctions() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      assumeCallableStatementsSupported(con);
      TestUtil.createCompositeType(con, "callable_row_t", "a int, b text");
      TestUtil.execute(con, "create or replace function callable_row_composite(x int)"
          + " returns callable_row_t language sql as $$select x, 'b'::text$$");
      TestUtil.createCompositeType(con, "callable_row_one_field_t", "a int");
      TestUtil.execute(con, "create or replace function callable_row_one_field(x int)"
          + " returns callable_row_one_field_t language sql"
          + " as $$select row(x)::callable_row_one_field_t$$");
      TestUtil.execute(con, "create or replace function callable_row_record(x int)"
          + " returns record language sql as $$select x, 'b'::text$$");
      TestUtil.execute(con, "create or replace function callable_row_setof(x int)"
          + " returns setof callable_row_t language sql"
          + " as $$select g, 'b'::text from generate_series(x, x + 1) g$$");
      TestUtil.execute(con, "create or replace function callable_row_table(x int)"
          + " returns table(a int, b text) language sql as $$select x, 'b'::text$$");
      TestUtil.execute(con, "create or replace function callable_row_inout(x int,"
          + " inout y int, out z text) language sql as $$select x + y, 'b'::text$$");
      TestUtil.execute(con, "create or replace function callable_row_out_params(x int,"
          + " out a int, out b text) language sql as $$select x, 'b'::text$$");
    }
  }

  @AfterAll
  static void dropFunctions() throws SQLException {
    try (Connection con = TestUtil.openDB()) {
      TestUtil.dropFunction(con, "callable_row_out_params", "x int, out a int, out b text");
      TestUtil.dropFunction(con, "callable_row_inout", "x int, inout y int, out z text");
      TestUtil.dropFunction(con, "callable_row_table", "x int");
      TestUtil.dropFunction(con, "callable_row_setof", "x int");
      TestUtil.dropFunction(con, "callable_row_record", "x int");
      TestUtil.dropFunction(con, "callable_row_one_field", "x int");
      TestUtil.dropFunction(con, "callable_row_composite", "x int");
      TestUtil.dropType(con, "callable_row_one_field_t");
      TestUtil.dropType(con, "callable_row_t");
    }
  }

  /**
   * The composite result used to arrive as two columns, and execute() failed with "A
   * CallableStatement was executed with an invalid number of parameters".
   */
  @Test
  void aCompositeResultIsTheValueOfTheOnlyOutParameter() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_composite(?) }")) {
      cs.registerOutParameter(1, Types.STRUCT);
      cs.setInt(2, 7);
      cs.execute();
      PGobject value = assertInstanceOf(PGobject.class, cs.getObject(1), "getObject(1)");
      assertAll(
          () -> assertEquals("callable_row_t", value.getType(), "getObject(1).getType()"),
          () -> assertEquals("(7,b)", value.getValue(), "getObject(1).getValue()"));
    }
  }

  /**
   * The column type of a composite result is {@code Types.STRUCT}, so a parameter registered as
   * {@code Types.OTHER}, as in issue #3425, does not match it.
   */
  @Test
  void aCompositeResultRegisteredAsOtherIsATypeMismatch() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_composite(?) }")) {
      cs.registerOutParameter(1, Types.OTHER);
      cs.setInt(2, 7);
      SQLException e = assertThrows(SQLException.class, cs::execute);
      assertEquals(PSQLState.DATA_TYPE_MISMATCH.getState(), e.getSQLState(), "execute() SQLState");
    }
  }

  @Test
  void theFirstRowOfASetOfCompositesIsTheValueOfTheOnlyOutParameter() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_setof(?) }")) {
      cs.registerOutParameter(1, Types.STRUCT);
      cs.setInt(2, 7);
      cs.execute();
      PGobject value = assertInstanceOf(PGobject.class, cs.getObject(1), "getObject(1)");
      assertEquals("(7,b)", value.getValue(), "getObject(1).getValue()");
    }
  }

  @Test
  void aTableResultIsTheValueOfTheOnlyOutParameter() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_table(?) }")) {
      cs.registerOutParameter(1, Types.OTHER);
      cs.setInt(2, 7);
      cs.execute();
      PGobject value = assertInstanceOf(PGobject.class, cs.getObject(1), "getObject(1)");
      assertAll(
          () -> assertEquals("record", value.getType(), "getObject(1).getType()"),
          () -> assertEquals("(7,b)", value.getValue(), "getObject(1).getValue()"));
    }
  }

  /**
   * The function used to be called in {@code FROM}, and the server rejected it with "a column
   * definition list is required for functions returning "record"".
   */
  @Test
  void aDeclaredRecordResultIsTheValueOfTheOnlyOutParameter() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_record(?) }")) {
      cs.registerOutParameter(1, Types.OTHER);
      cs.setInt(2, 7);
      cs.execute();
      PGobject value = assertInstanceOf(PGobject.class, cs.getObject(1), "getObject(1)");
      assertAll(
          () -> assertEquals("record", value.getType(), "getObject(1).getType()"),
          () -> assertEquals("(7,b)", value.getValue(), "getObject(1).getValue()"));
    }
  }

  /**
   * The record of the two OUT parameters used to arrive as two columns, and execute() failed with
   * "A CallableStatement was executed with an invalid number of parameters".
   */
  @Test
  void aRecordOfOutParametersIsTheValueOfTheOnlyOutParameter() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_out_params(?) }")) {
      cs.registerOutParameter(1, Types.OTHER);
      cs.setInt(2, 7);
      cs.execute();
      PGobject value = assertInstanceOf(PGobject.class, cs.getObject(1), "getObject(1)");
      assertAll(
          () -> assertEquals("record", value.getType(), "getObject(1).getType()"),
          () -> assertEquals("(7,b)", value.getValue(), "getObject(1).getValue()"));
    }
  }

  /**
   * A composite type with one field is a composite value too, not its field. Before this rule a
   * parameter registered as {@code Types.INTEGER} received the field.
   */
  @Test
  void aSingleFieldCompositeIsTheValueOfTheOnlyOutParameter() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_one_field(?) }")) {
      cs.registerOutParameter(1, Types.STRUCT);
      cs.setInt(2, 7);
      cs.execute();
      PGobject value = assertInstanceOf(PGobject.class, cs.getObject(1), "getObject(1)");
      assertEquals("(7)", value.getValue(), "getObject(1).getValue()");
    }
  }

  @Test
  void aSingleFieldCompositeRegisteredAsItsFieldTypeIsATypeMismatch() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_one_field(?) }")) {
      cs.registerOutParameter(1, Types.INTEGER);
      cs.setInt(2, 7);
      SQLException e = assertThrows(SQLException.class, cs::execute);
      assertEquals(PSQLState.DATA_TYPE_MISMATCH.getState(), e.getSQLState(), "execute() SQLState");
    }
  }

  /**
   * The function declares two OUT parameters, and the statement registers both: the result
   * parameter receives {@code a} and the third placeholder receives {@code b}.
   */
  @Test
  void eachOutParameterOfTheFunctionFillsOneRegisteredOutParameter() throws SQLException {
    try (CallableStatement cs = con.prepareCall("{ ? = call callable_row_out_params(?, ?) }")) {
      cs.registerOutParameter(1, Types.INTEGER);
      cs.setInt(2, 7);
      cs.registerOutParameter(3, Types.VARCHAR);
      cs.execute();
      assertAll(
          () -> assertEquals(7, cs.getInt(1), "getInt(1)"),
          () -> assertEquals("b", cs.getString(3), "getString(3)"));
    }
  }

  /**
   * The statement cache keeps the form for two or more OUT parameters apart from the other form of
   * the same SQL, so a later statement with one OUT parameter still receives the record whole.
   */
  @Test
  void aStatementWithOneOutParameterAfterOneWithTwoReceivesTheRecordWhole() throws SQLException {
    String sql = "{ ? = call callable_row_inout(?, ?) }";
    try (CallableStatement twoOutParameters = con.prepareCall(sql)) {
      twoOutParameters.registerOutParameter(1, Types.INTEGER);
      twoOutParameters.setInt(2, 7);
      twoOutParameters.setInt(3, 1);
      twoOutParameters.registerOutParameter(3, Types.VARCHAR);
      twoOutParameters.execute();
    }
    try (CallableStatement oneOutParameter = con.prepareCall(sql)) {
      oneOutParameter.registerOutParameter(1, Types.OTHER);
      oneOutParameter.setInt(2, 7);
      oneOutParameter.setInt(3, 1);
      oneOutParameter.execute();
      PGobject value = assertInstanceOf(PGobject.class, oneOutParameter.getObject(1),
          "getObject(1)");
      assertEquals("(8,b)", value.getValue(), "getObject(1).getValue()");
    }
  }
}
