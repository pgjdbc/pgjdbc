/*
 * Copyright (c) 2016, PostgreSQL Global Development Group
 * See the LICENSE file in the project root for more information.
 */

package org.postgresql.core.fetchsize;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assume.assumeThat;

import org.postgresql.PGProperty;
import org.postgresql.jdbc.PreferQueryMode;
import org.postgresql.test.TestUtil;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;


public class AdaptiveFetchSizeTest {

  private Connection connection;

  @Before
  public void setUp() throws Exception {
    String queryModeStr = PGProperty.PREFER_QUERY_MODE.get(System.getProperties());
    PreferQueryMode queryMode = PreferQueryMode.of(queryModeStr);


    assumeThat("FetchSize parameter work only with extended query protocol(v3)",
        queryMode,
        allOf(
            not(equalTo(PreferQueryMode.EXTENDED_FOR_PREPARED)),
            not(equalTo(PreferQueryMode.SIMPLE))
        )
    );
  }

  @After
  public void tearDown() throws Exception {
    if (connection != null) {
      TestUtil.closeDB(connection);
    }
  }

  @Test
  public void testResultFetchSizeFirstlyEqualToInitFetchSize() throws Exception {
    final int initFetchSize = 5;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, initFetchSize);
    PGProperty.DEFAULT_ROW_FETCH_SIZE_IN_BYTES.set(properties, "100");

    connection = openConnection(properties);
    connection.setAutoCommit(false);

    Statement statement = connection.createStatement();
    ResultSet resultSet =
        statement.executeQuery("SELECT generate_series(1,50) AS id, md5(random()::text) AS descr");

    int fetchSize = resultSet.getFetchSize();
    resultSet.close();
    statement.close();

    assertThat("Adaptive fetch size can't work without samples, that why first fetch size equal "
            + "to default fetch size, so we should see it value via ResultSet#getFetchSize",
        fetchSize, equalTo(initFetchSize)
    );
  }

  @Test
  public void testFetchSizeChangesAfterFewRoundTrip() throws Exception {
    final int initFetchSize = 5;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, initFetchSize);
    PGProperty.DEFAULT_ROW_FETCH_SIZE_IN_BYTES.set(properties, "100");

    connection = openConnection(properties);

    Statement statement = connection.createStatement();
    ResultSet resultSet =
        statement.executeQuery(
            "SELECT generate_series(1,1000) AS id, md5(random()::text) AS descr");

    int index = 0;
    while (index < 600 && resultSet.next()) {
      index++;
    }

    int fetchSize = resultSet.getFetchSize();

    resultSet.close();
    statement.close();

    assertThat("ResultSet#getFetchSize always show last fetch size that "
            + "was use for last round trip to database",
        fetchSize, not(equalTo(initFetchSize))
    );
  }

  @Test
  public void testFetchSizeAdaptOnHugeRecordAfterFewRoundTrips() throws Exception {
    final int initFetchSize = 5;
    Properties properties = new Properties();
    PGProperty.DEFAULT_ROW_FETCH_SIZE.set(properties, initFetchSize);
    PGProperty.DEFAULT_ROW_FETCH_SIZE_IN_BYTES.set(properties, "2000");

    connection = openConnection(properties);

    TestUtil.createTable(connection, "test_adaptive_fetch_size", "id int primary key, b text");

    Statement statement = connection.createStatement();
    statement.executeUpdate(
        "INSERT INTO test_adaptive_fetch_size SELECT generate_series(1,1000) AS id, md5(random()::text) AS b"
    );
    statement.close();

    statement = connection.createStatement();
    statement.executeUpdate(
        "UPDATE test_adaptive_fetch_size SET b = b||b||b||b||b||b||b||b||b||b||b where id > 800");
    statement.close();

    statement = connection.createStatement();
    ResultSet resultSet =
        statement.executeQuery("select * from test_adaptive_fetch_size order by id");

    skipRecords(resultSet, initFetchSize + 2);
    int firstFetchSizeEstimate = resultSet.getFetchSize();
    skipRecords(resultSet, 900);
    int secondFetchSizeEstimate = resultSet.getFetchSize();

    resultSet.close();
    statement.close();

    assertThat("In this case we check how fetch size adapts on data, first N rows was small enough "
            + "but then was only huge record, so, fetch size should less "
            + "than during fetch small records",
        secondFetchSizeEstimate < firstFetchSizeEstimate,
        equalTo(true)
    );
  }

  private void skipRecords(ResultSet resultSet, int size) throws SQLException {
    int index = 0;
    while (index < size && resultSet.next()) {
      index++;
    }
  }

  private Connection openConnection(Properties properties) throws Exception {
    Connection connection = TestUtil.openDB(properties);
    connection.setAutoCommit(false);

    return connection;
  }

}
