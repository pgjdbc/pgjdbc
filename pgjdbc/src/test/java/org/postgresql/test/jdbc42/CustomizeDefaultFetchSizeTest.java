/*
 * Copyright (c) 2004, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.test.jdbc42;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import org.postgresql.PGProperty;
import org.postgresql.test.TestUtil;

import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

class CustomizeDefaultFetchSizeTest {

  private Connection connection;

  @AfterEach
  void tearDown() throws Exception {
    if (connection != null) {
      TestUtil.closeDB(connection);
    }
  }

  @Test
  void setPredefineDefaultFetchSizeOnStatement() throws Exception {
    final int waitFetchSize = 13;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, waitFetchSize);

    connection = TestUtil.openDB(properties);

    Statement statement = connection.createStatement();
    int resultFetchSize = statement.getFetchSize();

    statement.close();

    assertThat(
        "PGProperty.DEFAULT_ROW_FETCH_SIZE should be propagate to Statement that was create from connection "
            + "on that define it parameter",
        resultFetchSize, CoreMatchers.equalTo(waitFetchSize));
  }

  @Test
  void setPredefineDefaultFetchSizeOnPreparedStatement() throws Exception {
    final int waitFetchSize = 14;

    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, waitFetchSize);

    connection = TestUtil.openDB(properties);

    CallableStatement statement = connection.prepareCall("{ call unnest(array[1, 2, 3, 5])}");
    int resultFetchSize = statement.getFetchSize();

    assertThat(
        "PGProperty.DEFAULT_ROW_FETCH_SIZE should be propagate to CallableStatement that was create from connection "
            + "on that define it parameter",
        resultFetchSize, CoreMatchers.equalTo(waitFetchSize));
  }

  @Test
  void notAvailableSpecifyNegativeFetchSize() throws Exception {
    assertThrows(SQLException.class, () -> {
      Properties properties = new Properties();
      PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, Integer.MIN_VALUE);

      connection = TestUtil.openDB(properties);

      fail(
          "On step initialize connection we know about not valid parameter PGProperty.DEFAULT_ROW_FETCH_SIZE they can't be negative, "
              + "so we should throw correspond exception about it rather than fall with exception in runtime for example during create statement");
    });
  }
}
