/*
 * Copyright (c) 2020, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.jdbc;

import org.postgresql.core.ParameterList;
import org.postgresql.test.jdbc2.BaseTest4;

import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;
import java.sql.SQLException;

public class PgPreparedStatementTest extends BaseTest4 {

  private static PgPreparedStatement preparedStatement;

  private static String preparedParametersVariableName = "preparedParameters";

  /**
   * Test for case of retrieving preparedParameters from PgPreparedStatement object. Checks if
   * returned ParameterList is the same as ParameterList object inside preparedStatement.
   */
  @Test
  public void testGetPreparedParameters()
      throws SQLException, NoSuchFieldException, IllegalAccessException {
    preparedStatement = (PgPreparedStatement) con
      .prepareStatement("SELECT * from function(?,?,?,?)");

    ParameterList expectedParameterList = getPreparedParameters();
    ParameterList actualParameterList = preparedStatement.getPreparedParameters();

    Assert.assertEquals(expectedParameterList, actualParameterList);
  }

  /**
   * Get preparedParameters from preparedStatement via reflection.
   *
   * @return preparedParameters variable from preparedStatement
   */
  public ParameterList getPreparedParameters() throws NoSuchFieldException, IllegalAccessException {
    Field field = preparedStatement.getClass().getDeclaredField(preparedParametersVariableName);
    field.setAccessible(true);
    return (ParameterList) field.get(preparedStatement);
  }

}
